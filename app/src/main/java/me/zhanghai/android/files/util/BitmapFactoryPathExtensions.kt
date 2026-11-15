/*
 * Copyright (c) 2025 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import java8.nio.file.Path
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.io.FileDescriptor
import java.io.File
import java.io.InterruptedIOException
import java.io.IOException

/**
 * Decodes the bounds (size and MIME type) of the image at this path.
 *
 * Use a local file or seekable descriptor for native decoding. Remote and archive streams
 * are first copied to an app-owned temporary file; no custom InputStream is handed to the
 * framework's native image decoder.
 *
 * The returned options have [BitmapFactory.Options.inJustDecodeBounds] set, so only the
 * `outWidth`, `outHeight` and `outMimeType` fields are filled in.
 */
@Throws(IOException::class)
fun Path.decodeImageBounds(): BitmapFactory.Options {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // Local files are decoded from their path, which opens a file descriptor and decodes it
    // natively.
    if (isLinuxPath) {
        val file = toFile()
        if (file.canRead()) {
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth != -1) {
                return options
            }
        }
    }
    // Document providers can give us a file descriptor, which is decoded natively as well.
    if (isDocumentPath) {
        val parcelFileDescriptor = try {
            DocumentResolver.openParcelFileDescriptor(this as DocumentResolver.Path, "r")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (parcelFileDescriptor != null) {
            try {
                // Only a seekable file descriptor is decoded natively, because
                // BitmapFactory.decodeFileDescriptor() falls back to decoding from a stream for a
                // non-seekable one.
                if (parcelFileDescriptor.fileDescriptor.seekToStart()) {
                    BitmapFactory.decodeFileDescriptor(
                        parcelFileDescriptor.fileDescriptor, null, options
                    )
                    if (options.outWidth != -1) {
                        return options
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                parcelFileDescriptor.close()
            }
        }
    }
    val temporaryFile = File.createTempFile("image-bounds-", ".tmp", application.cacheDir)
    try {
        newInputStream().use { input ->
            temporaryFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException()
                    }
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
            }
        }
        BitmapFactory.decodeFile(temporaryFile.path, options)
        return options
    } finally {
        temporaryFile.delete()
    }
}

/**
 * Seeks this file descriptor to its start, and returns whether it can be seeked at all, which is
 * required for it to be decoded natively by [BitmapFactory.decodeFileDescriptor].
 */
private fun FileDescriptor.seekToStart(): Boolean = try {
    Os.lseek(this, 0, OsConstants.SEEK_SET) >= 0
} catch (e: Exception) {
    // A non-seekable file descriptor fails with ESPIPE.
    false
}
