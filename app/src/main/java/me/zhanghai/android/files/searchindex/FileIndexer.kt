/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import me.zhanghai.android.files.provider.common.isDirectory
import java8.nio.file.DirectoryStream
import java8.nio.file.Files
import java8.nio.file.Path
import kotlin.concurrent.thread

/**
 * Walks the local storage roots and builds the file name index in the background.
 */
object FileIndexer {

    private val SKIP_DIRECTORIES = arrayOf(
        "Android", "AppData", "System Volume Information", "\$RECYCLE.BIN", "LOST.DIR"
    )

    @Volatile
    private var indexing = false

    val isIndexing: Boolean
        get() = indexing

    @Volatile
    var lastIndexedEntryCount: Long = 0
        private set

    @Volatile
    var lastIndexedTimeMillis: Long = 0
        private set

    fun isIndexed(): Boolean = lastIndexedEntryCount > 0

    fun startIndex(roots: List<Path>, onProgress: (Long) -> Unit, onDone: (Throwable?) -> Unit) {
        if (indexing) {
            onDone(IllegalStateException("Already indexing"))
            return
        }
        indexing = true
        thread(name = "FileIndexer") {
            try {
                SearchIndexDb.clear()
                var count = 0L
                for (root in roots) {
                    count = walk(root, count) { current ->
                        count = current
                        if (count % 500 == 0L) {
                            onProgress(count)
                        }
                    }
                }
                lastIndexedEntryCount = count
                lastIndexedTimeMillis = System.currentTimeMillis()
                onProgress(count)
                onDone(null)
            } catch (e: Throwable) {
                onDone(e)
            } finally {
                indexing = false
            }
        }
    }

    private fun walk(root: Path, startCount: Long, onCount: (Long) -> Unit): Long {
        var count = startCount
        try {
            Files.newDirectoryStream(root).use { stream ->
                for (path in stream) {
                    count = indexEntry(path, count, onCount)
                }
            }
        } catch (e: Exception) {
            // Unreadable directories are skipped.
        }
        return count
    }

    private fun indexEntry(path: Path, startCount: Long, onCount: (Long) -> Unit): Long {
        var count = startCount
        val isDirectory = try {
            path.isDirectory()
        } catch (e: Exception) {
            return count
        }
        val attributes = try {
            Files.readAttributes(path, java8.nio.file.attribute.BasicFileAttributes::class.java)
        } catch (e: Exception) {
            return count
        }
        SearchIndexDb.insert(
            path.toString(), path.fileName.toString(),
            attributes.size(), attributes.lastModifiedTime().toMillis(), isDirectory
        )
        count++
        onCount(count)
        if (isDirectory) {
            val name = path.fileName?.toString() ?: return count
            if (name in SKIP_DIRECTORIES) {
                return count
            }
            try {
                Files.newDirectoryStream(path).use { stream ->
                    for (child in stream) {
                        count = indexEntry(child, count, onCount)
                    }
                }
            } catch (e: Exception) {
                // Unreadable directories are skipped.
            }
        }
        return count
    }
}
