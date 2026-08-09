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

    val DEX_ANALYZER_EXTENSIONS = setOf("dex", "odex")

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
}
