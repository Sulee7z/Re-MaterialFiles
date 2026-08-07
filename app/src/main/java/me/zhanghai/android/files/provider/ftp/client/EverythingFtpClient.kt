/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream

/**
 * Capability for issuing the Everything SITE EVERYTHING QUERY data connection.
 */
interface EverythingQueryCapable {
    @Throws(IOException::class)
    fun openEverythingQueryStream(): InputStream?
}

class EverythingFTPClient : FTPClient(), EverythingQueryCapable {
    @Throws(IOException::class)
    override fun openEverythingQueryStream(): InputStream? =
        _openDataConnection_("SITE", "EVERYTHING QUERY")?.getInputStream()
}

class EverythingFTPSClient(implicit: Boolean) : FTPSClient(implicit), EverythingQueryCapable {
    @Throws(IOException::class)
    override fun openEverythingQueryStream(): InputStream? =
        _openDataConnection_("SITE", "EVERYTHING QUERY")?.getInputStream()
}

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
