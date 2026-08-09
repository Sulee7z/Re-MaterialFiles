/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.root.SuiFileServiceLauncher
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Walks the local storage roots and builds the file name index in the background.
 */
object FileIndexer {

    // Android/data, Android/obb and Android/media are deliberately NOT skipped: restricted
    // paths are routed through the root/Shizuku file service automatically by the provider,
    // so they can be indexed whenever Sui or Shizuku is available.
    private val SKIP_DIRECTORIES = setOf(
        "AppData", "System Volume Information", "\$RECYCLE.BIN", "LOST.DIR"
    )

    // System sub-directories directly below "/" that we do not descend into when indexing
    // the root filesystem: they hold tens of thousands of binaries/libs that nobody searches
    // for, and indexing them would burn the item budget. /data and /storage are not listed
    // because they are already covered by their own index roots (deduplicated via `visited`).
    private val ROOT_SKIP_DIRECTORIES = setOf(
        "acct", "apex", "bin", "bugreports", "cache", "charger", "config", "d",
        "data_mirror", "debug_ramdisk", "dev", "etc", "init", "linkerconfig",
        "lost+found", "metadata", "mnt", "odm", "oem", "postinstall", "preload",
        "proc", "product", "recovery", "root", "sbin", "sys", "system",
        "system_dlkm", "system_ext", "tmp", "vendor"
    )

    private const val MAX_INDEX_ITEMS = 1_000_000L

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

    /**
     * Computes the directories to index: every mounted storage volume, plus - when Sui
     * (root) or Shizuku is available - the system data directories, so scoped searches like
     * "/data/app chrome" can find files that normally require root to read.
     */
    fun getIndexRoots(): List<Path> {
        val roots = StorageVolumeListLiveData.value
            ?.filter { it.state == "mounted" }
            ?.mapNotNull { volume ->
                val directory = volume.directory ?: return@mapNotNull null
                Paths.get(directory.absolutePath)
            }
            ?.toMutableList()
            ?: mutableListOf()
        if (SuiFileServiceLauncher.isSuiAvailable() || SuiFileServiceLauncher.isShizukuAvailable()) {
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
        }
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
                            if (current.toString() == "/" && name in ROOT_SKIP_DIRECTORIES) {
                                // At the root only descend into what we actually want indexed
                                // (storage volumes and /data trees are separate roots).
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
