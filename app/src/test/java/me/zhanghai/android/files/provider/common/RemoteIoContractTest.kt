package me.zhanghai.android.files.provider.common

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import org.junit.Assert.*
import org.junit.Test

class RemoteIoContractTest {
    private class MemoryChannel : AbstractFileByteChannel(false) {
        val data = ByteArray(64) { it.toByte() }
        var length = data.size
        override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> {
            val count = minOf(size.toLong(), (length - position).coerceAtLeast(0)).toInt()
            return CompletableFuture.completedFuture(
                ByteBuffer.wrap(data.copyOfRange(position.coerceAtMost(length.toLong()).toInt(),
                    position.coerceAtMost(length.toLong()).toInt() + count))
            )
        }
        override fun onWrite(position: Long, source: ByteBuffer) {
            val count = minOf(2, source.remaining()) // Simulate a short remote write.
            source.get(data, position.toInt(), count)
        }
        override fun onSize(): Long = length.toLong()
        override fun onTruncate(size: Long) { length = size.toInt() }
    }

    @Test
    fun partialWritesReportActualCountAndInvalidateReadAhead() {
        MemoryChannel().use { channel ->
            channel.read(ByteBuffer.allocate(1)) // Prefetch the original contents.
            val source = ByteBuffer.wrap(byteArrayOf(99, 98, 97, 96))
            assertEquals(2, channel.write(source))
            assertEquals(3L, channel.position())
            assertEquals(2, source.position())
            channel.position(1)
            val result = ByteBuffer.allocate(3)
            assertEquals(3, channel.read(result))
            assertArrayEquals(byteArrayOf(99, 98, 3), result.array())
        }
    }

    @Test
    fun truncateDiscardsBufferedBytesPastNewEofAndNegativeSeekIsRejected() {
        MemoryChannel().use { channel ->
            channel.read(ByteBuffer.allocate(1))
            channel.truncate(2)
            val result = ByteBuffer.allocate(20)
            assertEquals(1, channel.read(result))
            assertEquals(-1, channel.read(result))
            assertEquals(0, channel.read(ByteBuffer.allocate(0)))
            assertThrows(IllegalArgumentException::class.java) { channel.position(-1) }
        }
    }

    @Test
    fun directReadOnlyAndSlicedBuffersWriteOnlyTheirWindow() {
        val input = ByteArray(20000) { it.toByte() }
        val direct = ByteBuffer.allocateDirect(input.size).apply { put(input); flip(); position(7); limit(19000) }
        val sliced = ByteBuffer.wrap(input).apply { position(7); limit(19000) }.slice()
        for (source in listOf(direct, sliced, sliced.asReadOnlyBuffer())) {
            val output = ByteArrayOutputStream()
            writeByteBuffer(source) { bytes, offset, length -> output.write(bytes, offset, length) }
            assertFalse(source.hasRemaining())
            assertArrayEquals(input.copyOfRange(7, 19000), output.toByteArray())
        }
    }

    @Test
    fun failedWriteDoesNotConsumeTheUnacknowledgedChunk() {
        val source = ByteBuffer.allocateDirect(20000)
        var calls = 0
        assertThrows(IOException::class.java) {
            writeByteBuffer(source) { _, _, _ ->
                if (++calls == 2) throw IOException("upload rejected")
            }
        }
        assertEquals(DEFAULT_BUFFER_SIZE, source.position())
    }

    @Test
    fun hugeSkipDoesNotOverflowAndInvalidReadsFailEvenAtEof() {
        val stream = ByteBufferInputStream(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        assertEquals(3L, stream.skip(Long.MAX_VALUE))
        assertEquals(-1, stream.read())
        assertEquals(0, stream.read(ByteArray(0), 0, 0))
        assertThrows(IndexOutOfBoundsException::class.java) { stream.read(ByteArray(1), 2, 1) }
    }

    @Test
    fun boundedEntryReadRejectsOversizeBeforeReadingWholeInput() {
        val input = ByteArray(10000).inputStream()
        assertThrows(IOException::class.java) { input.readBytesBounded(10) }
        assertEquals(9989, input.available())
        assertEquals(10, ByteArray(10).inputStream().readBytesBounded(10).size)
    }

    @Test
    fun archiveBlockWritesRetryShortWritesAndRejectNoProgress() {
        MemoryChannel().use { channel ->
            assertEquals(5, writeFully(ByteBuffer.wrap(byteArrayOf(4, 3, 2, 1, 0))) { channel.write(it) })
            assertArrayEquals(byteArrayOf(4, 3, 2, 1, 0), channel.data.copyOfRange(0, 5))
        }
        assertThrows(IOException::class.java) { writeFully(ByteBuffer.allocate(1)) { 0 } }
    }
}
