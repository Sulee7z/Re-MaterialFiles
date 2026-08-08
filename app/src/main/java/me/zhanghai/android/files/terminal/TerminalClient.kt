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
    @Volatile
    var ctrlKey: Boolean = false
        private set
    @Volatile
    var altKey: Boolean = false
        private set
    @Volatile
    var shiftKey: Boolean = false
        private set
    @Volatile
    var fnKey: Boolean = false
        private set

    /** Whether the shell process under the session is still running. */
    @Volatile
    var isSessionAlive: Boolean = true
        private set

    fun toggleCtrl() {
        ctrlKey = !ctrlKey
    }

    fun toggleAlt() {
        altKey = !altKey
    }

    fun toggleShift() {
        shiftKey = !shiftKey
    }

    fun toggleFn() {
        fnKey = !fnKey
    }

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

    override fun readControlKey() = ctrlKey

    override fun readAltKey() = altKey

    override fun readShiftKey() = shiftKey

    override fun readFnKey() = fnKey

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