/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.ftp.isFtpPath

private val archiveCacheLock = Any()

private const val CACHE_DIRECTORY_NAME = "archives"

/**
 * FTP random access reconnects a whole session per seek (see FileByteChannel), so parsing an
 * archive directly over FTP means libarchive's per-entry seeking opens hundreds of sessions
 * and the archive never finishes loading. Download the archive to the local cache once and
 * parse that local copy instead.
 *
 * Only FTP is affected (it is the provider without server-side random access); other remote
 * providers keep their direct channels. The cache is keyed by the file's URI plus its
 * modification time and size, so a changed remote archive is re-downloaded automatically.
 */
@Throws(IOException::class)
fun Path.cacheIfFtpArchive(): Path {
    if (!isFtpPath) {
        return this
    }
    // Use the same backport attribute call as ForeignCopyMove, following links as the copy does.
    // A stat/download failure must reach the caller, not fall back to random access over FTP.
    val attributes = readAttributes(BasicFileAttributes::class.java)
    val cacheDirectory = File(application.cacheDir, CACHE_DIRECTORY_NAME)
    val cacheKey = cacheKey(this)
    val cacheName =
        "$cacheKey-${attributes.lastModifiedTime().toMillis()}-${attributes.size()}.cache"
    val cacheFile = File(cacheDirectory, cacheName)
    synchronized(archiveCacheLock) {
        if (cacheFile.exists()) {
            cacheFile.checkDownload(attributes.size())
            return Paths.get(cacheFile.path)
        }
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("Cannot create archive cache directory: $cacheDirectory")
        }
        val tempFile = File.createTempFile("archive-", ".tmp", cacheDirectory)
        try {
            // Keep remote reads provider-aware, but write/publish locally so the cache stays
            // app-owned even when the file provider's root strategy is ALWAYS.
            newInputStream().use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            tempFile.checkDownload(attributes.size())
            if (!tempFile.renameTo(cacheFile)) {
                throw IOException("Cannot publish archive cache file: $tempFile -> $cacheFile")
            }
            cacheFile.checkDownload(attributes.size())
        } catch (t: Throwable) {
            try {
                if (!tempFile.delete() && tempFile.exists()) {
                    throw IOException("Cannot delete archive cache temporary file: $tempFile")
                }
            } catch (e: Exception) {
                t.addSuppressed(e)
            }
            throw t
        }
        // Do not prune on open: returned paths and older versions can still be used by other
        // readers, including in another process. Leave eviction to app-cache management, which
        // also avoids pruning active temporary downloads. Only our failed download is removed.
    }
    return Paths.get(cacheFile.path)
}

@Throws(IOException::class)
private fun File.checkDownload(expectedSize: Long) {
    if (!isFile || length() != expectedSize) {
        throw IOException(
            "Missing or incomplete archive cache file: $this (expected $expectedSize bytes)"
        )
    }
}

private fun cacheKey(path: Path): String =
    MessageDigest.getInstance("SHA-1")
        .digest(path.toUri().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
