/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class DexSmaliEditorActivity : AppActivity() {
    private lateinit var fragment: DexSmaliEditorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val path = intent.extraPath ?: run { finish(); return }
            val className = intent.getStringExtra(DexSmaliEditorFragment.EXTRA_CLASS_NAME)
                ?: run { finish(); return }
            val methodKey = intent.getStringExtra(DexSmaliEditorFragment.EXTRA_METHOD_KEY)
                ?: run { finish(); return }
            fragment = DexSmaliEditorFragment().putArgs(
                DexSmaliEditorFragment.Args(
                    path,
                    intent.getStringExtra(DexSmaliEditorFragment.EXTRA_SOURCE_DEX),
                    className,
                    methodKey
                )
            )
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as DexSmaliEditorFragment
        }
    }
}
