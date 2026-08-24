/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import java8.nio.file.Path
import me.zhanghai.android.files.dex.DexAnalyzerActivity
import me.zhanghai.android.files.elf.ElfAnalyzerActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isTextOrCode
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.text.TextEditorActivity

/**
 * Built-in openers for file categories (MT Manager style): images open in the image viewer,
 * text/code in the editor, DEX in the DEX analyzer and ELF in the ELF analyzer, without
 * consulting the system default. Shared by the file list and the index search screen.
 */
object BuiltInFileOpeners {

    // Real .odex files are OAT/ELF containers, not DEX; the parser only understands plain
    // DEX, so do not claim odex support.
    val DEX_ANALYZER_EXTENSIONS = setOf("dex")

    val ELF_ANALYZER_EXTENSIONS = setOf("so", "elf")

    /**
     * Returns an intent launching the built-in opener for [path]/[mimeType], or null when no
     * built-in opener applies (the caller then falls back to the system default). For images,
     * [imagePaths] can supply the neighbouring images for swipe navigation; when null the
     * single image is opened. When [verifyBinaryMagic] is set (callers on an IO thread, e.g.
     * the index search screen), DEX/ELF openers additionally check the file header so that a
     * misnamed regular file falls back instead of failing inside the analyzer.
     */
    fun createOpenIntent(
        path: Path,
        mimeType: MimeType,
        imagePaths: List<Path>? = null,
        verifyBinaryMagic: Boolean = false
    ): Intent? {
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return when {
            mimeType.isImage -> ImageViewerActivity::class.createIntent().apply {
                val paths = imagePaths ?: listOf(path)
                ImageViewerActivity.putExtras(this, paths, paths.indexOf(path).coerceAtLeast(0))
                extraPath = path
            }
            mimeType.isTextOrCode -> TextEditorActivity::class.createIntent().apply {
                extraPath = path
            }
            extension in DEX_ANALYZER_EXTENSIONS -> {
                if (verifyBinaryMagic && !hasMagic(path, DEX_MAGIC)) {
                    null
                } else {
                    DexAnalyzerActivity::class.createIntent().apply { extraPath = path }
                }
            }
            extension in ELF_ANALYZER_EXTENSIONS -> {
                if (verifyBinaryMagic && !hasMagic(path, ELF_MAGIC)) {
                    null
                } else {
                    ElfAnalyzerActivity::class.createIntent().apply { extraPath = path }
                }
            }
            else -> null
        }
    }

    private fun hasMagic(path: Path, magic: ByteArray): Boolean {
        return try {
            java8.nio.file.Files.newInputStream(path).use { input ->
                val buffer = ByteArray(magic.size)
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read == -1) {
                        return false
                    }
                    offset += read
                }
                buffer.contentEquals(magic)
            }
        } catch (e: Exception) {
            false
        }
    }

    private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())

    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    // ---------------------------------------------------------------- content sniffing
    //
    // Files without an extension (or with an unrecognized one) resolve to the generic
    // MIME and would always land in the "open as" dialog. Sniffing the file header
    // routes them to the right built-in viewer (or gives the system intent a usable
    // MIME) exactly like a desktop file manager.

    private const val HEADER_SAMPLE_SIZE = 4096

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    private val GIF_MAGIC = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte())
    private val BMP_MAGIC = byteArrayOf('B'.code.toByte(), 'M'.code.toByte())
    private val RIFF_MAGIC = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
    private val WEBP_MAGIC = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03.toByte(), 0x04.toByte())
    private val PDF_MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())
    private val MKV_MAGIC = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    /** Reads up to [size] leading bytes of [path], or null on any error. */
    fun readHeader(path: Path, size: Int): ByteArray? = try {
        java8.nio.file.Files.newInputStream(path).use { input ->
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read == -1) {
                    break
                }
                offset += read
            }
            buffer.copyOf(offset)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Content-sniffed open intent for files whose name gives no hint (no extension or an
     * unrecognized one): ELF/DEX go to their analyzers, recognized images to the viewer,
     * and a header without a single zero byte is treated as text for the editor. Returns
     * null when the content is an unknown binary (the caller keeps its usual fallback).
     */
    fun createOpenIntentForContent(path: Path): Intent? {
        val header = readHeader(path, HEADER_SAMPLE_SIZE) ?: return null
        return when {
            header.startsWith(ELF_MAGIC) ->
                ElfAnalyzerActivity::class.createIntent().apply { extraPath = path }
            header.startsWith(DEX_MAGIC) ->
                DexAnalyzerActivity::class.createIntent().apply { extraPath = path }
            header.startsWith(JPEG_MAGIC) || header.startsWith(PNG_MAGIC) ||
                header.startsWith(GIF_MAGIC) || header.startsWith(BMP_MAGIC) ||
                (header.startsWith(RIFF_MAGIC) && header.startsWithWebP()) ->
                ImageViewerActivity::class.createIntent().apply { extraPath = path }
            isTextHeader(header) ->
                TextEditorActivity::class.createIntent().apply { extraPath = path }
            else -> null
        }
    }

    /**
     * Content-sniffed MIME for files whose name gives no hint, so the system VIEW intent
     * reaches handlers that filter by MIME type. Returns null when nothing matches.
     */
    fun sniffMimeType(path: Path): String? {
        val header = readHeader(path, 16) ?: return null
        return when {
            header.startsWith(ELF_MAGIC) -> "application/x-executable"
            header.startsWith(DEX_MAGIC) -> "application/vnd.android.dex"
            header.startsWith(JPEG_MAGIC) -> "image/jpeg"
            header.startsWith(PNG_MAGIC) -> "image/png"
            header.startsWith(GIF_MAGIC) -> "image/gif"
            header.startsWith(BMP_MAGIC) -> "image/bmp"
            header.startsWith(RIFF_MAGIC) && header.startsWithWebP() -> "image/webp"
            header.startsWith(ZIP_MAGIC) -> "application/zip"
            header.startsWith(PDF_MAGIC) -> "application/pdf"
            header.size >= 12 && header.startsWith(MKV_MAGIC) -> "video/x-matroska"
            header.size >= 8 && header.copyOfRange(4, 8).contentEquals(
                byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
            ) -> "video/mp4"
            isTextHeader(readHeader(path, HEADER_SAMPLE_SIZE) ?: return null) -> "text/plain"
            else -> null
        }
    }

    private fun ByteArray.startsWithWebP(): Boolean =
        size >= 12 && copyOfRange(8, 12).contentEquals(WEBP_MAGIC)

    /** Classic text heuristic: no NUL byte in the sampled header. */
    private fun isTextHeader(header: ByteArray): Boolean = header.none { it == 0.toByte() }
}
