/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.defaultSharedPreferences

/**
 * Direct SharedPreferences-backed storage for the hidden items list, avoiding the
 * SettingLiveData listener cache entirely.
 */
object HiddenPaths {

    private const val TAG = "SoraEditor"

    private const val KEY = "key_file_list_hidden_paths"

    fun getAll(): Set<String> {
        val result = defaultSharedPreferences.getStringSet(KEY, emptySet())!!
        Log.i(TAG, "HiddenPaths.getAll() -> ${result.size} items: $result")
        return result
    }

    fun add(path: String) {
        update { it + path }
    }

    fun set(paths: Set<String>) {
        Log.i(TAG, "HiddenPaths.set($paths)")
        update { paths }
    }

    private fun update(transform: (Set<String>) -> Set<String>) {
        val current = getAll()
        val next = transform(current)
        defaultSharedPreferences.edit {
            putStringSet(KEY, next)
        }
        Log.i(TAG, "HiddenPaths.write: ${current.size} -> ${next.size}")
    }
}

