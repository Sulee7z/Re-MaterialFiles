package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.nio.ByteBuffer

/** Advances the source only after each blocking, all-or-throw write succeeds. */
@Throws(IOException::class)
internal fun writeByteBuffer(
    source: ByteBuffer,
    write: (ByteArray, Int, Int) -> Unit
) {
    if (!source.hasRemaining()) return
    if (source.hasArray()) {
        val count = source.remaining()
        write(source.array(), source.arrayOffset() + source.position(), count)
        source.position(source.position() + count)
        return
    }
    val bytes = ByteArray(minOf(source.remaining(), DEFAULT_BUFFER_SIZE))
    while (source.hasRemaining()) {
        val count = minOf(source.remaining(), bytes.size)
        source.duplicate().get(bytes, 0, count)
        write(bytes, 0, count)
        source.position(source.position() + count)
    }
}
