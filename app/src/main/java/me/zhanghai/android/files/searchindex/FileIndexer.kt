/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import me.zhanghai.android.files.provider.common.isDirectory
import java8.nio.file.Files
import java8.nio.file.Path
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Walks the local storage roots and builds the file name index in the background.
 */
object FileIndexer {

    private val SKIP_DIRECTORIES = setOf(
        "Android", "AppData", "System Volume Information", "\$RECYCLE.BIN", "LOST.DIR"
    )

    private const val MAX_INDEX_ITEMS = 500_000L

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
                val visited = HashSet<String>()
                val queue = ArrayDeque<Path>()
                for (root in roots) {
                    queue.addLast(root)
                }
                while (queue.isNotEmpty() && count < MAX_INDEX_ITEMS) {
                    val current = queue.removeFirst()
                    if (current.toString() in visited) {
                        continue
                    }
                    visited += current.toString()
                    val entries = try {
                        Files.newDirectoryStream(current).use { it.toList() }
                    } catch (e: Exception) {
                        continue
                    }
                    for (entry in entries) {
                        if (count >= MAX_INDEX_ITEMS) {
                            break
                        }
                        if (entry.toString() in visited) {
                            continue
                        }
                        val isDirectory = try {
                            entry.isDirectory()
                        } catch (e: Exception) {
                            continue
                        }
                        val attributes = try {
                            Files.readAttributes(
                                entry, java8.nio.file.attribute.BasicFileAttributes::class.java
                            )
                        } catch (e: Exception) {
                            continue
                        }
                        SearchIndexDb.insert(
                            entry.toString(), entry.fileName.toString(),
                            attributes.size(), attributes.lastModifiedTime().toMillis(), isDirectory
                        )
                        count++
                        if (count % 500 == 0L) {
                            onProgress(count)
                        }
                        if (isDirectory) {
                            val name = entry.fileName?.toString() ?: continue
                            if (name in SKIP_DIRECTORIES) {
                                continue
                            }
                            queue.addLast(entry)
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
}
