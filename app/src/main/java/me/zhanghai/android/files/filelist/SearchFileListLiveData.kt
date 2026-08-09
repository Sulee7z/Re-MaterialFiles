/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.searchindex.SearchIndexDb
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.text.Collator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class SearchFileListLiveData(
    private val path: Path,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var searchThread: Thread? = null

    init {
        loadValue()
    }

    fun loadValue() {
        // Interrupt the previous search instead of queueing in the shared AsyncTask pool,
        // which used to fill up with uninterruptible searches and starve new ones.
        searchThread?.interrupt()
        value = Loading(emptyList())
        searchThread = thread(name = "Search") {
            val fileList = mutableListOf<FileItem>()
            try {
                // High-speed indexed search (quick-search style): on a local Linux file system
                // with the SQLite file name index available, query the index instead of walking
                // the whole directory tree, so results appear instantly. Falls back to the tree
                // walk when the index is missing or yields nothing (the directory may simply not
                // have been indexed yet, or there really are no matches).
                val indexedPaths = queryIndex(path, query)
                if (indexedPaths != null) {
                    if (Thread.interrupted()) {
                        throw InterruptedIOException()
                    }
                    val fileItems = loadFileItems(indexedPaths)
                    if (Thread.interrupted()) {
                        throw InterruptedIOException()
                    }
                    fileList += fileItems
                    postValue(Success(fileList))
                } else {
                    path.search(query, INTERVAL_MILLIS) { paths: List<Path> ->
                        if (Thread.interrupted()) {
                            throw InterruptedIOException()
                        }
                        val fileItems = loadFileItems(paths)
                        if (Thread.interrupted()) {
                            throw InterruptedIOException()
                        }
                        fileList += fileItems
                        postValue(Loading(fileList.toList()))
                    }
                    postValue(Success(fileList))
                }
            } catch (e: InterruptedException) {
                // A newer search replaced this one.
            } catch (e: InterruptedIOException) {
                // A newer search replaced this one.
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    /**
     * Queries the SQLite file name index for [query] scoped to [path] and its descendants.
     * Returns null when the index cannot be used (non-local path, no index, or no hits), in
     * which case the caller falls back to the recursive tree walk.
     */
    private fun queryIndex(path: Path, query: String): List<Path>? {
        if (!path.isLinuxPath) {
            return null
        }
        val count = try {
            SearchIndexDb.count()
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
        if (count <= 0) {
            return null
        }
        val results = try {
            SearchIndexDb.search(query, pathPrefix = path.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return null
        if (results.isEmpty()) {
            return null
        }
        return results.mapNotNull { result ->
            try {
                Paths.get(result.path)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Loads file items for a batch in parallel (each item may need a network round trip on
     * remote file systems, e.g. FTP). Search results skip the expensive MIME detection
     * (which reads the file header) and use a generic type instead.
     */
    private fun loadFileItems(paths: List<Path>): List<FileItem> {
        val executor = FILE_ITEM_LOADER_EXECUTOR
        val items = ConcurrentLinkedQueue<FileItem>()
        val latch = CountDownLatch(paths.size)
        for (path in paths) {
            executor.execute {
                try {
                    items.add(path.loadFileItemLight())
                } catch (e: IOException) {
                    e.printStackTrace()
                    // TODO: Support file without information.
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return items.toList()
    }

    private fun Path.loadFileItemLight(): FileItem {
        val nameCollationKey = Collator.getInstance().getCollationKeyForFileName(name)
        val attributes = readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val isHidden = isHidden
        return FileItem(
            this, nameCollationKey, attributes, null, null, isHidden, MimeType.GENERIC
        )
    }

    override fun close() {
        searchThread?.interrupt()
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L

        private val FILE_ITEM_LOADER_EXECUTOR = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(4).coerceAtMost(8)
        ) { runnable -> Thread(runnable, "SearchItemLoader") }
    }
}

private class InterruptedIOException : java.io.IOException("Search interrupted")

