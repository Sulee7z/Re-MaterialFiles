/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

/**
 * Maps an Everything Windows path to this FTP server's remote path using the configured
 * Windows index root. Returns null when the path is outside the root.
 */
fun mapEverythingPathToRemotePath(windowsPath: String, windowsRoot: String): String? {
    if (windowsRoot.isEmpty()) {
        return null
    }
    val normalizedRoot = windowsRoot.trimEnd('\\')
    if (!windowsPath.startsWith(normalizedRoot, ignoreCase = true)) {
        return null
    }
    val relative = windowsPath.removePrefix(normalizedRoot).replace('\\', '/')
    return relative.ifEmpty { "/" }
}

/**
 * Maps this FTP server's remote path back to the Everything Windows path using the
 * configured Windows index root (inverse of [mapEverythingPathToRemotePath]). Returns
 * null when no root is configured.
 */
fun mapRemotePathToEverythingWindowsPath(remotePath: String, windowsRoot: String): String? {
    if (windowsRoot.isEmpty()) {
        return null
    }
    val normalizedRoot = windowsRoot.trimEnd('\\')
    val trimmed = remotePath.trim('/')
    return if (trimmed.isEmpty()) {
        normalizedRoot
    } else {
        "$normalizedRoot\\${trimmed.replace('/', '\\')}"
    }
}
