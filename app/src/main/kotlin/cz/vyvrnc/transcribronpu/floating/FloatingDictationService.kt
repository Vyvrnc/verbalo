package cz.vyvrnc.transcribronpu.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import cz.vyvrnc.transcribronpu.history.AppLog
import cz.vyvrnc.transcribronpu.history.DictationHistory
import cz.vyvrnc.transcribronpu.llm.LlmCleaner
import cz.vyvrnc.transcribronpu.llm.LlmModel
import cz.vyvrnc.transcribronpu.npu.NpuEngine
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.dataStore
import dev.soupslurpr.transcribro.preferences.PreferencesUiState
import androidx.datastore.preferences.core.Preferences
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService
import kotlin.math.abs

/**
 * Floating dictation button over all apps. Tap to dictate, tap again to stop; the text goes
 * into the focused field through [TextInsertionService], or to the clipboard without it.
 *
 * Runs as a microphone foreground service started from the app's settings while the app is
 * visible: Android 14+ grants microphone access to a foreground service only when it was
 * started from the foreground, so the button cannot start itself from the background later.
 */
class FloatingDictationService : Service() {
    companion object {
        private const val CHANNEL = "floating_dictation"
        private const val TAG = "Plovoucí tlačítko"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "cz.vyvrnc.transcribronpu.floating.STOP"
        private const val PREFS = "floating_button"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingDictationService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingDictationService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var windowManager: WindowManager
    private var button: FrameLayout? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private var dictating = false
    /** Gemma cleanup for this dictation; segments are then inserted only after it. */
    private var cleanup: LlmModel? = null
    /** The field the dictation started in, captured before the bubble appears. */
    private var target: LlmCleaner.Target? = null
    /** A finished dictation is being cleaned up; a new one waits for it. */
    private var cleaning = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Final text of this dictation so far, and whether some of it could not be inserted. */
    private val sessionText = StringBuilder()
    /** The dictation's entry in DictationHistory, from the recognition result. */
    private var historyId: String? = null
    private var insertFailed = false

    /** "Auto-Send Transcription" read at the start of this dictation. */
    private var autoSend = false

    /** The app's settings (keyboard switches), kept current so a tap never waits for the disk. */
    @Volatile private var settings: Preferences? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        scope.launch { dataStore.data.collect { settings = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (button == null) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Plovoucí tlačítko potřebuje oprávnění „Zobrazovat přes ostatní aplikace“", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            addButton()
            _running.value = true
        }
        // Not restarted by the system: a restart would come from the background, where the
        // microphone is not available to a foreground service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        button?.let { runCatching { windowManager.removeView(it) } }
        button = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Plovoucí tlačítko", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FloatingDictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_floating_mic)
            .setContentTitle("Plovoucí diktování je zapnuté")
            .setContentText("Mikrofon se zapne jen po klepnutí na tlačítko.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Vypnout", stop).build())
            .build()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never focusable: the text field in the app below must keep the input focus.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = dp(56)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val params = overlayParams(size, size, touchable = true).apply {
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - size - dp(12))
            y = prefs.getInt("y", resources.displayMetrics.heightPixels / 2)
        }
        val view = FrameLayout(this).apply {
            contentDescription = "Diktovat"
            elevation = dp(6).toFloat()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_floating_mic)
                setColorFilter(0xFFFFFFFF.toInt())
                val pad = dp(14)
                setPadding(pad, pad, pad, pad)
            }, FrameLayout.LayoutParams(size, size))
        }
        paintButton(view, false)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                        bubble?.let { positionBubble(it) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    } else {
                        view.performClick()
                        toggleDictation()
                    }
                }
            }
            true
        }
        windowManager.addView(view, params)
        button = view
        buttonParams = params
    }

    private fun paintButton(view: View, active: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) 0xFFC62828.toInt() else 0xFF37474F.toInt())
        }
    }

    private fun toggleDictation() {
        if (cleaning) return
        if (dictating) {
            recognizer?.stopListening()
            showBubble("Dokončuji přepis…")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(
            this, ComponentName(this, MainRecognitionService::class.java)
        ).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        sessionText.clear()
        insertFailed = false
        bubbleDismissed = false
        historyId = null
        // Not preloaded while speaking: Whisper and Gemma do not fit in memory together.
        // The target app decides: some apps get the raw transcript, inserted while speaking.
        val focused = TextInsertionService.target()
        cleanup = NpuSettings.cleanupModel(this)
            .takeIf { NpuSettings.cleanupFor(this, focused?.packageName) && it.ready(this) }
        target = if (cleanup != null) focused else null
        AppLog.i(this, TAG, "diktát: cíl ${focused?.packageName ?: "neznámý"} (${TextInsertionService.lastLookup}), " +
            "úprava ${if (cleanup != null) "ano" else "ne"}")
        // The keyboard's switches in the settings apply to the floating button too.
        val prefs = PreferencesUiState()
        val stored = settings
        val autoStop = stored?.get(prefs.autoStopRecognition.first) ?: prefs.autoStopRecognition.second.value
        autoSend = stored?.get(prefs.autoSendTranscription.first) ?: prefs.autoSendTranscription.second.value
        TextInsertionService.newDictation()
        dictating = true
        button?.let { paintButton(it, true) }
        showBubble(when {
            focused != null -> "Poslouchám…"
            !TextInsertionService.enabled(this) -> "Poslouchám… (vkládání do pole je vypnuté, text půjde do schránky)"
            !TextInsertionService.connected() ->
                "Poslouchám… (Android vkládání uspal – vypněte a zapněte ho v Usnadnění, text půjde do schránky)"
            else -> "Poslouchám…"
        })
        r.startListening(Intent().apply {
            putExtra(MainRecognitionService.EXTRA_SOURCE, "plovoucí tlačítko")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(MainRecognitionService.EXTRA_LIVE_PARTIALS, true)
            // Off: ends with the next tap, so pauses while thinking do not cut the dictation.
            putExtra(MainRecognitionService.EXTRA_AUTO_STOP, autoStop)
        })
    }

    /** [ok] false: recognition failed, so the text must not be auto-sent. */
    private fun finishDictation(message: String?, ok: Boolean) {
        dictating = false
        button?.let { paintButton(it, false) }
        val model = cleanup
        if (model != null && sessionText.isNotEmpty()) {
            val raw = sessionText.toString()
            val into = target
            showBubble("Upravuji text…")
            cleaning = true
            scope.launch {
                // The recognition session is over; Whisper reloads with the next dictation.
                val result = NpuEngine.whileReleased(this@FloatingDictationService) {
                    LlmCleaner.clean(this@FloatingDictationService, raw, into, model)
                }
                val cleaned = result.text
                historyId?.let { id ->
                    if (result.edited) {
                        // The history is a by-product: a failure (full storage) must not stop the insertion.
                        runCatching {
                            DictationHistory.saveCleanup(this@FloatingDictationService, id, cleaned, model.name,
                                into?.appLabel ?: into?.packageName, result.loadMs, result.generateMs)
                        }.onFailure { AppLog.e(this@FloatingDictationService, TAG, "uložení do historie selhalo", it) }
                    }
                }
                sessionText.setLength(0)
                sessionText.append(cleaned)
                if (insert(cleaned) != true) insertFailed = true
                cleaning = false
                deliverEnd(message ?: result.error?.let { "Úprava textu se nepovedla, vložen původní text" },
                    ok && result.error == null)
            }
            return
        }
        deliverEnd(message, ok)
    }

    /** Inserts into the focused field and logs why it did not work. */
    private fun insert(text: String): Boolean? {
        val result = TextInsertionService.insert(text)
        when (result) {
            true -> {}
            false -> AppLog.i(this, TAG, "vložení: ${TextInsertionService.lastLookup}, text jde do schránky")
            null -> AppLog.i(this, TAG, if (TextInsertionService.enabled(this)) {
                "vložení: služba je v Usnadnění zapnutá, ale Android ji odpojil, text jde do schránky"
            } else {
                "vložení: služba přístupnosti „vkládání diktátu“ je vypnutá, text jde do schránky"
            })
        }
        return result
    }

    private fun deliverEnd(message: String?, ok: Boolean) {
        if (autoSend && ok && !insertFailed && sessionText.isNotBlank()) {
            AppLog.i(this, TAG, "odeslání: ${TextInsertionService.send()}")
        }
        if (insertFailed && sessionText.isNotEmpty()) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Diktát", sessionText.toString()))
            Toast.makeText(this, "Text je ve schránce (zapněte vkládání v nastavení Transcribro)", Toast.LENGTH_LONG).show()
        } else if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        hideBubble()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            // Spaces only: a segment may be or contain a spoken line break ("nový řádek").
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.trim(' ', '\t')
            if (text.isNullOrEmpty()) return
            if (partialResults.getBoolean(MainRecognitionService.LIVE_PARTIAL)) {
                if (text.isNotBlank()) showBubble(text.trim())
                return
            }
            // A finished segment goes into the field right away.
            if (sessionText.isNotEmpty() && !sessionText.endsWith('\n') && !text.startsWith('\n')) sessionText.append(' ')
            sessionText.append(text)
            // With cleanup the whole dictation is inserted at the end instead.
            if (cleanup == null && !insertFailed && insert(text) != true) insertFailed = true
            showBubble(sessionText.takeLast(160).toString())
        }

        override fun onResults(results: Bundle?) {
            historyId = results?.getString(MainRecognitionService.HISTORY_ID)
            finishDictation(null, ok = true)
        }

        override fun onError(error: Int) = finishDictation(
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "NPU model není stažený (Nastavení Transcribro)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chybí oprávnění k mikrofonu"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Rozpoznávání je zaneprázdněné"
                SpeechRecognizer.ERROR_CLIENT -> "Přepis se nepovedl"
                else -> null
            },
            ok = false,
        )
    }

    /** The user tapped the bubble away for this dictation. */
    private var bubbleDismissed = false

    private fun showBubble(text: String) {
        if (bubbleDismissed) return
        val b = bubble ?: TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 4
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            // Opaque: the bubble must stay readable over any app.
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF263238.toInt())
            }
        }.also { tv ->
            // Touchable: Android 12+ drops touches that pass through an opaque overlay of another
            // app anyway, so the bubble takes them and a tap hides it for this dictation.
            val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
            tv.setOnClickListener {
                bubbleDismissed = true
                hideBubble()
            }
            tv.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            windowManager.addView(tv, params)
            bubble = tv
        }
        b.text = text
        positionBubble(b)
    }

    private fun positionBubble(b: TextView) {
        val bp = buttonParams ?: return
        val params = b.layoutParams as? WindowManager.LayoutParams ?: return
        val width = resources.displayMetrics.widthPixels
        b.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        params.x = (bp.x + dp(28) - b.measuredWidth / 2).coerceIn(dp(8), (width - b.measuredWidth - dp(8)).coerceAtLeast(dp(8)))
        params.y = (bp.y - b.measuredHeight - dp(8)).let { if (it < dp(24)) bp.y + dp(64) else it }
        runCatching { windowManager.updateViewLayout(b, params) }
    }

    private fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }
}
