package com.morty649.murmur

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service whose only job is to put dictated text into the focused text box.
 * It does not read or log anything else on screen.
 */
class TypeService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        for (w in windows) {
            w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        }
        return null
    }

    /** Inserts [text] at the cursor (replacing any selection). Returns false if no text box is focused. */
    fun insert(text: String): Boolean {
        val node = focusedInput() ?: return false
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        if (start > end) start = end.also { end = start }

        val needsSpace = start > 0 && !current[start - 1].isWhitespace() &&
            text.firstOrNull()?.let { it.isLetterOrDigit() } == true
        val piece = (if (needsSpace) " " else "") + text
        val updated = current.substring(0, start) + piece + current.substring(end)
        val cursor = start + piece.length

        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            return true
        }
        // Some apps (e.g. custom editors) refuse SET_TEXT; paste instead.
        copyToClipboard(this, piece)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    companion object {
        @Volatile
        var instance: TypeService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, TypeService::class.java)
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun copyToClipboard(ctx: Context, text: String) {
            ctx.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Murmur", text))
        }
    }
}
