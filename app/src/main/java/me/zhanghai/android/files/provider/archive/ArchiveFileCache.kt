/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.ftp.isFtpPath

private val archiveCacheLock = Any()

private const val CACHE_DIRECTORY_NAME = "archives"

private const val CACHE_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

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
    val attributes = try {
        Files.readAttributes(this, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: Exception) {
        // Cannot stat the archive (e.g. transient network error): let the direct path try.
        return this
    }
    val cacheDirectory = File(application.cacheDir, CACHE_DIRECTORY_NAME)
    val cacheKey = cacheKey(this)
    val cacheName =
        "$cacheKey-${attributes.lastModifiedTime().toMillis()}-${attributes.size()}.cache"
    val cacheFile = File(cacheDirectory, cacheName)
    synchronized(archiveCacheLock) {
        if (cacheFile.exists()) {
            return Paths.get(cacheFile.path)
        }
        cacheDirectory.mkdirs()
        val tempFile = File.createTempFile("archive-", ".tmp", cacheDirectory)
        val tempPath = Paths.get(tempFile.path)
        try {
            // createTempFile() already created the empty file; replace it with the copy.
            copyTo(tempPath, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            try {
                Files.deleteIfExists(tempPath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            throw t
        }
        tempPath.moveTo(Paths.get(cacheFile.path), StandardCopyOption.REPLACE_EXISTING)
        // Drop older versions of this archive and best-effort prune long-unused entries.
        val cachePrefix = "$cacheKey-"
        val maxAgeCutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS
        cacheDirectory.listFiles { file ->
            (file.name.startsWith(cachePrefix) && file.name != cacheName) ||
                file.lastModified() < maxAgeCutoff
        }?.forEach { it.delete() }
    }
    return Paths.get(cacheFile.path)
}

private fun cacheKey(path: Path): String =
    MessageDigest.getInstance("SHA-1")
        .digest(path.toUri().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
