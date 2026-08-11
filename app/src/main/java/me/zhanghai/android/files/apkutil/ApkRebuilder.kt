/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkutil

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import me.zhanghai.android.files.apksign.AutoSigner

/**
 * Rebuilds an APK from its cache copy: keeps every entry byte-identical, replaces or adds
 * the entries in [changes], drops the old META-INF signature files, then re-signs with the
 * app's auto-generated key so the result is installable.
 */
object ApkRebuilder {

    /**
     * Reads a single entry from a (local) APK/ZIP file.
     */
    fun readEntry(file: File, name: String): ByteArray? {
        ZipFile(file).use { zipFile ->
            val entry = zipFile.getEntry(name) ?: return null
            return zipFile.getInputStream(entry).use { it.readBytes() }
        }
    }

    /**
     * Copies [sourcePath] (possibly on a remote file system) into the app's cache so the
     * zip/parse steps can use random access.
     */
    fun copyToCache(
        sourcePath: java8.nio.file.Path, cacheDirectory: File, name: String
    ): File {
        cacheDirectory.mkdirs()
        val target = File(cacheDirectory, name)
        java8.nio.file.Files.newInputStream(sourcePath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /**
     * @param changes entry name to new bytes; existing entries are replaced, missing
     * entries are appended.
     * @param keepMetaInf when false (default) all META-INF/ entries are dropped so the
     * stale v1 signature files do not conflict with the new signing.
     */
    fun rebuild(input: File, output: File, changes: Map<String, ByteArray>, keepMetaInf: Boolean = false) {
        ZipFile(input).use { zipFile ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zos ->
                val written = HashSet<String>()
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!keepMetaInf && name.startsWith("META-INF/")) {
                        continue
                    }
                    val replacement = changes[name]
                    if (replacement != null) {
                        writeEntry(zos, name, entry.time, replacement)
                    } else {
                        val newEntry = ZipEntry(name)
                        newEntry.method = entry.method
                        newEntry.time = entry.time
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.size = entry.size
                            newEntry.compressedSize = entry.compressedSize
                            newEntry.crc = entry.crc
                        }
                        zos.putNextEntry(newEntry)
                        zipFile.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    written.add(name)
                }
                changes.forEach { (name, bytes) ->
                    if (name !in written) {
                        writeEntry(zos, name, System.currentTimeMillis(), bytes)
                    }
                }
            }
        }
    }

    /**
     * Rebuilds [input] with [changes] applied and signs the result with the app's
     * auto-generated key, writing the final installable APK to [output].
     */
    fun rebuildAndSign(
        context: Context, input: File, output: File, changes: Map<String, ByteArray>
    ) {
        val rebuilt = File(input.parentFile, "rebuilt.apk")
        rebuild(input, rebuilt, changes)
        try {
            AutoSigner.sign(context, rebuilt, output)
        } finally {
            rebuilt.delete()
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, time: Long, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED
        entry.time = time
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }
}
