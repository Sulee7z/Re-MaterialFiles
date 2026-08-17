/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditBookmarkDirectoryDialogBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.launchSafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.setTextWithSelection

class EditBookmarkDirectoryDialogFragment : AppCompatDialogFragment() {
    private val openPathLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract(), ::onOpenPathResult)

    private val args by args<Args>()

    // Null until the user picks a directory when adding a new bookmark.
    private var path: Path? = null

    private lateinit var binding: EditBookmarkDirectoryDialogBinding

    // Set while the fragment is being created so the directory picker opens on
    // the first onResume (when launching an ActivityResult is guaranteed safe).
    private var pendingAutoPickDirectory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingAutoPickDirectory = args.isAdd && savedInstanceState == null
        path = savedInstanceState?.getState<State>()?.path ?: if (args.isAdd) {
            // The folder must be chosen by the user before adding a bookmark.
            null
        } else {
            args.bookmarkDirectory.path
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(
                if (args.isAdd) R.string.file_list_action_add_bookmark
                else R.string.navigation_edit_bookmark_directory_title
            )
            .apply {
                binding = EditBookmarkDirectoryDialogBinding.inflate(context.layoutInflater)
                val bookmarkDirectory = args.bookmarkDirectory
                // When adding a new bookmark, the placeholder is only meaningful after the
                // user picks a directory, so leave it empty until then (set in onOpenPathResult).
                binding.nameLayout.placeholderText = if (args.isAdd) null else bookmarkDirectory.defaultName
                // When adding a new bookmark, leave the name empty so the default name
                // (shown as the placeholder) is used unless the user types one.
                if (savedInstanceState == null && !args.isAdd) {
                    binding.nameEdit.setTextWithSelection(bookmarkDirectory.name)
                }
                updatePathText()
                // ReadOnlyTextInputEditText disables clickability when not selectable;
                // re-enable it so tapping the path lets the user pick a folder.
                binding.pathText.isClickable = true
                binding.pathText.setOnClickListener { onEditPath() }
                setView(binding.root)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> save() }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
            .apply {
                // Only editing an existing bookmark can remove it.
                if (!args.isAdd) {
                    setNeutralButton(R.string.remove) { _, _ -> remove() }
                }
            }
            .create()
            .apply {
                window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            }

    override fun onResume() {
        super.onResume()

        // When adding a new bookmark, immediately ask the user to pick the folder
        // instead of showing an empty path. Doing this in onResume (instead of
        // e.g. right after onCreateDialog) guarantees the fragment is RESUMED, which
        // ActivityResultLauncher.launch() requires. The dialog stays so the user can
        // go back to it after picking (or cancel and pick again by tapping the path).
        if (pendingAutoPickDirectory) {
            pendingAutoPickDirectory = false
            onEditPath()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putState(State(path))
    }

    private fun onEditPath() {
        openPathLauncher.launchSafe(path, this)
    }

    private fun onOpenPathResult(result: Path?) {
        result ?: return
        path = result
        // Now that the folder is chosen, show its name as the name placeholder.
        binding.nameLayout.placeholderText = result.name
        updatePathText()
    }

    private fun updatePathText() {
        binding.pathText.setText(
            path?.toUserFriendlyString()
                ?: getString(R.string.navigation_edit_bookmark_directory_path_hint)
        )
    }

    private fun save() {
        val path = path
        if (path == null) {
            // No folder chosen yet; ask the user to pick one instead of saving.
            onEditPath()
            return
        }
        val customName = binding.nameEdit.text.toString()
            .takeIf { it.isNotEmpty() && it != binding.nameLayout.placeholderText }
        if (args.isAdd) {
            BookmarkDirectories.add(BookmarkDirectory(customName, path))
        } else {
            val bookmarkDirectory = args.bookmarkDirectory.copy(customName = customName, path = path)
            BookmarkDirectories.replace(bookmarkDirectory)
        }
        finish()
    }

    private fun remove() {
        BookmarkDirectories.remove(args.bookmarkDirectory)
        finish()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        finish()
    }

    @Parcelize
    class Args(
        val bookmarkDirectory: BookmarkDirectory,
        val isAdd: Boolean = false
    ) : ParcelableArgs

    @Parcelize
    private class State(var path: @WriteWith<ParcelableParceler> Path?) : ParcelableState
}
