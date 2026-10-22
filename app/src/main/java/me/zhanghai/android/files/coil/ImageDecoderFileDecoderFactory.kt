/*
 * Copyright (c) 2025 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.os.Build
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.Decoder
import coil.decode.ImageDecoderDecoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.of
import java.io.IOException

/**
 * A [Decoder.Factory] that decodes images with [android.graphics.ImageDecoder], reading the
 * encoded image from a file (or a file descriptor) instead of from a Java [java.io.InputStream].
 *
 * Coil's own [coil.decode.BitmapFactoryDecoder] decodes images with
 * [android.graphics.BitmapFactory.decodeStream], which hands a Java byte array to the native
 * decoder, which reads the encoded image from that array through ART's JNI array copy
 * (art::JNI<false>::GetByteArrayRegion). A concurrent moving garbage collection can relocate
 * that array while the native decoder is still reading it, which crashes the process with a
 * native SIGSEGV.
 *
 * [android.graphics.ImageDecoder] reads the encoded image from a file descriptor, so the encoded
 * bytes never travel through the Java heap.
 *
 * Only the image formats that [android.graphics.ImageDecoder] supports are handled here. GIF
 * images are left to [ImageDecoderDecoder.Factory], which is registered after this factory and
 * keeps handling GIF images (including animated ones) and animated HEIF images.
 */
@RequiresApi(Build.VERSION_CODES.P)
class ImageDecoderFileDecoderFactory : Decoder.Factory {
    override fun create(
        result: SourceResult,
        options: Options,
        imageLoader: ImageLoader
    ): Decoder? {
        val source = result.source
        if (!source.isSupportedByImageDecoder()) {
            return null
        }
        // Make sure that the encoded image data is backed by a file, so that ImageDecoder reads
        // it from a file descriptor. If the image data is not backed by a file yet, this copies
        // it to a temporary file in the cache directory, which is deleted when the image source
        // is closed.
        try {
            source.file()
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
        return ImageDecoderDecoder(source, options)
    }

    override fun equals(other: Any?): Boolean = other is ImageDecoderFileDecoderFactory

    override fun hashCode(): Int = javaClass.hashCode()
}

private val PNG_SIGNATURE = ByteString.of(
    0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
    0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
)
private val JPEG_SIGNATURE = ByteString.of(
    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()
)
private val RIFF_SIGNATURE = ByteString.of(
    0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()
)
private val WEBP_SIGNATURE = ByteString.of(
    0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte()
)

/**
 * Returns whether the encoded image data is in a format that
 * [android.graphics.ImageDecoder] can decode.
 */
private fun ImageSource.isSupportedByImageDecoder(): Boolean = try {
    source().isPngOrJpegOrWebP()
} catch (e: IOException) {
    e.printStackTrace()
    false
}

private fun BufferedSource.isPngOrJpegOrWebP(): Boolean =
    rangeEquals(0, PNG_SIGNATURE) || rangeEquals(0, JPEG_SIGNATURE)
        || (rangeEquals(0, RIFF_SIGNATURE) && rangeEquals(8, WEBP_SIGNATURE))
