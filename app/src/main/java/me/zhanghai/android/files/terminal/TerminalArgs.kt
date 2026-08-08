/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.util.ParcelableArgs

@Parcelize
class TerminalArgs(
    val cwd: String,
    val asRoot: Boolean = false
) : ParcelableArgs