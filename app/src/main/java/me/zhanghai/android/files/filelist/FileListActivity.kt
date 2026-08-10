/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat

class FileListActivity : AppActivity() {
    private lateinit var fragment: FileListFragment

    /** The two-pane value this Activity was created with; used to rebuild only on change. */
    private val twoPaneAtCreation: Boolean = Settings.FILE_LIST_TWO_PANE.valueCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (twoPaneAtCreation) {
            // MT Manager style two-pane browsing: two independent file lists side by side.
            // Copy/cut on one pane and paste on the other to move files across panes (the
            // paste state is shared). The right pane starts at the default directory.
            // The layout uses fixed container ids so FragmentManager can restore the two
            // fragments across Activity recreation.
            setContentView(R.layout.file_list_activity_two_pane)
            // Fixed widths instead of layout weights: DrawerLayout (the FileListFragment
            // root) crashes when measured with AT_MOST by a weighted LinearLayout.
            val paneWidth = resources.displayMetrics.widthPixels / 2
            findViewById<View>(R.id.leftPane).layoutParams.width = paneWidth
            findViewById<View>(R.id.rightPane).layoutParams.width = paneWidth
            // ZenFile-style active-pane tracking: touching either pane makes it the active
            // pane, and the back key navigates it. Container-level listeners fire on any
            // touch inside the pane (taps, scrolls, long-presses).
            findViewById<View>(R.id.leftPane).setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    TwoPaneState.activePaneSecondary = false
                }
                false
            }
            findViewById<View>(R.id.rightPane).setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    TwoPaneState.activePaneSecondary = true
                }
                false
            }
            // Back key navigates the pane the user last touched (both fragments do not
            // register their own back callbacks in two-pane mode).
            onBackPressedDispatcher.addCallback(
                this,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        Log.i(
                            "TwoPaneDebug",
                            "back pressed: activePaneSecondary=${TwoPaneState.activePaneSecondary}"
                        )
                        val activeFragment = if (TwoPaneState.activePaneSecondary) {
                            supportFragmentManager
                                .findFragmentById(R.id.rightPane) as? FileListFragment
                        } else {
                            supportFragmentManager
                                .findFragmentById(R.id.leftPane) as? FileListFragment
                        }
                        if (activeFragment == null || !activeFragment.performBack()) {
                            // The touched pane cannot navigate up (e.g. it is at its root):
                            // try the other pane before falling through to the default exit.
                            val otherFragment = if (TwoPaneState.activePaneSecondary) {
                                supportFragmentManager
                                    .findFragmentById(R.id.leftPane) as? FileListFragment
                            } else {
                                supportFragmentManager
                                    .findFragmentById(R.id.rightPane) as? FileListFragment
                            }
                            if (otherFragment == null || !otherFragment.performBack()) {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                }
            )
            if (savedInstanceState == null) {
                val leftFragment = FileListFragment().putArgs(FileListFragment.Args(intent))
                supportFragmentManager.commit { add(R.id.leftPane, leftFragment) }
                val rightIntent = FileListActivity.createViewIntent(
                    Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
                )
                val rightFragment = FileListFragment()
                    .putArgs(FileListFragment.Args(rightIntent, secondaryPane = true))
                supportFragmentManager.commit { add(R.id.rightPane, rightFragment) }
            }
        } else if (savedInstanceState == null) {
            fragment = FileListFragment().putArgs(FileListFragment.Args(intent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as FileListFragment
        }
    }

    override fun onResume() {
        super.onResume()
        // Apply two-pane setting changes when returning from the settings screen: the
        // Activity is stopped while the setting screen is on top. Recreate() would restore
        // the old fragment hierarchy into containers that no longer match the new pane
        // layout (crash), so finish and start a fresh instance instead.
        if (Settings.FILE_LIST_TWO_PANE.valueCompat != twoPaneAtCreation) {
            finish()
            startActivity(Intent(this, FileListActivity::class.java))
        }
    }

    private fun currentFragment(): FileListFragment? =
        if (Settings.FILE_LIST_TWO_PANE.valueCompat) {
            supportFragmentManager.findFragmentById(R.id.leftPane) as? FileListFragment
        } else {
            fragment
        }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val currentFragment = currentFragment()
        if (currentFragment != null && currentFragment.onKeyShortcut(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
