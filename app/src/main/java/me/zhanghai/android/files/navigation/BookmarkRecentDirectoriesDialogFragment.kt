/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.themeResIdCompat
import me.zhanghai.android.files.databinding.BookmarkRecentDirectoriesDialogFragmentBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.launchSafe
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

class BookmarkRecentDirectoriesDialogFragment : BottomSheetDialogFragment(),
    BookmarkRecentDirectoryAdapter.Listener {
    private val openPathLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract(), ::onOpenPathResult)

    private lateinit var binding: BookmarkRecentDirectoriesDialogFragmentBinding

    private lateinit var adapter: BookmarkRecentDirectoryAdapter

    private var bookmarkDirectories: List<BookmarkDirectory> = emptyList()

    private var recentDirectories: List<RecentDirectory> = emptyList()

    private var currentTab = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        BookmarkRecentDirectoriesDialogFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Use the activity's current theme (which may be a custom theme such as
        // the pure black variant) for the bottom sheet dialog, so that all of
        // its colors follow the app theme instead of the library default bottom
        // sheet theme overlay.
        return BottomSheetDialog(requireContext(), requireContext().themeResIdCompat)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = getString(R.string.navigation_recent_bookmark_directories)
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

        binding.fab.setOnClickListener { onAddBookmarkDirectory() }

        Settings.BOOKMARK_DIRECTORIES.observe(viewLifecycleOwner) {
            bookmarkDirectories = it
            updateList()
        }
        Settings.RECENT_DIRECTORIES.observe(viewLifecycleOwner) {
            recentDirectories = it
            updateList()
        }
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
        binding.fab.fadeToVisibilityUnsafe(currentTab == 0)
        adapter.replace(items)
    }

    private fun onAddBookmarkDirectory() {
        openPathLauncher.launchSafe(null, this)
    }

    private fun onOpenPathResult(result: Path?) {
        result ?: return
        BookmarkDirectories.add(BookmarkDirectory(null, result))
    }

    override fun onItemClick(item: BookmarkRecentDirectoryAdapter.Item) {
        if (currentTab == 0) {
            item.bookmarkDirectory?.let { editBookmarkDirectory(it) }
        } else {
            dismiss()
            startActivitySafe(FileListActivity.createViewIntent(item.path))
        }
    }

    override fun onItemLongClick(item: BookmarkRecentDirectoryAdapter.Item) {
        if (currentTab == 0) {
            item.bookmarkDirectory?.let { editBookmarkDirectory(it) }
        } else {
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