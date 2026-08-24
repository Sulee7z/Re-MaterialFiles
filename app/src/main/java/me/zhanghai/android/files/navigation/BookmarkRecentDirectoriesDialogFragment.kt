/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.pathCompat
import me.zhanghai.android.files.databinding.BookmarkRecentDirectoriesDialogFragmentBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

class BookmarkRecentDirectoriesDialogFragment : AppCompatDialogFragment(),
    BookmarkRecentDirectoryAdapter.Listener {

    private lateinit var binding: BookmarkRecentDirectoriesDialogFragmentBinding

    private lateinit var adapter: BookmarkRecentDirectoryAdapter

    private var bookmarkDirectories: List<BookmarkDirectory> = emptyList()

    private var recentDirectories: List<RecentDirectory> = emptyList()

    private var currentTab = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Inflate the custom layout and set it as the dialog content, exactly
        // like EditBookmarkDirectoryDialogFragment. This gives the dialog the
        // standard Material card appearance (rounded corners, surface color,
        // centered with a max width), matching the "Add storage" dialog, while
        // keeping the toolbar/tabs/list content from the layout.
        //
        // Do NOT go back to an onCreateView()-based fragment view here: returning
        // a plain MaterialAlertDialogBuilder(...).create() (without setView) makes
        // DialogFragment race with AlertController over the content view, and the
        // dialog ends up with a zero-height invisible content. Setting the view in
        // onCreateDialog and doing all initialization here is the working pattern.
        binding = BookmarkRecentDirectoriesDialogFragmentBinding.inflate(requireContext().layoutInflater)

        // Cap the list height so the dialog grows with its content but never
        // fills the whole screen; the list is scrollable past the cap.
        binding.recyclerView.maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()

        binding.toolbar.title = getString(R.string.navigation_recent_bookmark_directories)
        binding.toolbar.inflateMenu(R.menu.bookmark_recent_directories_dialog)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_bookmark -> {
                    onAddBookmarkDirectory()
                    true
                }
                else -> false
            }
        }
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.settings_bookmark_directories_title))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.recent_directories_title))
        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                onTabChanged(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        binding.recyclerView.layoutManager = LinearLayoutManager(
            requireContext(), RecyclerView.VERTICAL, false
        )
        adapter = BookmarkRecentDirectoryAdapter(this)
        binding.recyclerView.adapter = adapter

        binding.tabLayout.getTabAt(
            if (Settings.BOOKMARK_RECENT_DEFAULT_PAGE.valueCompat
                == BookmarkRecentDefaultPage.RECENT
            ) 1 else 0
        )?.select()

        Settings.BOOKMARK_DIRECTORIES.observe(this) {
            bookmarkDirectories = it
            updateList()
        }
        Settings.RECENT_DIRECTORIES.observe(this) {
            recentDirectories = it
            updateList()
        }

        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setView(binding.root)
            .create()
    }

    private fun onTabChanged(position: Int) {
        currentTab = position
        updateList()
    }

    private fun updateList() {
        val items = if (currentTab == 0) {
            bookmarkDirectories.map {
                BookmarkRecentDirectoryAdapter.Item(it.name, it.path, bookmarkDirectory = it)
            }
        } else {
            recentDirectories.map {
                BookmarkRecentDirectoryAdapter.Item(it.name, it.path, recentDirectory = it)
            }
        }
        binding.emptyView.text = getString(
            if (currentTab == 0) R.string.settings_bookmark_directory_list_empty
            else R.string.recent_directories_empty
        )
        binding.emptyView.fadeToVisibilityUnsafe(items.isEmpty())
        // Only the bookmarks tab can add new entries, so only show the add action there.
        binding.toolbar.menu.findItem(R.id.action_add_bookmark)?.isVisible = currentTab == 0
        adapter.replace(items)
    }

    private fun onAddBookmarkDirectory() {
        startActivitySafe(
            EditBookmarkDirectoryDialogActivity::class.createIntent()
                .putArgs(
                    EditBookmarkDirectoryDialogFragment.Args(
                        BookmarkDirectory(null, defaultPath()),
                        isAdd = true
                    )
                )
        )
    }

    private fun defaultPath(): Path =
        StorageVolumeListLiveData.valueCompat.firstOrNull { it.isPrimaryCompat }
            ?.pathCompat?.let { Paths.get(it) } ?: Paths.get("/")

    override fun onItemClick(item: BookmarkRecentDirectoryAdapter.Item) {
        // Tap on a bookmark/recent folder opens its folder.
        dismiss()
        val navigationFragment = parentFragment as? NavigationFragment
        if (navigationFragment != null) {
            // Open in place (single-pane: the current list; two-pane: the active pane)
            // instead of stacking a new FileListActivity on top of the current one.
            navigationFragment.listener?.navigateTo(item.path)
        } else {
            startActivitySafe(FileListActivity.createViewIntent(item.path))
        }
    }

    override fun onItemLongClick(item: BookmarkRecentDirectoryAdapter.Item) {
        if (currentTab == 0) {
            // Long press on a bookmark edits it.
            item.bookmarkDirectory?.let { editBookmarkDirectory(it) }
        } else {
            // Long press on a recent folder removes it.
            item.recentDirectory?.let { RecentDirectories.remove(it) }
        }
    }

    private fun editBookmarkDirectory(bookmarkDirectory: BookmarkDirectory) {
        startActivitySafe(
            EditBookmarkDirectoryDialogActivity::class.createIntent()
                .putArgs(EditBookmarkDirectoryDialogFragment.Args(bookmarkDirectory))
        )
    }
}
