package cz.vyvrnc.transcribronpu.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.StringRes
import dev.soupslurpr.transcribro.R
import cz.vyvrnc.transcribronpu.llm.LlmModel
import cz.vyvrnc.transcribronpu.npu.NpuModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads the models in a dataSync foreground service, so a multi-gigabyte download keeps
 * going when the settings screen is left or the display turns off (a coroutine in the UI was
 * stopped by the system as soon as the app went to the background). Holds a partial wake
 * lock and a Wi-Fi lock while a download runs. Started from the visible settings screen.
 */
class ModelDownloadService : Service() {
    enum class Model(@StringRes val titleRes: Int, val notificationId: Int) {
        WHISPER(R.string.download_model_whisper, 41),
        GEMMA(R.string.download_model_gemma_gpu, 42),
        GEMMA_NPU(R.string.download_model_gemma_npu, 43);

        fun title(context: Context): String = context.getString(titleRes)

        fun ready(context: Context) = when (this) {
            WHISPER -> NpuModel.ready(context)
            GEMMA -> LlmModel.GPU.ready(context)
            GEMMA_NPU -> LlmModel.NPU.ready(context)
        }

        suspend fun download(context: Context, onProgress: (String, Long, Long) -> Unit) = when (this) {
            WHISPER -> NpuModel.download(context, onProgress)
            GEMMA -> LlmModel.GPU.download(context, onProgress)
            GEMMA_NPU -> LlmModel.NPU.download(context, onProgress)
        }
    }

    /** [progress] is null while connecting; [status] is the last message (progress or result). */
    data class State(val running: Boolean, val progress: Float? = null, val status: String? = null)

    companion object {
        private const val TAG = "ModelDownload"
        private const val CHANNEL = "model_download"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_MODEL = "model"

        private val _states = MutableStateFlow<Map<Model, State>>(emptyMap())
        val states: StateFlow<Map<Model, State>> = _states.asStateFlow()

        fun start(context: Context, model: Model) {
            context.startForegroundService(intent(context, model, ACTION_START))
        }

        fun cancel(context: Context, model: Model) {
            context.startService(intent(context, model, ACTION_CANCEL))
        }

        private fun intent(context: Context, model: Model, action: String) =
            Intent(context, ModelDownloadService::class.java).setAction(action).putExtra(EXTRA_MODEL, model.name)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The current job per model; guarded by itself. A replaced job never touches its successor. */
    private val jobs = mutableMapOf<Model, Job>()
    @Volatile private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val model = intent?.getStringExtra(EXTRA_MODEL)?.let { name -> Model.entries.firstOrNull { it.name == name } }
        // startForegroundService() must always be answered with startForeground().
        startForeground(
            model?.notificationId ?: Model.WHISPER.notificationId,
            notification(model ?: Model.WHISPER, null, getString(R.string.download_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        when {
            model == null -> {}
            intent.action == ACTION_CANCEL -> synchronized(jobs) { jobs[model]?.cancel() }
            intent.action == ACTION_START -> launch(model)
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    /**
     * Android 15+: the daily dataSync time is used up. Stops right away: a blocking read does
     * not react to cancellation and the system allows only a few seconds. The partial file
     * stays for a resume.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout")
        synchronized(jobs) {
            jobs.forEach { (model, job) ->
                job.cancel()
                _states.update { it + (model to State(running = false, status = getString(R.string.download_paused))) }
                getSystemService(NotificationManager::class.java).cancel(model.notificationId)
            }
            jobs.clear()
        }
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun launch(model: Model) = synchronized(jobs) {
        if (jobs[model]?.isActive == true) return@synchronized
        acquireLocks()
        _states.update { it + (model to State(running = true, status = getString(R.string.download_connecting))) }
        val nm = getSystemService(NotificationManager::class.java)
        // Set by the body; stays as is when the job is cancelled before it even starts.
        var result: String? = getString(R.string.download_interrupted)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            val started = System.nanoTime()
            var lastNotify = 0L
            result = try {
                model.download(this@ModelDownloadService) { phase, done, total ->
                    val secs = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.1)
                    val fraction = done.toFloat() / total.coerceAtLeast(1)
                    val text = getString(R.string.download_progress,
                        phase, done / 1048576.0, total / 1048576.0, done / 1048576.0 / secs)
                    synchronized(jobs) {
                        if (jobs[model] !== self) return@download
                        _states.update { it + (model to State(running = true, progress = fraction, status = text)) }
                    }
                    val now = System.nanoTime()
                    if (now - lastNotify > 1_000_000_000L) {
                        lastNotify = now
                        nm.notify(model.notificationId, notification(model, fraction, text))
                    }
                }
                if (model.ready(this@ModelDownloadService)) null else getString(R.string.download_incomplete)
            } catch (e: CancellationException) {
                getString(R.string.download_interrupted)
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                getString(R.string.download_error, e.message ?: e.javaClass.simpleName)
            }
        }
        // Runs also for a job cancelled before its body started.
        job.invokeOnCompletion {
            val current = synchronized(jobs) {
                if (jobs[model] !== job) return@invokeOnCompletion
                jobs.remove(model)
                _states.update { it + (model to State(running = false, status = result)) }
                true
            }
            if (current) {
                nm.cancel(model.notificationId)
                stopIfIdle()
            }
        }
        jobs[model] = job
        job.start()
    }

    private fun stopIfIdle() = synchronized(jobs) {
        if (jobs.values.none { it.isActive }) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Only if no newer start is queued; that start brings its own startForeground().
            stopSelfResult(lastStartId)
        } else {
            // Keep the foreground notification on a download that still runs.
            val model = jobs.entries.first { it.value.isActive }.key
            val s = _states.value[model]
            startForeground(model.notificationId, notification(model, s?.progress, s?.status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transcribro:download")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Transcribro:download")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun notification(model: Model, progress: Float?, text: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(
            this, model.notificationId, intent(this, model, ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title, model.title(this)))
            .setContentText(text ?: getString(R.string.download_connecting))
            .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.download_cancel), cancel).build())
            .build()
    }
}
