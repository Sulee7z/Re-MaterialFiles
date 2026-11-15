/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.app.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java8.nio.file.Path
import java8.nio.file.Paths
import java.io.IOException

/**
 * Persists the list of trashed files so they survive app restarts and can be
 * browsed/restored from the "Trash" screen. Each entry records the original path,
 * the trash (renamed) path, and the deletion timestamp.
 */
object TrashManager {
    // File mutations use a separate lock so reading the index from the UI never waits
    // for a recursive deletion. All trash jobs share this lock within the app process.
    internal val operationLock = Any()

    data class TrashEntry(
        val originalPath: String,
        val trashPath: String,
        val deletedAtMillis: Long
    ) {
        val originalFileName: String
            get() = originalPath.substringAfterLast('/')

        val originalParentPath: String
            get() = originalPath.substringBeforeLast('/')

        fun toOriginalPath(): Path = Paths.get(originalPath)

        fun toTrashPath(): Path = Paths.get(trashPath)
    }

    private const val KEY_TRASH_LIST = "trash_list_v1"

    private val prefs: SharedPreferences
        get() = defaultSharedPreferences

    // The persisted index is the source of truth, so the LiveData is seeded from it
    // instead of an empty list. Otherwise the Trash screen shows an empty list after a
    // process restart, until the first mutation republishes the whole list.
    private val _trashLiveData = MutableLiveData<List<TrashEntry>>(readForDisplay())
    val trashLiveData: LiveData<List<TrashEntry>> = _trashLiveData

    /**
     * Re-reads the persisted trash index and republishes it, so that observers always
     * see the entries that are currently stored.
     */
    @Synchronized
    fun refresh() {
        _trashLiveData.postValue(readForDisplay())
    }

    @Synchronized
    fun add(originalPath: Path, trashPath: Path) {
        val entries = getAllInternal().toMutableList()
        entries.add(
            TrashEntry(
                originalPath = originalPath.toString(),
                trashPath = trashPath.toString(),
                deletedAtMillis = System.currentTimeMillis()
            )
        )
        save(entries)
    }

    @Synchronized
    fun remove(trashPath: Path) {
        val trashPathString = trashPath.toString()
        save(getAllInternal().filter { it.trashPath != trashPathString })
    }

    @Synchronized
    fun getAll(): List<TrashEntry> = readForDisplay()

    @Synchronized
    internal fun getAllForOperation(): List<TrashEntry> = getAllInternal()

    @Synchronized
    internal fun contains(trashPath: Path, originalPath: Path? = null): Boolean =
        getAllInternal().any {
            it.trashPath == trashPath.toString() &&
                (originalPath == null || it.originalPath == originalPath.toString())
        }

    /**
     * Returns entries whose [TrashEntry.deletedAtMillis] is older than [days] days.
     * A [days] of 0 means "never" and returns nothing.
     */
    @Synchronized
    fun findExpired(days: Int): List<TrashEntry> {
        if (days <= 0) {
            return emptyList()
        }
        val cutoff = System.currentTimeMillis() - days * DAY_MILLIS
        return getAllInternal().filter { it.deletedAtMillis < cutoff }
    }

    /** Removes the given entries (by trash path) from the persisted trash record. */
    @Synchronized
    fun removeEntries(entries: List<TrashEntry>) {
        if (entries.isEmpty()) {
            return
        }
        val removedPaths = entries.map { it.trashPath }.toSet()
        save(getAllInternal().filter { it.trashPath !in removedPaths })
    }

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    private fun readForDisplay(): List<TrashEntry> = try {
        getAllInternal(strict = false)
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    private fun getAllInternal(strict: Boolean = true): List<TrashEntry> {
        val encoded = try {
            prefs.getString(KEY_TRASH_LIST, null)
        } catch (e: Exception) {
            throw IOException("Cannot read trash index", e)
        } ?: return emptyList()
        val array = try {
            JSONArray(encoded)
        } catch (e: Exception) {
            throw IOException("Cannot parse trash index", e)
        }
        return buildList {
            for (index in 0..<array.length()) {
                try {
                    val json = array.getJSONObject(index)
                    add(
                        TrashEntry(
                            originalPath = json.getString("originalPath"),
                            trashPath = json.getString("trashPath"),
                            deletedAtMillis = json.getLong("deletedAtMillis")
                        )
                    )
                } catch (e: Exception) {
                    if (strict) {
                        // A mutation must not silently overwrite records it cannot parse.
                        throw IOException("Cannot parse trash entry $index", e)
                    }
                    // Skip a malformed entry instead of dropping the whole index: the
                    // remaining entries are still valid and must survive.
                    e.printStackTrace()
                }
            }
        }
    }

    private fun save(entries: List<TrashEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject().apply {
                    put("originalPath", entry.originalPath)
                    put("trashPath", entry.trashPath)
                    put("deletedAtMillis", entry.deletedAtMillis)
                }
            )
        }
        if (!prefs.edit().putString(KEY_TRASH_LIST, array.toString()).commit()) {
            throw IOException("Cannot persist trash index")
        }
        _trashLiveData.postValue(entries)
    }
}
