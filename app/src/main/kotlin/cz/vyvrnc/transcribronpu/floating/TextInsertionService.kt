package cz.vyvrnc.transcribronpu.floating

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cz.vyvrnc.transcribronpu.llm.LlmCleaner
import dev.soupslurpr.transcribro.R

/**
 * Inserts dictated text into the focused text field of any app, at the cursor (replacing a
 * selection); without input focus into the only editable field of the foreground app. Used only
 * by the floating button; it reads no screen content beyond finding that field (its app, hint
 * and text, as context for the on-device cleanup) and keeps nothing.
 */
class TextInsertionService : AccessibilityService() {
    companion object {
        @Volatile private var instance: TextInsertionService? = null
        private const val MAX_NODES = 400

        /** True when inserted, false when there is no editable field, null when the service is not bound. */
        fun insert(text: String): Boolean? = instance?.insertText(text)

        /**
         * The target field's app, hint and text before the cursor, for the per-app rule and the
         * cleanup prompt. Without a field it still names the app in the foreground.
         */
        fun target(): LlmCleaner.Target? {
            val service = instance
            if (service == null) lastLookup = "služba nepřipojená"
            return service?.focusedTarget()
        }

        /**
         * The keyboard's send/enter action in the field the dictation went into, like the
         * keyboard's "Auto-Send Transcription". What it does is up to the app: send in a chat,
         * a new line in an e-mail body.
         */
        fun send(): String = instance?.sendText() ?: "služba nepřipojená, neodesláno"

        /** Forgets the field of the previous dictation, so auto-send never reaches it. */
        fun newDictation() {
            instance?.let { it.insertedInto = null; it.splitAcrossFields = false }
        }

        /** Bound by the system; can be false while the service is switched on in the settings. */
        fun connected(): Boolean = instance != null

        /** How the last lookup found its field, for the in-app log. */
        @Volatile var lastLookup: String = "služba nepřipojená"
            private set

        fun enabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${TextInsertionService::class.java.name}"
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
        lastLookup = "služba připojená"
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        lastLookup = "služba nepřipojená"
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun focusedTarget(): LlmCleaner.Target? {
        val node = editableField()
        if (node == null) {
            // No field, but the app still decides whether the dictation gets cleaned up.
            val pkg = appWindowRoot()?.packageName?.toString() ?: return null
            return LlmCleaner.Target(pkg, appLabel(pkg), null, null)
        }
        val pkg = node.packageName?.toString() ?: return null
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val cursor = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        return LlmCleaner.Target(pkg, appLabel(pkg), node.hintText?.toString(), current.substring(0, cursor).takeLast(300))
    }

    private fun appLabel(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /**
     * The field to write into: the one with input focus, otherwise (chat opened but the field not
     * tapped yet) the only visible editable field of the foreground app. With several candidates
     * (search box and composer) it gives up and the text goes to the clipboard.
     */
    private fun editableField(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let {
            lastLookup = "pole s kurzorem v ${it.packageName}"
            return it
        }
        val root = appWindowRoot()
        if (root == null) {
            lastLookup = "žádné okno aplikace v popředí"
            return null
        }
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let {
            lastLookup = "pole s kurzorem v okně ${root.packageName}"
            return it
        }
        val (field, why) = onlyEditable(root)
        lastLookup = "${root.packageName}: $why"
        return field
    }

    /**
     * Root of the one foreground app window: focused, else active, else the only one. Our overlay
     * is TYPE_SYSTEM, not TYPE_APPLICATION. An unavailable root never falls through to another
     * app in split screen.
     */
    private fun appWindowRoot(): AccessibilityNodeInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.singleOrNull()
        val root = if (apps.isEmpty()) rootInActiveWindow else window?.root
        return root?.takeIf { it.packageName?.toString() != packageName }
    }

    /**
     * The single visible editable node under [root]. Runs on the main thread and every child is a
     * call into the other app, so the walk has a hard budget of fetched nodes.
     */
    private fun onlyEditable(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo?, String> {
        var found: AccessibilityNodeInfo? = null
        var fetched = 1
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser) {
                if (found != null) return null to "víc polí bez kurzoru, nevím kam"
                found = node
            }
            for (i in 0 until node.childCount) {
                if (fetched >= MAX_NODES) return null to "okno je moc velké na hledání pole"
                fetched++
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return found to (if (found != null) "jediné pole bez kurzoru" else "bez upravitelného pole")
    }

    /** The field this dictation's text went into; auto-send acts only on it. */
    @Volatile private var insertedInto: AccessibilityNodeInfo? = null

    /** Parts of this dictation went into different fields: sending either would be a guess. */
    @Volatile private var splitAcrossFields = false

    private fun inserted(node: AccessibilityNodeInfo) {
        if (insertedInto != null && insertedInto != node) splitAcrossFields = true
        insertedInto = node
    }

    private fun sendText(): String {
        val node = insertedInto ?: return "text nebyl vložen do pole, neodesláno"
        if (splitAcrossFields) return "diktát šel do víc polí, neodesláno"
        // The user may have moved to another field (another draft) while the text was finishing.
        if (editableField() != node || !node.refresh()) return "pole se mezitím změnilo, neodesláno"
        if (!node.isFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return "pole nepřijalo fokus, neodesláno"
        }
        return if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            "Enter/Odeslat v poli"
        } else {
            "pole odeslání nepřijalo"
        }
    }

    private fun insertText(text: String): Boolean {
        val node = editableField() ?: return false
        val piece = text.trim(' ', '\t')
        if (piece.isEmpty()) return true
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length; end = current.length
        }
        if (start > end) start = end.also { end = start }
        val before = current.substring(0, start)
        val after = current.substring(end)
        val insert = (if (before.isEmpty() || before.last().isWhitespace()) "" else " ") + piece +
            (if (after.isEmpty() || after.first().isWhitespace() || !after.first().isLetterOrDigit()) "" else " ")
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + insert + after)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            inserted(node)
            val cursor = before.length + insert.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
            return true
        }
        // Some fields (web views, custom editors) ignore SET_TEXT but accept a paste, which
        // needs the input focus (the field may have been found without it).
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.floating_clip_label), insert))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE).also { if (it) inserted(node) }
    }
}
