/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class ConfirmDeleteFilesDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val files = args.files
        val message = if (files.size == 1) {
            val file = files.single()
            val messageRes = if (file.attributesNoFollowLinks.isDirectory) {
                R.string.file_delete_message_directory_format
            } else {
                R.string.file_delete_message_file_format
            }
            getString(messageRes, file.name)
        } else {
            val allDirectories = files.all { it.attributesNoFollowLinks.isDirectory }
            val allFiles = files.none { it.attributesNoFollowLinks.isDirectory }
            val messageRes = when {
                allDirectories -> R.plurals.file_delete_message_multiple_directories_format
                allFiles -> R.plurals.file_delete_message_multiple_files_format
                else -> R.plurals.file_delete_message_multiple_mixed_format
            }
            getQuantityString(messageRes, files.size, files.size)
        }
        // Compute the total size including directories (recursively), so the user knows
        // how much space will be freed. Shown as soon as it is ready.
        val dialog = MaterialAlertDialogBuilder(requireContext(), theme)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> listener.deleteFiles(files) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        lifecycleScope.launch {
            val sizeText = withContext(Dispatchers.IO) { calculateTotalSize(files) }
                ?.asFileSize()?.formatHumanReadable(requireContext())
            if (sizeText != null && dialog.isShowing) {
                dialog.setMessage(
                    message + "\n\n" + getString(R.string.file_delete_message_size_format, sizeText)
                )
            }
        }
        return dialog
    }

    /** Recursively sums the sizes of all files under [files]; null when nothing is readable. */
    private fun calculateTotalSize(files: FileItemSet): Long? {
        var totalSize = 0L
        var anySizeRead = false
        for (file in files) {
            if (file.attributesNoFollowLinks.isDirectory) {
                val directorySize = calculateDirectorySize(file.path)
                if (directorySize != null) {
                    totalSize += directorySize
                    anySizeRead = true
                }
            } else {
                totalSize += file.attributes.size()
                anySizeRead = true
            }
        }
        return if (anySizeRead) totalSize else null
    }

    private fun calculateDirectorySize(directory: Path): Long? {
        var size = 0L
        var anyFileRead = false
        try {
            Files.walkFileTree(directory, object : FileVisitor<Path> {
                override fun preVisitDirectory(
                    dir: Path, attributes: BasicFileAttributes
                ): FileVisitResult = FileVisitResult.CONTINUE

                override fun visitFile(
                    file: Path, attributes: BasicFileAttributes
                ): FileVisitResult {
                    size += attributes.size()
                    anyFileRead = true
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path, exception: IOException
                ): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(
                    dir: Path, exception: IOException?
                ): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: Exception) {
            return null
        }
        return if (anyFileRead) size else null
    }

    companion object {
        fun show(files: FileItemSet, fragment: Fragment) {
            ConfirmDeleteFilesDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    interface Listener {
        fun deleteFiles(files: FileItemSet)
    }
}
