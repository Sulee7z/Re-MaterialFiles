/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.removeFirst
import me.zhanghai.android.files.util.valueCompat

object RecentDirectories {
    private const val MAX_RECENT_DIRECTORIES = 20

    fun add(path: Path) {
        val recentDirectories = Settings.RECENT_DIRECTORIES.valueCompat.toMutableList()
            .apply {
                removeFirst { it.path == path }
                add(0, RecentDirectory(path))
                if (size > MAX_RECENT_DIRECTORIES) {
                    subList(MAX_RECENT_DIRECTORIES, size).clear()
                }
            }
        Settings.RECENT_DIRECTORIES.putValue(recentDirectories)
    }

    fun remove(recentDirectory: RecentDirectory) {
        val recentDirectories = Settings.RECENT_DIRECTORIES.valueCompat.toMutableList()
            .apply { removeFirst { it == recentDirectory } }
        Settings.RECENT_DIRECTORIES.putValue(recentDirectories)
    }
}