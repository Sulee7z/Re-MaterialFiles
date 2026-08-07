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
    private var client: FTPClient,
    private val releaseClient: (FTPClient) -> Unit,
    private val reconnectClient: (FTPClient) -> FTPClient,
    private val path: String,
    private val isAppendParam: Boolean,
    truncate: Boolean
) : AbstractFileByteChannel(isAppendParam, joinCancelledRead = true) {
    private val clientLock = Any()

    private var openInputStream: InputStream? = null
    private var openStreamPosition = 0L
    private var openOutputStream: java.io.OutputStream? = null
    private var openOutputPosition = 0L
    private var hasUsedDataTransfer = false

    // A STOR without REST starts at offset 0 and truncates the file anyway, so a separate
    // empty STOR would only create a second transfer which some servers (FileZilla Server
    // proxying SMB shares) answer with 421. Truncation is thus deferred: it happens when
    // the first write opens the STOR stream, or at close() if nothing was written.
    private var needsTruncate = truncate

    /**
     * Some servers (FileZilla Server proxying SMB shares) only honor the first REST command
     * on a session and ignore later ones, which corrupts chunked REST+RETR reads/writes on a
     * reused connection. Reopening a data stream therefore switches to a fresh connection,
     * like AmazeFileManager does per operation.
     */
    @Throws(IOException::class)
    private fun ensureFreshConnectionForNewTransfer() {
        if (hasUsedDataTransfer) {
            closeOpenInputStream()
            closeOpenOutputStream()
            client = reconnectClient(client)
        }
        hasUsedDataTransfer = true
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val destination = ByteBuffer.allocate(size)
        synchronized(clientLock) {
            // Sequential reads reuse the same RETR stream. Random access (position moved)
            // reopens with a fresh connection because some servers only honor the first REST.
            if (openInputStream == null || position != openStreamPosition) {
                closeOpenInputStream()
                ensureFreshConnectionForNewTransfer()
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
                closeOpenInputStream()
                throw e
            }
        }
        return destination
    }

    private fun closeOpenInputStream() {
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
            closeOpenInputStream()
            // Sequential writes reuse the same STOR stream. Reopening (random access)
            // switches to a fresh connection like AmazeFileManager per operation.
            if (openOutputStream == null || position != openOutputPosition) {
                closeOpenOutputStream()
                ensureFreshConnectionForNewTransfer()
                client.restartOffset = position
                openOutputStream = client.storeFileStream(path)
                    ?: client.throwNegativeReplyCodeException()
                openOutputPosition = position
                needsTruncate = false
            }
            val outputStream = openOutputStream!!
            try {
                val remaining = source.remaining()
                ByteBufferInputStream(source).use { input ->
                    input.copyTo(outputStream)
                }
                openOutputPosition += remaining
            } catch (e: IOException) {
                closeOpenOutputStream()
                throw e
            }
        }
    }

    @Throws(IOException::class)
    override fun onAppend(source: ByteBuffer) {
        synchronized(clientLock) {
            closeOpenInputStream()
            closeOpenOutputStream()
            ensureFreshConnectionForNewTransfer()
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
            closeOpenInputStream()
            closeOpenOutputStream()
            ensureFreshConnectionForNewTransfer()
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
    override fun onForce(metaData: Boolean) {
        // Keep the single STOR stream open: closing it here would force a new REST+STOR on
        // the next write, which some servers (FileZilla Server proxying SMB shares) mishandle
        // and answer with 421. Data is already flowing to the server over the data socket.
    }

    private fun closeOpenOutputStream() {
        val outputStream = openOutputStream ?: return
        openOutputStream = null
        try {
            outputStream.close()
        } finally {
            client.completePendingCommand()
        }
    }

    @Throws(IOException::class)
    override fun onClose() {
        synchronized(clientLock) {
            if (needsTruncate && openOutputStream == null) {
                // Opened for truncation but nothing was written: truncate explicitly.
                ensureFreshConnectionForNewTransfer()
                InputStream::class.nullInputStream().use {
                    if (!client.storeFile(path, it)) {
                        client.throwNegativeReplyCodeException()
                    }
                }
            }
            closeOpenInputStream()
            closeOpenOutputStream()
            releaseClient(client)
        }
    }
}







