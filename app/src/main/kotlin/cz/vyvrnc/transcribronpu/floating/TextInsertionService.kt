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

/**
 * Inserts dictated text into the focused text field of any app, at the cursor (replacing a
 * selection); without input focus into the last editable field of the foreground app. Used only
 * by the floating button; it reads no screen content beyond finding that field (its app, hint
 * and text, as context for the on-device cleanup) and keeps nothing.
 */
class TextInsertionService : AccessibilityService() {
    companion object {
        @Volatile private var instance: TextInsertionService? = null

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
     * tapped yet) the last visible editable field of the foreground app, usually the composer.
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
        val field = lastEditable(root)
        lastLookup = if (field != null) "pole bez kurzoru v ${root.packageName}" else "${root.packageName} bez upravitelného pole"
        return field
    }

    /** Root of the foreground app window, skipping our own overlay, the keyboard and system UI. */
    private fun appWindowRoot(): AccessibilityNodeInfo? =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.isActive })
            .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() != packageName } }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }

    private fun lastEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        // Depth-first in screen order; capped so a huge list cannot stall the dictation.
        while (stack.isNotEmpty() && visited < 3000) {
            val node = stack.removeLast()
            visited++
            if (node.isEditable && node.isVisibleToUser) found = node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it) }
        }
        return found
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
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diktát", insert))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
