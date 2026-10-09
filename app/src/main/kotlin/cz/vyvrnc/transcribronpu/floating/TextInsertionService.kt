package cz.vyvrnc.transcribronpu.floating

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts dictated text into the focused text field of any app, at the cursor (replacing a
 * selection). Used only by the floating button; it reads no screen content beyond the
 * focused field and keeps nothing.
 */
class TextInsertionService : AccessibilityService() {
    companion object {
        @Volatile private var instance: TextInsertionService? = null

        /** True when inserted, false when there is no editable focused field, null when the service is off. */
        fun insert(text: String): Boolean? = instance?.insertText(text)

        fun enabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${TextInsertionService::class.java.name}"
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun insertText(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return false
        val piece = text.trim()
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
        // Some fields (web views, custom editors) ignore SET_TEXT but accept a paste.
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diktát", insert))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
