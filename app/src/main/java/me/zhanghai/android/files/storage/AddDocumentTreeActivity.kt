/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.getArgsOrNull
import me.zhanghai.android.files.util.putArgs

class AddDocumentTreeActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val initialUri = intent.extras?.getArgsOrNull<AddDocumentTreeFragment.Args>()?.initialUri
            val fragment = AddDocumentTreeFragment().putArgs(AddDocumentTreeFragment.Args(initialUri))
            supportFragmentManager.commit { add(fragment, AddDocumentTreeFragment::class.java.name) }
        }
    }
}
