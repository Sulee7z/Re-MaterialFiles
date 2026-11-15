package me.zhanghai.android.files.provider.archive.archiver

import org.junit.Assert.*
import org.junit.Test

class ArchiveDataInputStreamTest {
    private fun stream(bytes: ByteArray): ArchiveDataInputStream {
        var position = 0
        return ArchiveDataInputStream { buffer ->
            assertTrue(buffer.isDirect)
            val count = minOf(buffer.remaining(), bytes.size - position)
            buffer.put(bytes, position, count)
            position += count
        }
    }

    @Test
    fun shortReadIntoLargeArrayRespectsRequestedWindow() {
        val input = stream(ByteArray(32768) { it.toByte() })
        val output = ByteArray(16384) { 0x55 }
        assertEquals(3, input.read(output, 17, 3))
        assertArrayEquals(byteArrayOf(0, 1, 2), output.copyOfRange(17, 20))
        assertTrue(output.take(17).all { it == 0x55.toByte() })
        assertTrue(output.drop(20).all { it == 0x55.toByte() })
        assertEquals(3, input.read())
    }

    @Test
    fun zeroLengthAndInvalidReadsDoNotConsumeData() {
        val input = stream(byteArrayOf(42))
        val output = ByteArray(4)
        assertEquals(0, input.read(output, 4, 0))
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 3 to 2, Int.MAX_VALUE to 1)) {
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(output, offset, length) }
        }
        assertEquals(42, input.read())
        assertEquals(-1, input.read(output))
        assertEquals(0, input.read(output, 0, 0))
    }

    @Test
    fun mixedReadsPreserveDataAcrossBufferBoundariesAndEof() {
        val expected = ByteArray(262400) { it.toByte() }
        val input = stream(expected)
        val actual = java.io.ByteArrayOutputStream()
        val output = ByteArray(20000)
        while (true) {
            val first = input.read()
            if (first == -1) break
            actual.write(first)
            val count = input.read(output, 7, 17000)
            if (count == -1) break
            assertTrue(count in 1..17000)
            actual.write(output, 7, count)
        }
        assertArrayEquals(expected, actual.toByteArray())
        assertEquals(-1, input.read())
        assertEquals(-1, input.read(output, 1, 10))
    }
}
