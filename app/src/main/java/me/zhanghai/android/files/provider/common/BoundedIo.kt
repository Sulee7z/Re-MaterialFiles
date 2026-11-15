package me.zhanghai.android.files.provider.common

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer

/** Enforces the cap while reading, rather than after an unbounded allocation. */
internal fun InputStream.readBytesBounded(maxSize: Int): ByteArray {
    require(maxSize >= 0)
    val output = ByteArrayOutputStream(minOf(maxSize, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        val remaining = maxSize - output.size()
        // Read one extra byte to distinguish exact-limit EOF from oversized input.
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining.toLong() + 1).toInt())
        if (count == -1) break
        if (count == 0) throw IOException("Input stream made no progress")
        if (count > remaining) throw IOException("Archive entry exceeds $maxSize bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Native archive write callbacks require the whole block, even on short channel writes. */
internal fun writeFully(buffer: ByteBuffer, write: (ByteBuffer) -> Int): Int {
    val size = buffer.remaining()
    while (buffer.hasRemaining()) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        val position = buffer.position()
        val count = write(buffer)
        if (count <= 0 || count != buffer.position() - position) {
            throw IOException("Invalid channel write progress: $count")
        }
    }
    return size
}
