/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.EMPTY
import me.zhanghai.android.files.provider.common.readFully
import me.zhanghai.android.files.provider.common.writeByteBuffer
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

// https://blog.sphere.chronosempire.org.uk/2012/11/21/webdav-and-the-http-patch-nightmare
class FileByteChannel(
    private val resource: DavResource,
    private val patchSupport: PatchSupport,
    isAppend: Boolean
) : AbstractFileByteChannel(isAppend) {
    private var nextSequentialWritePosition = 0L
    private var sequentialWriteOutputStream: OutputStream? = null

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val inputStream = try {
            resource.getRangeCompat("*/*", position, size, null)
        } catch (e: HttpException) {
            if (e.code == HTTP_RANGE_NOT_SATISFIABLE) {
                // Some servers reject ranges for other reasons. Do not turn a failed
                // request inside the file into silent EOF and a truncated archive.
                if (position >= onSize()) {
                    return ByteBuffer::class.EMPTY
                }
            }
            throw e
        }
        val destination = ByteBuffer.allocate(size)
        val limit = inputStream.use {
            it.readFully(destination.array(), destination.arrayOffset(), size)
        }
        destination.limit(limit)
        return destination
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        when (patchSupport) {
            PatchSupport.APACHE ->
                resource.putRangeCompat(source, position) {}
            PatchSupport.SABRE ->
                resource.patchCompat(source, position) {}
            PatchSupport.NONE -> {
                if (position != nextSequentialWritePosition) {
                    throw IOException("Unsupported non-sequential write")
                }
                val outputStream = sequentialWriteOutputStream
                    ?: resource.putCompat().also { sequentialWriteOutputStream = it }
                writeByteBuffer(source) { bytes, start, length ->
                    outputStream.write(bytes, start, length)
                    nextSequentialWritePosition += length
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        if (size == 0L) {
            resource.put(byteArrayOf().toRequestBody()) {}
        } else {
            throw IOException("Unsupported truncate to non-zero size")
        }
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        val getContentLength =
            Client.findProperties(resource, GetContentLength.NAME)[GetContentLength::class.java]
                ?: throw IOException("Missing GetContentLength")
        return getContentLength.contentLength ?: throw IOException("Invalid GetContentLength")
    }

    @Throws(IOException::class)
    override fun onClose() {
        sequentialWriteOutputStream?.close()
    }

    companion object {
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
