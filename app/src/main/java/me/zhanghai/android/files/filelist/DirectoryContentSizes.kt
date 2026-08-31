/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Computes and caches the total content size of a directory (the sum of the sizes of
 * everything inside it, recursively), so the file list can show a meaningful size for
 * folders instead of the meaningless directory entry size.
 *
 * The walk runs on a single background thread (so scrolling through many folders cannot
 * hammer the file system or the UI thread) and results are cached keyed by path and the
 * directory's last modification time, so an unchanged directory is never walked twice.
 */
object DirectoryContentSizes {
    // Multiple threads so one slow directory (e.g. Android/data with tens of thousands
    // of entries) cannot stall the queue and starve every other folder's computation.
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "DirectoryContentSizes").apply { priority = Thread.MIN_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private class Entry(val lastModifiedMillis: Long, val sizeBytes: Long)

    private val cache = HashMap<String, Entry>()
    private val pending = HashSet<String>()

    /**
     * Returns the cached content size in bytes for the directory, or null when it has
     * not been computed yet (or the directory changed since it was). A negative value
     * means a previous computation failed; callers should fall back to the entry size.
     */
    @Synchronized
    fun get(path: Path, attributes: BasicFileAttributes): Long? {
        val entry = cache[path.toString()] ?: return null
        val lastModifiedMillis = attributes.lastModifiedTime().toInstant().toEpochMilli()
        return if (entry.lastModifiedMillis == lastModifiedMillis) {
            entry.sizeBytes
        } else {
            null
        }
    }

    /**
     * Requests an asynchronous computation of the directory's content size. When it
     * finishes (successfully or not), [onUpdate] is invoked on the main thread exactly
     * once so the caller can rebind the affected row.
     */
    @Synchronized
    fun request(path: Path, attributes: BasicFileAttributes, onUpdate: () -> Unit) {
        val key = path.toString()
        if (key in pending) {
            return
        }
        val lastModifiedMillis = attributes.lastModifiedTime().toInstant().toEpochMilli()
        cache[key]?.let { entry ->
            if (entry.lastModifiedMillis == lastModifiedMillis) {
                // Already computed for this state of the directory.
                return
            }
        }
        if (cache.size > MAX_CACHE_ENTRIES) {
            cache.clear()
        }
        pending.add(key)
        executor.execute {
            val sizeBytes = computeSize(path)
            synchronized(this@DirectoryContentSizes) {
                pending.remove(key)
                cache[key] = Entry(lastModifiedMillis, sizeBytes)
            }
            mainHandler.post(onUpdate)
        }
    }

    private fun computeSize(directory: Path): Long =
        try {
            var size = 0L
            Files.walkFileTree(directory, object : FileVisitor<Path> {
                override fun preVisitDirectory(
                    dir: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult = FileVisitResult.CONTINUE

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    size += attributes.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exception: IOException
                ): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(
                    dir: Path,
                    exception: IOException?
                ): FileVisitResult = FileVisitResult.CONTINUE
            })
            size
        } catch (exception: IOException) {
            -1L
        } catch (exception: SecurityException) {
            -1L
        }

    private const val MAX_CACHE_ENTRIES = 1000
}
