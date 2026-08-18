/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Bridges a [TerminalView] and its [TerminalSession] to the owning [TerminalFragment], implementing
 * both the view client and the session client. A fresh instance is created per fragment view so
 * that rotation naturally re-binds the callbacks to the new view; the session is re-attached to
 * this client through [TerminalSession.updateTerminalSessionClient].
 */
class TerminalClient(
    private val viewProvider: () -> TerminalView?,
    private val onTitleChanged: (String?) -> Unit,
    private val onSessionFinished: (Boolean) -> Unit,
    private val onCopyText: (String) -> Unit,
    private val onPasteText: () -> String?
) : TerminalViewClient, TerminalSessionClient {

    // Extra keys row modifier toggle states, read back by TerminalView during key handling.
    // A modifier is "temporary" when tapped once (it is auto-cleared after the next key event),
    // and "locked" when long-pressed (it stays active until toggled again), like Termux.
    @Volatile
    private var ctrlKeyState: Boolean = false
    @Volatile
    private var ctrlKeyLocked: Boolean = false
    @Volatile
    private var altKeyState: Boolean = false
    @Volatile
    private var altKeyLocked: Boolean = false
    @Volatile
    private var shiftKeyState: Boolean = false
    @Volatile
    private var shiftKeyLocked: Boolean = false
    @Volatile
    private var fnKeyState: Boolean = false
    @Volatile
    private var fnKeyLocked: Boolean = false

    val ctrlKey: Boolean get() = ctrlKeyState
    val altKey: Boolean get() = altKeyState
    val shiftKey: Boolean get() = shiftKeyState
    val fnKey: Boolean get() = fnKeyState

    /** The currently active state of a modifier key (used to highlight it in the UI). */
    fun isModifierActive(key: ModifierKey): Boolean = when (key) {
        ModifierKey.CTRL -> ctrlKeyState
        ModifierKey.ALT -> altKeyState
        ModifierKey.SHIFT -> shiftKeyState
        ModifierKey.FN -> fnKeyState
    }

    enum class ModifierKey { CTRL, ALT, SHIFT, FN }

    /** Toggle a modifier on/off. Toggling off also clears any lock. */
    fun toggleModifier(key: ModifierKey) {
        when (key) {
            ModifierKey.CTRL -> {
                ctrlKeyState = !ctrlKeyState
                if (!ctrlKeyState) ctrlKeyLocked = false
            }
            ModifierKey.ALT -> {
                altKeyState = !altKeyState
                if (!altKeyState) altKeyLocked = false
            }
            ModifierKey.SHIFT -> {
                shiftKeyState = !shiftKeyState
                if (!shiftKeyState) shiftKeyLocked = false
            }
            ModifierKey.FN -> {
                fnKeyState = !fnKeyState
                if (!fnKeyState) fnKeyLocked = false
            }
        }
    }

    /** Turn a modifier on and lock it (long press). */
    fun lockModifier(key: ModifierKey) {
        when (key) {
            ModifierKey.CTRL -> { ctrlKeyState = true; ctrlKeyLocked = true }
            ModifierKey.ALT -> { altKeyState = true; altKeyLocked = true }
            ModifierKey.SHIFT -> { shiftKeyState = true; shiftKeyLocked = true }
            ModifierKey.FN -> { fnKeyState = true; fnKeyLocked = true }
        }
    }

    /** Clear every modifier (used when a non-modifier key is pressed). */
    fun clearModifiers() {
        if (!ctrlKeyLocked) ctrlKeyState = false
        if (!altKeyLocked) altKeyState = false
        if (!shiftKeyLocked) shiftKeyState = false
        if (!fnKeyLocked) fnKeyState = false
    }

    /** Whether the shell process under the session is still running. */
    @Volatile
    var isSessionAlive: Boolean = true
        private set

    fun toggleCtrl() = toggleModifier(ModifierKey.CTRL)
    fun toggleAlt() = toggleModifier(ModifierKey.ALT)
    fun toggleShift() = toggleModifier(ModifierKey.SHIFT)
    fun toggleFn() = toggleModifier(ModifierKey.FN)

    // TerminalViewClient

    override fun onScale(scale: Float): Float {
        if (!isSessionAlive) return 1f
        val view = viewProvider() ?: return scale
        // TerminalView accumulates the returned scale into its font size.
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val view = viewProvider() ?: return
        view.requestFocus()
        view.performClick()
    }

    override fun shouldBackButtonBeMappedToEscape() = false

    override fun shouldEnforceCharBasedInput() = true

    override fun shouldUseCtrlSpaceWorkaround() = true

    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) {
        // Selection toolbar is handled by the system through the contextual action mode.
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // TerminalView handles ctrl/alt/shift/fn combinations via this client's read* methods;
        // there is nothing extra to intercept here.
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = false

    override fun onLongPress(event: MotionEvent?): Boolean {
        // Long press selection is handled internally by TerminalView.
        return false
    }

    override fun readControlKey(): Boolean {
        // A temporary (non-locked) modifier applies to the next key event only and is
        // auto-cleared after being read, exactly like Termux's readSpecialButton(..., true).
        val active = ctrlKeyState
        if (active && !ctrlKeyLocked) ctrlKeyState = false
        return active
    }

    override fun readAltKey(): Boolean {
        val active = altKeyState
        if (active && !altKeyLocked) altKeyState = false
        return active
    }

    override fun readShiftKey(): Boolean {
        val active = shiftKeyState
        if (active && !shiftKeyLocked) shiftKeyState = false
        return active
    }

    override fun readFnKey(): Boolean {
        val active = fnKeyState
        if (active && !fnKeyLocked) fnKeyState = false
        return active
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // Forward codepoints handled by the view; return false to continue default handling.
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        if (Log.isLoggable(tag, Log.VERBOSE)) Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception?) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception?) {
        Log.e(tag, "", e)
    }

    // TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        val view = viewProvider() ?: return
        view.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        isSessionAlive = false
        onSessionFinished(finishedSession.exitStatus == 0)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isNotEmpty()) onCopyText(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = onPasteText() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty()) session?.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null
}