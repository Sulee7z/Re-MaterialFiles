/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import java8.nio.file.Path as Java8Path
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.DelegateOutputStream
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedOutputStream
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedSeekableByteChannel
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPClientConfig
import org.apache.commons.net.ftp.FTPCmd
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

object Client {
    private val TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
            .withChronology(IsoChronology.INSTANCE)
            .withZone(ZoneOffset.UTC)

    @Volatile
    lateinit var authenticator: Authenticator

    private data class PooledClient(val client: FTPClient, val idleSinceMillis: Long)

    private val clientPool = mutableMapOf<Authority, MutableList<PooledClient>>()

    // FileZilla Server's default idle timeout is usually 120s; keep pooled connections well
    // below it so a borrowed client never gets a 421 right after the liveness check.
    private const val MAX_POOLED_IDLE_MILLIS = 60_000L

    private val directoryFilesCache = Collections.synchronizedMap(WeakHashMap<Path, FTPFile>())
    @Throws(IOException::class)
    private fun acquireClient(authority: Authority): FTPClient {
        while (true) {
            val client = acquireClientUnchecked(authority) ?: break
            if (!client.isConnected) {
                client.disconnect()
                continue
            }
            val isAlive = try {
                client.sendNoOp()
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
            if (!isAlive) {
                closeClient(client)
                continue
            }
            return client
        }
        return createClient(authority)
    }

    private fun acquireClientUnchecked(authority: Authority): FTPClient? =
        synchronized(clientPool) {
            val pooledClients = clientPool[authority] ?: return null
            while (pooledClients.isNotEmpty()) {
                val pooled = pooledClients.removeAt(pooledClients.lastIndex)
                if (pooledClients.isEmpty()) {
                    clientPool -= authority
                }
                val idleMillis = System.currentTimeMillis() - pooled.idleSinceMillis
                if (idleMillis <= MAX_POOLED_IDLE_MILLIS) {
                    return pooled.client
                }
                // Idled too long: the server may have already timed it out (421). Discard it
                // instead of relying on the NOOP liveness check.
                closeClient(pooled.client)
            }
            return null
        }

    @Throws(IOException::class)
    private fun createClient(authority: Authority): FTPClient {
        val password = authenticator.getPassword(authority)
            ?: throw IOException("No password found for $authority")
        return authority.protocol.createClient().apply {
            configure(FTPClientConfig(""))
            // This has to be set before connect().
            controlEncoding = authority.encoding
            listHiddenFiles = true
            connect(authority.host, authority.port)
            try {
                if (!FTPReply.isPositiveCompletion(replyCode)) {
                    throwNegativeReplyCodeException()
                }
                if (!login(authority.username, password)) {
                    throwNegativeReplyCodeException()
                }
            } catch (t: Throwable) {
                disconnect()
                throw t
            }
            // This has to be called after connect() despite being entirely local.
            if (authority.mode == Mode.PASSIVE) {
                enterLocalPassiveMode()
            }
            try {
                if (this is FTPSClient) {
                    // @see https://datatracker.ietf.org/doc/html/rfc4217#section-9
                    execPBSZ(0)
                    execPROT("P")
                }
                if (!setFileType(FTPClient.BINARY_FILE_TYPE)) {
                    throwNegativeReplyCodeException()
                }
            } catch (t: Throwable) {
                closeClient(this)
                throw t
            }
        }
    }

    private fun releaseClient(authority: Authority, client: FTPClient) {
        if (!client.isConnected) {
            client.disconnect()
            return
        }
        synchronized(clientPool) {
            clientPool.getOrPut(authority) { mutableListOf() } +=
                PooledClient(client, System.currentTimeMillis())
        }
    }

    private fun closeClient(client: FTPClient) {
        try {
            client.logout()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        client.disconnect()
    }

    private inline fun <R> useClient(authority: Authority, block: (FTPClient) -> R): R {
        val client = acquireClient(authority)
        try {
            return block(client)
        } finally {
            releaseClient(authority, client)
        }
    }

    @Throws(IOException::class)
    fun createDirectory(path: Path) {
        if (path.isEverythingServer) {
            EverythingClient.denyWrite(path, "Creating directories")
        }
        useClient(path.authority) { client ->
            if (!client.makeDirectory(path.remotePath)) {
                client.throwNegativeReplyCodeException()
            }
        }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(IOException::class)
    fun createFile(path: Path) {
        storeFile(path).close()
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(IOException::class)
    fun delete(path: Path) {
        val file = listFile(path, true)
        delete(path, file.isDirectory)
    }

    @Throws(IOException::class)
    fun delete(path: Path, isDirectory: Boolean) {
        if (isDirectory) {
            deleteDirectory(path)
        } else {
            deleteFile(path)
        }
    }

    @Throws(IOException::class)
    fun deleteFile(path: Path) {
        if (path.isEverythingServer) {
            EverythingClient.denyWrite(path, "Deleting")
        }
        useClient(path.authority) { client ->
            if (!client.deleteFile(path.remotePath)) {
                client.throwNegativeReplyCodeException()
            }
        }
        directoryFilesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(IOException::class)
    fun deleteDirectory(path: Path) {
        if (path.isEverythingServer) {
            EverythingClient.denyWrite(path, "Deleting")
        }
        useClient(path.authority) { client ->
            if (!client.removeDirectory(path.remotePath)) {
                client.throwNegativeReplyCodeException()
            }
        }
        directoryFilesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(IOException::class)
    fun renameFile(source: Path, target: Path) {
        if (source.authority != target.authority) {
            throw IOException("Paths aren't on the same authority")
        }
        if (source.isEverythingServer) {
            EverythingClient.denyWrite(source, "Renaming")
        }
        useClient(source.authority) { client ->
            if (!client.rename(source.remotePath, target.remotePath)) {
                client.throwNegativeReplyCodeException()
            }
        }
        directoryFilesCache -= source
        directoryFilesCache -= target
        LocalWatchService.onEntryDeleted(source as Java8Path)
        LocalWatchService.onEntryCreated(target as Java8Path)
    }

    @Throws(IOException::class)
    fun retrieveFile(path: Path): InputStream {
        if (path.isEverythingServer) {
            return EverythingClient.retrieveFile(path)
        }
        val authority = path.authority
        val client = acquireClient(authority)
        val inputStream = try {
            client.retrieveFileStream(path.remotePath) ?: client.throwNegativeReplyCodeException()
        } catch (t: Throwable) {
            releaseClient(authority, client)
            throw t
        }
        return CompletePendingCommandInputStream(inputStream, authority, client)
    }

    @Throws(IOException::class)
    fun listDirectory(path: Path): List<Path> {
        if (path.isEverythingServer) {
            // Everything HTTP backend: browse via the `parent:` search operator instead of
            // the (optional, finicky) ETP/FTP server, so the storage works with just the
            // Everything HTTP server enabled.
            val (childPaths, filesByName) = EverythingClient.listDirectory(path)
            filesByName.forEach { (name, file) ->
                directoryFilesCache[path.resolve(name)] = file
            }
            return childPaths
        }
        useClient(path.authority) { client ->
            // Whether MLSD returns dot files is entirely up to the server; the app's
            // "show hidden files" setting is only honored by the traditional LIST path
            // (listHiddenFiles appends "-a"). So when the user enables the setting, fall
            // back to LIST to make sure dot files actually appear.
            val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
            val files = (
                if (showHiddenFiles) client.listFiles(path.remotePath.escapeFtpGlob())
                else client.mlistDirCompat(path.remotePath)
                ) ?: client.throwNegativeReplyCodeException()
            return files.mapNotNull { file ->
                if (file.name == "." || file.name == "..") {
                    return@mapNotNull null
                }
                path.resolve(file.name).also { directoryFilesCache[it] = file }
            }
        }
    }

    @Throws(IOException::class)
    fun listFileOrNull(path: Path, noFollowLinks: Boolean): FTPFile? =
        try {
            listFile(path, noFollowLinks)
        } catch (e: NegativeReplyCodeException) {
            null
        }

    @Throws(IOException::class)
    fun listFile(path: Path, noFollowLinks: Boolean): FTPFile {
        val file = listFileNoFollowLinks(path, noFollowLinks)
        if (!file.isSymbolicLink || noFollowLinks) {
            return file
        }
        val targetString = file.link ?: throw IOException("FTPFile.getLink() returned null: $file")
        val target = path.resolve(targetString)
        return listFileNoFollowLinks(target, false)
    }

    @Throws(IOException::class)
    private fun listFileNoFollowLinks(path: Path, preserveCacheForSymbolicLink: Boolean): FTPFile {
        synchronized(directoryFilesCache) {
            directoryFilesCache[path]?.let {
                if (!(it.isSymbolicLink && preserveCacheForSymbolicLink)) {
                    directoryFilesCache -= path
                }
                return it
            }
        }
        if (path.isEverythingServer) {
            // Everything HTTP backend: stat via the parent's `parent:` listing (served
            // from the listing cache right after a directory listing).
            return EverythingClient.listFile(path)
        }
        useClient(path.authority) { client ->
            return client.mlistFileCompat(path.remotePath)
                ?: client.throwNegativeReplyCodeException()
        }
    }

    @Throws(IOException::class)
    fun openByteChannel(path: Path, isAppend: Boolean, truncate: Boolean): SeekableByteChannel {
        if (path.isEverythingServer) {
            if (isAppend || truncate) {
                EverythingClient.denyWrite(path, "Writing")
            }
            return EverythingClient.openByteChannel(path)
        }
        val authority = path.authority
        val client = acquireClient(authority)
        if (!client.hasFeature(FTPCmd.REST)) {
            throw IOException("Missing feature ${FTPCmd.REST.command}")
        }
        return NotifyEntryModifiedSeekableByteChannel(
            FileByteChannel(
                client,
                { releaseClient(authority, it) },
                { reconnectClient(authority, it) },
                path.remotePath, isAppend, truncate
            ), path as Java8Path
        )
    }

    /**
     * Disconnects the given client (so the server closes the session, releasing any stale
     * state/handles) and returns a fresh client. Some servers (FileZilla Server proxying SMB
     * shares) only honor the first REST command on a session, so data transfers that reopen
     * a stream must use a new connection.
     */
    @Throws(IOException::class)
    private fun reconnectClient(authority: Authority, client: FTPClient): FTPClient {
        if (client.isConnected) {
            try {
                client.disconnect()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return acquireClient(authority)
    }

    @Throws(IOException::class)
    fun setLastModifiedTime(path: Path, lastModifiedTime: Instant) {
        if (path.isEverythingServer) {
            EverythingClient.denyWrite(path, "Setting the modification time")
        }
        val lastModifiedTimeString = TIMESTAMP_FORMATTER.format(lastModifiedTime)
        useClient(path.authority) { client ->
            if (!client.setModificationTimeCompat(path.remotePath, lastModifiedTimeString)) {
                client.throwNegativeReplyCodeException()
            }
        }
        LocalWatchService.onEntryModified(path as Java8Path)
    }

    @Throws(IOException::class)
    fun storeFile(path: Path): OutputStream {
        if (path.isEverythingServer) {
            EverythingClient.denyWrite(path, "Writing")
        }
        val authority = path.authority
        val client = acquireClient(authority)
        val outputStream = try {
            client.storeFileStream(path.remotePath) ?: client.throwNegativeReplyCodeException()
        } catch (t: Throwable) {
            releaseClient(authority, client)
            throw t
        }
        return NotifyEntryModifiedOutputStream(
            CompletePendingCommandOutputStream(outputStream, authority, client), path as Java8Path
        )
    }

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }

    private class CompletePendingCommandInputStream(
        inputStream: InputStream,
        private val authority: Authority,
        private val client: FTPClient
    ) : DelegateInputStream(inputStream) {
        @Throws(IOException::class)
        override fun close() {
            try {
                super.close()
                if (!client.completePendingCommand()) {
                    // We may close the input stream before the file is fully read (may happen when
                    // decoding images) and it will result in an error reported here, but that's
                    // totally fine.
                    client.createNegativeReplyCodeException().printStackTrace()
                }
            } finally {
                releaseClient(authority, client)
            }
        }
    }

    private class CompletePendingCommandOutputStream(
        outputStream: OutputStream,
        private val authority: Authority,
        private val client: FTPClient
    ) : DelegateOutputStream(outputStream) {
        @Throws(IOException::class)
        override fun close() {
            try {
                super.close()
                if (!client.completePendingCommand()) {
                    client.throwNegativeReplyCodeException()
                }
            } finally {
                releaseClient(authority, client)
            }
        }
    }
}
