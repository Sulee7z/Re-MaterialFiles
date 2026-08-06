/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hex

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Files
import java8.nio.file.Path

class HexPage(
    val loading: Boolean = false,
    val error: String? = null,
    val hexText: String = "",
    val rangeText: String = ""
)

class HexViewerViewModel(private val path: Path) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 16 * 1024
        private const val BYTES_PER_LINE = 16
    }

    var currentPageOffset: Long = 0
        private set

    private val _pageLiveData = MutableLiveData<HexPage>()
    val pageLiveData: LiveData<HexPage>
        get() = _pageLiveData

    private var fileSize: Long? = null
    private var lastPageBytes: ByteArray = ByteArray(0)
    private var lastPageOffset: Long = 0

    init {
        loadPage(0)
    }

    fun loadPage(offset: Long) {
        if (offset < 0) {
            return
        }
        _pageLiveData.value = HexPage(loading = true)
        currentPageOffset = offset
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val page = try {
                if (fileSize == null) {
                    fileSize = try {
                        Files.size(path)
                    } catch (e: Exception) {
                        null
                    }
                }
                val bytes = ByteArray(PAGE_SIZE)
                var read = 0
                Files.newInputStream(path).use { stream ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val skipResult = stream.skip(offset - skipped)
                        if (skipResult <= 0) {
                            break
                        }
                        skipped += skipResult
                    }
                    while (read < PAGE_SIZE) {
                        val result = stream.read(bytes, read, PAGE_SIZE - read)
                        if (result < 0) {
                            break
                        }
                        read += result
                    }
                }
                lastPageBytes = bytes.copyOf(read)
                lastPageOffset = offset
                HexPage(
                    hexText = formatHex(bytes, read, offset),
                    rangeText = formatRange(offset, read)
                )
            } catch (e: Exception) {
                HexPage(error = e.javaClass.simpleName + ": " + e.message)
            }
            _pageLiveData.postValue(page)
        }
    }

    fun editableText(): String {
        val builder = StringBuilder()
        var index = 0
        while (index < lastPageBytes.size) {
            val lineEnd = minOf(index + BYTES_PER_LINE, lastPageBytes.size)
            for (i in index until lineEnd) {
                builder.append("%02x ".format(lastPageBytes[i].toInt() and 0xff))
            }
            builder.append('\n')
            index = lineEnd
        }
        return builder.toString()
    }

    fun saveEditableText(text: String) {
        val hexChars = text.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hexChars.length % 2 != 0) {
            throw IllegalArgumentException("Invalid hex text")
        }
        val bytes = ByteArray(hexChars.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hexChars.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val channel = Files.newByteChannel(path, java8.nio.file.StandardOpenOption.WRITE)
        channel.use {
            it.position(lastPageOffset)
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                it.write(buffer)
            }
        }
    }

    private fun formatRange(offset: Long, read: Int): String {
        val end = offset + read
        val size = fileSize
        val endText = if (size != null && size > 0) {
            "0x%x".format(end.coerceAtMost(size))
        } else {
            "0x%x".format(end)
        }
        val sizeText = size?.let { "0x%x".format(it) } ?: "?"
        return "0x%x - %s / %s".format(offset, endText, sizeText)
    }

    private fun formatHex(bytes: ByteArray, read: Int, offset: Long): String {
        val builder = StringBuilder()
        var lineOffset = offset
        var index = 0
        while (index < read) {
            builder.append("%08x: ".format(lineOffset))
            val lineEnd = minOf(index + BYTES_PER_LINE, read)
            for (i in index until lineEnd) {
                builder.append("%02x ".format(bytes[i].toInt() and 0xff))
            }
            for (i in lineEnd until index + BYTES_PER_LINE) {
                builder.append("   ")
            }
            builder.append('|')
            for (i in index until lineEnd) {
                val byte = bytes[i].toInt() and 0xff
                builder.append(if (byte in 0x20..0x7e) byte.toChar() else '.')
            }
            builder.append("|\n")
            index = lineEnd
            lineOffset += BYTES_PER_LINE
        }
        return builder.toString()
    }
}
