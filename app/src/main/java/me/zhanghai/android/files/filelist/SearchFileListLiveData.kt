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
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.searchindex.FileIndexer
import me.zhanghai.android.files.searchindex.SearchIndexDb
import me.zhanghai.android.files.searchindex.SearchQuery
import me.zhanghai.android.files.searchindex.SearchQueryParser
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
    query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var searchThread: Thread? = null

    // The query is parsed Everything-style: a leading "/folder" scopes the search to that
    // directory (and its descendants), while space-separated terms AND together, "|" ORs
    // alternatives, "!" excludes, "..." phrases match verbatim, and file:/folder:/doc:/pic:/
    // video:/zip:/size:/dm: filters narrow the results.
    private val scopedPath: Path
    private val searchQuery: SearchQuery

    /** Whether the user explicitly scoped the query with a "/" prefix (e.g. "/ target" or
     *  "/data/app apk"). Explicit scopes fall back to a full tree walk on empty index hits
     *  so newly created files (not yet in the index) are still found. */
    private val isExplicitPathScope: Boolean

    init {
        val parsed = SearchQueryParser.parse(query)
        isExplicitPathScope = parsed.pathPrefix != null
        scopedPath = parsed.pathPrefix?.let { pathText ->
            try {
                Paths.get(pathText)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: path
        searchQuery = parsed.copy(pathPrefix = null)
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
                // walk when the index is missing or the directory has never been indexed.
                val indexedPaths = queryIndex(scopedPath, searchQuery)
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
                    // Search the scope with a tree walk. When the scope is the "/" root, skip
                    // the huge system sub-trees (they are either not indexed or irrelevant) so
                    // the fallback returns quickly; /data and /storage are still descended.
                    val walk = { listener: (List<Path>) -> Unit ->
                        if (scopedPath.toString() == "/") {
                            WalkFileTreeSearchable.search(
                                directory = scopedPath,
                                query = searchQuery.simpleKeywords,
                                intervalMillis = INTERVAL_MILLIS,
                                listener = { paths -> listener(paths) },
                                skipDirectories = FileIndexer.ROOT_SKIP_DIRECTORIES
                            )
                        } else {
                            scopedPath.search(searchQuery.simpleKeywords, INTERVAL_MILLIS) { paths ->
                                listener(paths)
                            }
                        }
                    }
                    walk { paths ->
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
     * Returns null only when the index cannot serve this directory (non-local path, no index
     * at all, or the directory has never been indexed), in which case the caller falls back
     * to the recursive tree walk. When the index covers the directory the result is trusted
     * even if empty: an empty list means no matches, keeping searches instant.
     */
    private fun queryIndex(path: Path, query: SearchQuery): List<Path>? {
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
        val pathString = path.toString()
        val covered = try {
            SearchIndexDb.hasEntriesUnder(pathString)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        if (!covered) {
            return null
        }
        val results = try {
            SearchIndexDb.search(query.copy(pathPrefix = pathString))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return null
        // An explicit "/path" or "/ keyword" scope must find files even if they were created
        // after the index was built, so an empty index hit falls back to the tree walk. Plain
        // (non-scoped) searches trust the index for speed.
        if (results.isEmpty() && isExplicitPathScope) {
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
        // Guess the MIME type from the extension (cheap, no file header reads) instead of
        // using GENERIC, so the built-in openers (image viewer/editor/analyzers) work on
        // search results exactly like they do on the file list.
        val mimeType = try {
            AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
        } catch (e: Exception) {
            e.printStackTrace()
            MimeType.GENERIC
        }
        return FileItem(
            this, nameCollationKey, attributes, null, null, isHidden, mimeType
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

