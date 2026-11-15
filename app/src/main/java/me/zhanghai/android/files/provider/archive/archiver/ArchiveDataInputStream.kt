/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.InputStream
import java.nio.ByteBuffer

/** Adapts libarchive's ByteBuffer writer to the bounded InputStream read contract. */
internal class ArchiveDataInputStream(
    private val readData: (ByteBuffer) -> Unit
) : InputStream() {
    private val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)

    override fun read(): Int {
        fill(1)
        return if (buffer.hasRemaining()) buffer.get().toInt() and 0xFF else -1
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException("size=${bytes.size}, off=$offset, len=$length")
        }
        if (length == 0) {
            return 0
        }
        fill(minOf(length, buffer.capacity()))
        val count = buffer.remaining()
        if (count == 0) {
            return -1
        }
        buffer.get(bytes, offset, count)
        return count
    }

    private fun fill(length: Int) {
        // Never clear a wrapped caller array: clear() discards its offset/length
        // window. Returning more than length can overrun a native stream consumer's
        // destination, such as BitmapFactory's JavaInputStreamAdaptor.
        buffer.clear()
        buffer.limit(length)
        readData(buffer)
        buffer.flip()
    }
}
