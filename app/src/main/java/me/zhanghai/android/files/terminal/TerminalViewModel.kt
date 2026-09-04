/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import androidx.lifecycle.ViewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Bridges the activity-scoped view model to the app-scoped [TerminalSessionManager]: the
 * fragment creates a new [TerminalClient] per view (so rotation re-binds the view callbacks)
 * and this view model only hands out the session, re-attaching the new client through
 * [TerminalSession.updateTerminalSessionClient] when the session already exists.
 *
 * The session itself is owned by [TerminalSessionManager], NOT this view model, so closing
 * the activity (returning to the file list) keeps the terminal running behind the floating
 * bubble instead of killing it via [ViewModel.onCleared].
 */
class TerminalViewModel constructor(
    private val cwd: String,
    private val asRoot: Boolean
) : ViewModel() {

    /**
     * Returns the existing session (updating its client) or creates one with the given [client].
     */
    fun getOrCreateSession(client: TerminalSessionClient): TerminalSession =
        TerminalSessionManager.getOrCreate(cwd, asRoot, client)
}
