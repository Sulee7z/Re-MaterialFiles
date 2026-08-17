/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.pathCompat
import me.zhanghai.android.files.compat.themeResIdCompat
import me.zhanghai.android.files.databinding.BookmarkRecentDirectoriesDialogFragmentBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

class BookmarkRecentDirectoriesDialogFragment : DialogFragment(),
    BookmarkRecentDirectoryAdapter.Listener {
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
        // the pure black variant) for the centered dialog, so that all of its
        // colors follow the app theme instead of the library default dialog
        // theme overlay.
        return Dialog(requireContext(), requireContext().themeResIdCompat)
    }

    override fun onStart() {
        super.onStart()

        val dialog = dialog ?: return
        // Don't dim the window behind the dialog, so the main UI shows through.
        dialog.window?.setDimAmount(0f)
        // The activity theme used for this dialog (themeResIdCompat) has an opaque
        // android:windowBackground (e.g. @android:color/black for the pure black theme),
        // which would otherwise completely cover the main UI even with dim set to zero.
        // Make the window background transparent so the main UI shows through around the
        // dialog, and the user can tap it to dismiss the dialog.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Center the dialog on screen, sized to the content. The content root has its own
        // theme-colored background, so only the area around it stays transparent. Use a
        // near-full width so the dialog fills the middle of the screen without awkward
        // gaps on either side; a sliver stays outside for tap-to-dismiss.
        val widthFraction = if (resources.configuration.orientation
                == Configuration.ORIENTATION_LANDSCAPE
        ) 0.96f else 0.98f
        val width = (resources.displayMetrics.widthPixels * widthFraction).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        // Tapping the transparent area outside the dialog content dismisses it.
        dialog.setCanceledOnTouchOutside(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
        startActivitySafe(FileListActivity.createViewIntent(item.path))
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