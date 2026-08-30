/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.app.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java8.nio.file.Path
import java8.nio.file.Paths

/**
 * Persists the list of trashed files so they survive app restarts and can be
 * browsed/restored from the "Trash" screen. Each entry records the original path,
 * the trash (renamed) path, and the deletion timestamp.
 */
object TrashManager {

    data class TrashEntry(
        val originalPath: String,
        val trashPath: String,
        val deletedAtMillis: Long
    ) {
        private fun displayPath(uriString: String): String {
            return try {
                java.net.URI(uriString).path ?: uriString
            } catch (e: Exception) {
                uriString
            }
        }

        val originalFileName: String
            get() = displayPath(originalPath).substringAfterLast('/')

        val originalParentPath: String
            get() = displayPath(originalPath).substringBeforeLast('/')

        fun toOriginalPath(): Path = Paths.get(originalPath)

        fun toTrashPath(): Path = Paths.get(trashPath)
    }

    private const val KEY_TRASH_LIST = "trash_list_v1"

    private val prefs: SharedPreferences
        get() = defaultSharedPreferences

    private val _trashLiveData = MutableLiveData<List<TrashEntry>>(emptyList())
    val trashLiveData: LiveData<List<TrashEntry>> = _trashLiveData

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

    fun remove(trashPath: Path) {
        val trashPathString = trashPath.toString()
        save(getAllInternal().filter { it.trashPath != trashPathString })
    }

    fun getAll(): List<TrashEntry> = getAllInternal()

    fun clear() {
        save(emptyList())
    }

    private fun getAllInternal(): List<TrashEntry> {
        val encoded = prefs.getString(KEY_TRASH_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0..<array.length()) {
                    val json = array.getJSONObject(index)
                    add(
                        TrashEntry(
                            originalPath = json.getString("originalPath"),
                            trashPath = json.getString("trashPath"),
                            deletedAtMillis = json.getLong("deletedAtMillis")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
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
        prefs.edit { putString(KEY_TRASH_LIST, array.toString()) }
        _trashLiveData.postValue(entries)
    }
}