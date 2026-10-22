/*
 * Copyright (c) 2025 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.io.FileDescriptor
import java.io.IOException

/**
 * Decodes the bounds (size and MIME type) of the image at this path.
 *
 * [BitmapFactory.decodeStream] reads the encoded image into a Java byte array and hands the raw
 * pointer of that array to the native decoder, which reads the encoded image from that array
 * through ART's JNI array copy (art::JNI<false>::GetByteArrayRegion). A concurrent moving
 * garbage collection can relocate the array while the native decoder is still reading it, which
 * crashes the process with a native SIGSEGV. Decoding from a file path or from a file descriptor
 * keeps the encoded bytes out of the Java heap and is therefore immune to that failure.
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
    // There is neither a local file nor a file descriptor for this path (e.g. FTP, SFTP and SMB
    // paths). The decode still completes before the stream is closed, but the encoded bytes still
    // travel through a Java byte array, so the ART relocation hazard remains for these paths.
    newInputStream().use { BitmapFactory.decodeStream(it, null, options) }
    return options
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
