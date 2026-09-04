/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs

class TerminalActivity : AppActivity() {

    private val args by args<TerminalArgs>()

    private var minimizeToBubble = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The previous instance may have finished with the bubble shown; a fresh instance
        // (rotation, or relaunching from the bubble) should not re-show it.
        minimizeToBubble = false

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = TerminalFragment().putArgs(args)
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }

        // The back key finishes the terminal instead of closing it: the session is owned by
        // the app-scoped TerminalSessionManager and keeps running, and the floating bubble
        // shown in onDestroy() stays as its entry point.
        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    minimizeToBubble()
                }
            }
        )
    }

    // The toolbar up arrow returns to the file list (the session keeps running, owned by the
    // app-scoped TerminalSessionManager; the bubble appears if the overlay permission is
    // granted). No permission prompt here — that only happens via the back key.
    override fun onSupportNavigateUp(): Boolean {
        minimizeToBubble = true
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (minimizeToBubble && TerminalSessionManager.isRunning()) {
            TerminalBubble.show()
        } else {
            TerminalBubble.hide()
        }
    }

    /**
     * Finishes the activity (revealing the file list underneath) and shows the floating
     * bubble as the entry point to the still-running session. On the first use the overlay
     * permission is requested; without it the session keeps running in the background but
     * no bubble is shown.
     */
    private fun minimizeToBubble() {
        minimizeToBubble = true
        if (!Settings.canDrawOverlays(this)) {
            // No overlay permission: jump straight to the system overlay settings page and
            // STAY here — do not finish or move the task to back, otherwise the terminal
            // appears to "flash away". The user grants the permission, returns to the
            // terminal, and presses back again to minimize into the bubble.
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        finish()
    }

    // Physical (bluetooth/USB) keyboard support: route every key event to the TerminalView
    // first so ctrl/alt/shift combinations (e.g. Ctrl+C) reach the emulator, like Termux.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = supportFragmentManager
            .findFragmentById(android.R.id.content) as? TerminalFragment
        if (fragment != null && fragment.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
