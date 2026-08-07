/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import me.zhanghai.android.files.compat.nullInputStream
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.ByteBufferInputStream
import me.zhanghai.android.files.provider.common.readFully
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class FileByteChannel(
    private val client: FTPClient,
    private val releaseClient: (FTPClient) -> Unit,
    private val path: String,
    isAppend: Boolean,
    truncate: Boolean
) : AbstractFileByteChannel(isAppend, joinCancelledRead = true) {
    private val clientLock = Any()

    private var openInputStream: InputStream? = null
    private var openStreamPosition = 0L

    init {
        if (truncate) {
            // FTP's REST+STOR does not truncate the tail of an existing file, so truncate it
            // explicitly by storing empty data before any write happens.
            synchronized(clientLock) {
                InputStream::class.nullInputStream().use {
                    if (!client.storeFile(path, it)) {
                        client.throwNegativeReplyCodeException()
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val destination = ByteBuffer.allocate(size)
        synchronized(clientLock) {
            // Sequential reads reuse the same RETR stream instead of issuing a REST+RETR
            // per chunk. Some servers mishandle REST for RETR, which would corrupt reads of
            // files larger than the read buffer. Random access (position moved) falls back
            // to REST+RETR.
            if (openInputStream == null || position != openStreamPosition) {
                closeOpenStream()
                client.restartOffset = position
                openInputStream = client.retrieveFileStream(path)
                    ?: client.throwNegativeReplyCodeException()
                openStreamPosition = position
            }
            val inputStream = openInputStream!!
            try {
                val limit = inputStream.readFully(
                    destination.array(), destination.arrayOffset(), size
                )
                destination.limit(limit)
                openStreamPosition += limit
            } catch (e: IOException) {
                // The stream may have been closed by the server; reopen on the next read.
                closeOpenStream()
                throw e
            }
        }
        return destination
    }

    private fun closeOpenStream() {
        val inputStream = openInputStream ?: return
        openInputStream = null
        try {
            inputStream.close()
        } finally {
            client.completePendingCommand()
        }
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        synchronized(clientLock) {
            closeOpenStream()
            client.restartOffset = position
            ByteBufferInputStream(source).use {
                if (!client.storeFile(path, it)) {
                    client.throwNegativeReplyCodeException()
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun onAppend(source: ByteBuffer) {
        synchronized(clientLock) {
            closeOpenStream()
            ByteBufferInputStream(source).use {
                if (!client.appendFile(path, it)) {
                    client.throwNegativeReplyCodeException()
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        synchronized(clientLock) {
            closeOpenStream()
            client.restartOffset = size
            InputStream::class.nullInputStream().use {
                if (!client.storeFile(path, it)) {
                    client.throwNegativeReplyCodeException()
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        val sizeString = synchronized(clientLock) {
            client.getSize(path) ?: client.throwNegativeReplyCodeException()
        }
        return sizeString.toLongOrNull() ?: throw IOException("Invalid size $sizeString")
    }

    @Throws(IOException::class)
    override fun onClose() {
        synchronized(clientLock) {
            closeOpenStream()
            releaseClient(client)
        }
    }
}
