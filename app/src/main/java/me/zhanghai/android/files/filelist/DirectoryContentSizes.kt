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
import me.zhanghai.android.files.provider.common.readAttributes
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
    // Limited concurrency: parallel walks over the same flash-backed storage only
    // contend (each stat is a FUSE round-trip), so fewer, normal-priority threads
    // finish visible folders sooner.
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "DirectoryContentSizes")
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private class Entry(val lastModifiedMillis: Long, val sizeBytes: Long)

    private val cache = HashMap<String, Entry>()
    private val pending = HashSet<String>()

    /**
     * Returns the cached content size in bytes for the directory, or null when it has
     * not been computed yet (or the directory changed since it was).
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
            // Iterative implementation (Amaze-style): list directories recursively with
            // an explicit stack, reading each entry's attributes.
            var size = 0L
            var visited = 0
            val stack = ArrayDeque<Path>()
            stack.addFirst(directory)
            while (stack.isNotEmpty()) {
                var stream: java8.nio.file.DirectoryStream<Path>
                try {
                    val current = stack.removeFirst()
                    stream = java8.nio.file.Files.newDirectoryStream(current)
                } catch (exception: Exception) {
                    continue
                }
                stream.use { entries ->
                    val iterator = entries.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next()
                        if (++visited > MAX_ENTRIES) {
                            return UNKNOWN
                        }
                        try {
                            // Same attribute read as loadFileItem() (NOFOLLOW_LINKS): the
                            // custom java8.nio provider reports correct dir/file/size only
                            // through this path.
                            val attrs = path.readAttributes(
                                java8.nio.file.attribute.BasicFileAttributes::class.java,
                                java8.nio.file.LinkOption.NOFOLLOW_LINKS
                            )
                            if (attrs.isDirectory) {
                                stack.addFirst(path)
                            } else if (attrs.isRegularFile) {
                                size += attrs.size()
                            }
                        } catch (exception: Exception) {
                            // Unreadable entry: skip it, keep the rest.
                        }
                    }
                }
            }
            size
        } catch (exception: Exception) {
            UNKNOWN
        }

    /** Sentinels stored for directories whose size could not be computed (too large,
     *  unreadable, …); callers show date-only rather than a misleading "0 B". */
    const val UNKNOWN = Long.MIN_VALUE
    private const val MAX_ENTRIES = 300000
    private const val MAX_CACHE_ENTRIES = 1000

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
