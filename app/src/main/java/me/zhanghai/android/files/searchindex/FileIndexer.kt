/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.stateCompat
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import android.os.Environment
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Walks the local storage roots and builds the file name index in the background.
 *
 * Deliberately single-threaded: restricted paths (/data, "/") go through the root/Shizuku
 * file service over Binder, which serializes file operations; parallel workers just queue
 * up on it and can hang the index. Speed comes from batched (transactional) SQLite inserts
 * and skipping app cache trees, not from concurrency.
 */
object FileIndexer {

    // Android/data, Android/obb and Android/media are deliberately NOT skipped: restricted
    // paths are routed through the root/Shizuku file service automatically by the provider,
    // so they can be indexed whenever Sui or Shizuku is available.
    // App caches are skipped: they hold tens of thousands of volatile files that nobody
    // searches for, and skipping them massively speeds up the first index build.
    private val SKIP_DIRECTORIES = setOf(
        "AppData", "System Volume Information", "\$RECYCLE.BIN", "LOST.DIR",
        "cache", "code_cache"
    )

    // System sub-directories directly below "/" that we do not descend into when indexing
    // the root filesystem: they hold tens of thousands of binaries/libs that nobody searches
    // for, and indexing them would burn the item budget. /data and /storage are not listed
    // because they are already covered by their own index roots (deduplicated via `visited`).
    // Also used by the fallback tree walk when a "/ keyword" search misses the index.
    val ROOT_SKIP_DIRECTORIES = setOf(
        "acct", "apex", "bin", "bugreports", "cache", "charger", "config", "d",
        "data_mirror", "debug_ramdisk", "dev", "etc", "init", "linkerconfig",
        "lost+found", "metadata", "mnt", "odm", "oem", "postinstall", "preload",
        "proc", "product", "recovery", "root", "sbin", "sys", "system",
        "system_dlkm", "system_ext", "tmp", "vendor"
    )

    private const val MAX_INDEX_ITEMS = 1_000_000L

    /** Rows buffered before a batched (transactional) insert. */
    private const val INSERT_BATCH_SIZE = 200

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

    private const val META_LAST_INDEX_TIME = "last_indexed_time_millis"

    @Volatile
    private var stateRestoredFromDatabase = false

    /**
     * Restores the in-memory index state from the database, so a previously built index
     * survives process death: without this the entry count starts at 0 on every cold
     * start, the search screen mistook that for "not indexed" and forced a full (clearing)
     * rebuild before searching worked again. Idempotent and safe to call repeatedly; the
     * COUNT(*) probe runs on the calling thread, so invoke it off the main thread.
     */
    @Synchronized
    fun restoreStateFromDatabase(context: android.content.Context): Pair<Long, Long> {
        SearchIndexDb.initialize(context.applicationContext)
        if (!stateRestoredFromDatabase) {
            stateRestoredFromDatabase = true
            val count = try {
                SearchIndexDb.count()
            } catch (e: Exception) {
                0L
            }
            if (count > 0) {
                lastIndexedEntryCount = count
                lastIndexedTimeMillis = try {
                    SearchIndexDb.getMetaLong(META_LAST_INDEX_TIME) ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
        }
        return lastIndexedEntryCount to lastIndexedTimeMillis
    }

    /**
     * Computes the directories to index: every mounted storage volume, plus the system data
     * directories and the "/" root so scoped searches like "/data/app chrome" or "/ target"
     * can find files that normally require root/Shizuku to read. The root trees are always
     * requested here (the Sui/Shizuku availability APIs are unreliable early at app start);
     * startIndex() probes them and drops whatever the device cannot actually read.
     */
    fun getIndexRoots(): List<Path> {
        val roots = StorageVolumeListLiveData.value
            ?.filter { it.stateCompat == Environment.MEDIA_MOUNTED }
            ?.mapNotNull { volume ->
                val directory = volume.directoryCompat ?: return@mapNotNull null
                Paths.get(directory.absolutePath)
            }
            ?.toMutableList()
            ?: mutableListOf()
        // The whole root filesystem (so "/target" style searches find files at the root,
        // e.g. /target.txt), the installed app APKs and the main user's app data. The
        // storage volumes come first so they win when MAX_INDEX_ITEMS is reached; the
        // "/" root is appended last and mostly indexes root-level files and whatever
        // sub-trees are accessible, while /data/app and /data/user/0 are covered by the
        // explicit roots above.
        roots.addAll(
            listOf(
                Paths.get("/data/app"),
                Paths.get("/data/user/0"),
                Paths.get("/")
            )
        )
        return roots
    }

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

                // Probe restricted roots: devices without a working root/Shizuku cannot read
                // /data/app, /data/user/0 or the "/" root. Dropping them here keeps the walk
                // fast (no per-directory service timeouts) and the index free of empty trees.
                val dataAccessible = isDirectoryReadable(Paths.get("/data/app"))
                val skipSet = ROOT_SKIP_DIRECTORIES.toMutableSet()
                if (!dataAccessible) {
                    // Without root access /data is not readable; don't even descend into it
                    // when walking "/" (its own roots are dropped below anyway).
                    skipSet += "data"
                }
                for (root in roots) {
                    val rootString = root.toString()
                    if ((rootString == "/" || rootString.startsWith("/data/"))
                        && !isDirectoryReadable(root)) {
                        continue
                    }
                    queue.addLast(root)
                }

                val batch = ArrayList<SearchIndexDb.IndexedFileInsert>(INSERT_BATCH_SIZE)
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
                        // One attribute read per entry: isDirectory comes from the same
                        // attributes instead of a second stat round trip, halving the
                        // file-system traffic (the dominant cost over the root/Shizuku
                        // Binder file service on restricted paths).
                        val attributes = try {
                            Files.readAttributes(
                                entry, java8.nio.file.attribute.BasicFileAttributes::class.java
                            )
                        } catch (e: Exception) {
                            continue
                        }
                        val isDirectory = attributes.isDirectory
                        val fileName = entry.fileName?.toString() ?: continue
                        batch.add(
                            SearchIndexDb.IndexedFileInsert(
                                entry.toString(), fileName,
                                attributes.size(), attributes.lastModifiedTime().toMillis(),
                                isDirectory
                            )
                        )
                        if (batch.size >= INSERT_BATCH_SIZE) {
                            SearchIndexDb.insertBatch(batch)
                            batch.clear()
                        }
                        count++
                        if (count % 500 == 0L) {
                            onProgress(count)
                        }
                        if (isDirectory) {
                            if (fileName in SKIP_DIRECTORIES) {
                                continue
                            }
                            if (current.toString() == "/" && fileName in skipSet) {
                                // At the root only descend into what we actually want indexed
                                // (storage volumes and /data trees are separate roots).
                                continue
                            }
                            queue.addLast(entry)
                        }
                    }
                }
                if (batch.isNotEmpty()) {
                    SearchIndexDb.insertBatch(batch)
                }
                lastIndexedEntryCount = count
                lastIndexedTimeMillis = System.currentTimeMillis()
                // Persist the completion time so it survives process death along with the
                // index rows themselves.
                try {
                    SearchIndexDb.putMetaLong(META_LAST_INDEX_TIME, lastIndexedTimeMillis)
                } catch (e: Exception) {
                    // Non-fatal: only the displayed "indexed at" timestamp is affected.
                }
                onProgress(count)
                onDone(null)
            } catch (e: Throwable) {
                onDone(e)
            } finally {
                indexing = false
            }
        }
    }

    private fun isDirectoryReadable(path: Path): Boolean =
        try {
            Files.newDirectoryStream(path).use { true }
        } catch (e: Exception) {
            false
        }
}
