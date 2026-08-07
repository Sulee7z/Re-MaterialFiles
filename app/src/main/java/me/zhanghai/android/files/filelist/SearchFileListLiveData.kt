/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.search
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

