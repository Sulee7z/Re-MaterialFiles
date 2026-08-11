/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs

class TerminalActivity : AppActivity() {

    private val args by args<TerminalArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = TerminalFragment().putArgs(args)
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }
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
