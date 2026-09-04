/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * App-scoped owner of the terminal session. The session outlives [TerminalActivity] so that
 * closing the terminal (returning to the file list) keeps it running; the floating bubble
 * ([TerminalBubble]) then stays usable, and [close] (from the bubble's "close" menu) is the
 * only way to fully end it.
 */
object TerminalSessionManager {

    private var session: TerminalSession? = null
    private var cwd: String? = null
    private var asRoot = false

    /**
     * Returns the existing session (re-attaching [client]) or creates one for the given
     * [cwd]/[asRoot]. A different directory starts a fresh session and ends the previous one.
     */
    @Synchronized
    fun getOrCreate(cwd: String, asRoot: Boolean, client: TerminalSessionClient): TerminalSession {
        val existing = session
        if (existing != null && existing.isRunning()
            && this.cwd == cwd && this.asRoot == asRoot
        ) {
            existing.updateTerminalSessionClient(client)
            return existing
        }
        existing?.finishIfRunning()
        val newSession = createSession(cwd, asRoot, client)
        session = newSession
        this.cwd = cwd
        this.asRoot = asRoot
        return newSession
    }

    fun isRunning(): Boolean = session?.isRunning() == true

    /** Working directory of the current session, for relaunching it from the bubble. */
    @Synchronized
    fun sessionArgs(): TerminalArgs? {
        val cwd = cwd ?: return null
        return TerminalArgs(cwd, asRoot)
    }

    /** The shell exited by itself (e.g. `exit`): forget the session. */
    @Synchronized
    fun onSessionEnded() {
        session = null
        cwd = null
    }

    /** Fully ends the session (called from the bubble's "close terminal" menu). */
    @Synchronized
    fun close() {
        session?.finishIfRunning()
        session = null
        cwd = null
    }

    private fun createSession(
        cwd: String,
        asRoot: Boolean,
        client: TerminalSessionClient
    ): TerminalSession {
        val (shellPath, args) = if (asRoot) {
            // Invoke su to spawn an interactive root shell on the same PTY.
            "su" to arrayOf("su", "-c", "/system/bin/sh -i")
        } else {
            "/system/bin/sh" to arrayOf("/system/bin/sh")
        }
        val env = arrayOf(
            "TERM=xterm-256color",
            // Android system busybox/toybox commands; /system/bin must be searched for `su`.
            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/odm/bin",
            "HOME=$cwd",
            "SHELL=/system/bin/sh",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "EXTERNAL_STORAGE=/sdcard",
            "LD_LIBRARY_PATH=/sbin:/vendor/lib64:/system/lib64"
        )
        return TerminalSession(shellPath, cwd, args, env, null, client)
    }
}
