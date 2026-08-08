/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import androidx.lifecycle.ViewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Owns the [TerminalSession] across configuration changes. The fragment creates a new
 * [TerminalClient] per view (so rotation re-binds the view callbacks) and this view model only
 * holds the session, re-attaching the new client through
 * [TerminalSession.updateTerminalSessionClient] on rotation.
 */
class TerminalViewModel constructor(
    private val cwd: String,
    private val asRoot: Boolean
) : ViewModel() {

    private var session: TerminalSession? = null

    /**
     * Returns the existing session (updating its client) or creates one with the given [client].
     */
    fun getOrCreateSession(client: TerminalSessionClient): TerminalSession {
        val existing = session
        if (existing != null) {
            existing.updateTerminalSessionClient(client)
            return existing
        }
        val newSession = createSession(client)
        session = newSession
        return newSession
    }

    private fun createSession(client: TerminalSessionClient): TerminalSession {
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

    override fun onCleared() {
        session?.finishIfRunning()
    }
}