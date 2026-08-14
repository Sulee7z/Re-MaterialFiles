/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.util.ParcelableParceler

@Parcelize
data class RecentDirectory(
    val path: @WriteWith<ParcelableParceler> Path
) : Parcelable {
    val name: String
        get() = path.name
}