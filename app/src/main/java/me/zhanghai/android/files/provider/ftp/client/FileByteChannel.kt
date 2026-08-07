/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.nullInputStream
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.ByteBufferInputStream
import me.zhanghai.android.files.provider.common.readFully
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.showToast
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class FileByteChannel(
    private val client: FTPClient,
    private val releaseClient: (FTPClient) -> Unit,
    private val path: String,
    private val isAppendParam: Boolean,
    truncate: Boolean
) : AbstractFileByteChannel(isAppendParam, joinCancelledRead = true) {
    private val clientLock = Any()

    private var openInputStream: InputStream? = null
    private var openStreamPosition = 0L
    private var openOutputStream: java.io.OutputStream? = null
    private var openOutputPosition = 0L

    // A STOR without REST starts at offset 0 and truncates the file anyway, so a separate
    // empty STOR would only create a second transfer which some servers (FileZilla Server
    // proxying SMB shares) answer with 421. Truncation is thus deferred: it happens when
    // the first write opens the STOR stream, or at close() if nothing was written.
    private var needsTruncate = truncate

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val destination = ByteBuffer.allocate(size)
        ensureLocalCache()
        localCacheChannel?.let { channel ->
            // Read from the local cache: fully supports random access without any FTP
            // REST/RETR issues.
            synchronized(clientLock) {
                channel.position(position)
                var total = 0
                while (total < size) {
                    val count = channel.read(destination)
                    if (count == -1) {
                        break
                    }
                    total += count
                }
                destination.flip()
            }
            return destination
        }
        synchronized(clientLock) {
            // Sequential reads reuse the same RETR stream instead of issuing a REST+RETR
            // per chunk. Some servers mishandle REST for RETR, which would corrupt reads of
            // files larger than the read buffer. Random access (position moved) falls back
            // to REST+RETR.
            if (openInputStream == null || position != openStreamPosition) {
                closeOpenInputStream()
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

    private var localCacheChecked = false
    private var localCacheFile: File? = null
    private var localCacheChannel: java.nio.channels.FileChannel? = null

    /**
     * Like AmazeFileManager, the whole file is downloaded once into a local cache and then
     * read from there. This sidesteps servers that corrupt chunked REST+RETR reads
     * (FileZilla Server proxying SMB shares) and fully supports random access.
     */
    @Throws(IOException::class)
    private fun ensureLocalCache() {
        if (localCacheChecked || isAppendParam) {
            return
        }
        localCacheChecked = true
        val cacheDirectory = File(application.cacheDir, "ftp-cache")
        cacheDirectory.mkdirs()
        val cacheFile = File(
            cacheDirectory, "ftp-${path.hashCode()}-${System.currentTimeMillis()}.tmp"
        )
        application.showToast(
            application.getString(R.string.ftp_cache_downloading)
        )
        try {
            synchronized(clientLock) {
                client.setRestartOffset(0)
                val inputStream = client.retrieveFileStream(path)
                    ?: client.throwNegativeReplyCodeException()
                try {
                    inputStream.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } finally {
                    client.completePendingCommand()
                }
            }
            localCacheFile = cacheFile
            localCacheChannel = cacheFile.inputStream().channel
            application.showToast(
                application.getString(R.string.ftp_cache_done)
            )
        } catch (e: IOException) {
            cacheFile.delete()
            throw e
        }
    }

    private fun clearLocalCache() {
        localCacheChannel?.closeSafe()
        localCacheChannel = null
        localCacheFile?.delete()
        localCacheFile = null
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
            clearLocalCache()
            // Sequential writes reuse the same STOR stream instead of issuing a REST+STOR
            // per chunk, because some servers (e.g. FileZilla Server proxying SMB shares)
            // mishandle REST and would restart each STOR at the beginning of the file.
            if (openOutputStream == null || position != openOutputPosition) {
                closeOpenOutputStream()
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
        localCacheChannel?.let { return it.size() }
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
                InputStream::class.nullInputStream().use {
                    if (!client.storeFile(path, it)) {
                        client.throwNegativeReplyCodeException()
                    }
                }
            }
            closeOpenInputStream()
            closeOpenOutputStream()
            clearLocalCache()
            releaseClient(client)
        }
    }
}



