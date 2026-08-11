/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.arsc

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class ArscEditorActivity : AppActivity() {
    private lateinit var fragment: ArscEditorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            fragment = ArscEditorFragment().putArgs(
                ArscEditorFragment.Args(intent.extraPath ?: run { finish(); return })
            )
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as ArscEditorFragment
        }
    }

    // The toolbar up arrow pops the page navigation instead of finishing.
    override fun onSupportNavigateUp(): Boolean {
        if (fragment.navigateUp()) {
            return true
        }
        return super.onSupportNavigateUp()
    }
}
