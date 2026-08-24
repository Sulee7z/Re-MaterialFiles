/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import android.util.Base64
import org.apache.commons.net.ftp.FTPFile
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.NoSuchFileException

/**
 * Whether [path] is backed by the Everything HTTP server (an Everything root and HTTP
 * port are configured on its authority).
 */
val Client.Path.isEverythingServer: Boolean
    get() = authority.everythingHttpPort != 0 && authority.everythingWindowsRoot.isNotEmpty()

/**
 * Everything (voidtools) HTTP server backend for Everything-configured FTP storages.
 *
 * The Everything HTTP server alone can serve the whole storage over pure HTTP, using the
 * same API as the Everything web UI and the open-source EverythingDroid client:
 *
 *  - Browse a folder: search `parent:"<windows path>"` (direct children only).
 *  - Stat an entry: look it up in its parent's `parent:` listing.
 *  - Read a file: `GET http://host:port/<windows path with '/' separators>` (supports
 *    Range requests; UNC paths escape the leading `\\` as `%5C%5C`).
 *
 * This keeps Everything storages usable without Everything's optional ETP/FTP server,
 * which is finicky to configure and was the reason the entries could not connect.
 */
object EverythingClient {

    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 60_000
    private const val MAX_RESULTS = 1000

    /** How long a `parent:` listing stays fresh for stat() lookups, in milliseconds. */
    private const val LISTING_CACHE_MILLIS = 10_000L

    private data class CachedListing(
        val key: String,
        val cachedAtMillis: Long,
        val filesByName: Map<String, FTPFile>
    )

    @Volatile
    private var cachedListing: CachedListing? = null

    /**
     * The Windows path for [path], or null when the remote path lies outside the
     * configured Everything index root.
     */
    fun windowsPathOrNull(path: Client.Path): String? =
        mapRemotePathToEverythingWindowsPath(path.remotePath, path.authority.everythingWindowsRoot)

    /**
     * Lists the direct children of [path] (its Windows path), returning the child paths
     * together with a synthetic [FTPFile] per child (consumed by the attribute views).
     *
     * Uses the `parent:` operator like the Everything web UI's folder browsing; when the
     * server does not know `parent:` (Everything 1.4), falls back to a scoped recursive
     * `path:` search filtered down to direct children.
     */
    @Throws(IOException::class)
    fun listDirectory(path: Client.Path): Pair<List<Client.Path>, Map<String, FTPFile>> {
        val windowsPath = windowsPathOrNull(path)
            ?: throw NoSuchFileException(path.toString())
        val key = cacheKey(path.authority, windowsPath)
        cachedListing?.takeIf { it.key == key && isFresh(it) }?.let { cached ->
            return pathsFor(path, cached.filesByName)
        }
        val filesByName = queryChildren(path.authority, windowsPath)
        synchronized(this) {
            cachedListing = CachedListing(key, System.currentTimeMillis(), filesByName)
        }
        return pathsFor(path, filesByName)
    }

    /**
     * Stats [path] via its parent's `parent:` listing (served from the listing cache when
     * possible). The storage root itself is always reported as an existing directory.
     */
    @Throws(IOException::class)
    fun listFile(path: Client.Path): FTPFile {
        val remotePath = path.remotePath
        if (remotePath == "/") {
            return directoryFile("")
        }
        val windowsPath = windowsPathOrNull(path) ?: throw NoSuchFileException(path.toString())
        val parentRemotePath = remotePath.substringBeforeLast('/', "")
        val name = remotePath.substringAfterLast('/')
        val parentWindowsPath = mapRemotePathToEverythingWindowsPath(
            parentRemotePath, path.authority.everythingWindowsRoot
        ) ?: throw NoSuchFileException(path.toString())
        val key = cacheKey(path.authority, parentWindowsPath)
        synchronized(this) {
            cachedListing?.takeIf { it.key == key && isFresh(it) }?.let { cached ->
                cached.filesByName[name]?.let { return it }
                throw NoSuchFileException(path.toString())
            }
        }
        val filesByName = queryChildren(path.authority, parentWindowsPath)
        synchronized(this) {
            cachedListing = CachedListing(key, System.currentTimeMillis(), filesByName)
        }
        return filesByName[name] ?: throw NoSuchFileException(path.toString())
    }

    /** Opens the file content stream over HTTP (the server must allow file download). */
    @Throws(IOException::class)
    fun retrieveFile(path: Client.Path): InputStream {
        val connection = openConnection(path, 0)
        return connection.inputStream
    }

    /** Opens a read-only seekable channel over HTTP (Range requests power seeking). */
    @Throws(IOException::class)
    fun openByteChannel(path: Client.Path): SeekableByteChannel =
        HttpReadonlyChannel(path)

    @Throws(IOException::class)
    fun denyWrite(path: Client.Path, operation: String): Nothing =
        throw java8.nio.file.AccessDeniedException(
            path.toString(), null, "$operation is not supported: the Everything HTTP " +
                "server is read-only"
        )

    private fun pathsFor(
        path: Client.Path,
        filesByName: Map<String, FTPFile>
    ): Pair<List<Client.Path>, Map<String, FTPFile>> {
        val childPaths = filesByName.values
            .mapNotNull { file -> file.name?.takeIf { it.isNotEmpty() } }
            .map { name -> path.resolve(name) }
        return childPaths to filesByName
    }

    private fun isFresh(cached: CachedListing): Boolean =
        System.currentTimeMillis() - cached.cachedAtMillis <= LISTING_CACHE_MILLIS

    private fun cacheKey(authority: Authority, windowsPath: String): String =
        "${authority.host}:${authority.everythingHttpPort}|$windowsPath"

    /** Queries the direct children of [windowsPath] as synthetic FTPFiles keyed by name. */
    @Throws(IOException::class)
    private fun queryChildren(
        authority: Authority,
        windowsPath: String
    ): Map<String, FTPFile> {
        // `parent:` lists direct children only (Everything 1.5+ / the operator the
        // EverythingDroid reference client browses with). When it yields nothing at all,
        // fall back to a scoped recursive search filtered to direct children, so 1.4
        // servers (without `parent:`) still browse.
        val direct = search(
            authority, "parent:\"$windowsPath\"", windowsPath, directChildrenOnly = false
        )
        val filesByName = direct.ifEmpty {
            search(authority, "path:\"$windowsPath\"", windowsPath, directChildrenOnly = true)
        }
        return filesByName
    }

    /**
     * Runs an Everything search and maps the JSON results to synthetic FTPFiles keyed by
     * entry name. With [directChildrenOnly], results outside [windowsPath]'s direct
     * children are dropped (Everything's `path:` matches whole subtrees).
     */
    @Throws(IOException::class)
    private fun search(
        authority: Authority,
        query: String,
        windowsPath: String,
        directChildrenOnly: Boolean
    ): Map<String, FTPFile> {
        val normalizedRoot = windowsPath.trimEnd('\\', '/')
        val rootPrefix = if (normalizedRoot.isEmpty()) {
            // Drive root (e.g. "C:\"): children are "C:\name".
            windowsPath
        } else {
            "$normalizedRoot\\"
        }
        val filesByName = linkedMapOf<String, FTPFile>()
        forEachResult(authority, query) { item ->
            val name = item.optString("name", "")
            if (name.isEmpty()) {
                return@forEachResult
            }
            val parentPath = item.optString("path", "")
            val fullWindowsPath = if (parentPath.isEmpty()) {
                name
            } else {
                "${parentPath.trimEnd('\\')}\\$name"
            }
            if (directChildrenOnly &&
                !fullWindowsPath.equals("$rootPrefix$name", ignoreCase = true)
            ) {
                // Not a direct child (Everything's path: matches whole subtrees).
                return@forEachResult
            }
            if (filesByName.containsKey(name)) {
                return@forEachResult
            }
            filesByName[name] = toFtpFile(item, name)
        }
        return filesByName
    }

    /**
     * Runs [query] against the server and invokes [consumer] for every JSON result object
     * (capped at [MAX_RESULTS]).
     */
    @Throws(IOException::class)
    private fun forEachResult(
        authority: Authority,
        query: String,
        consumer: (JSONObject) -> Unit
    ) {
        val parameters = buildString {
            append("s=")
            append(URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            append("&j=1")
            append("&path_column=1")
            append("&size_column=1")
            append("&date_modified_column=1")
            append("&count=")
            append(MAX_RESULTS)
        }
        val url = URL("http://${authority.host}:${httpPort(authority)}/?$parameters")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            setAuthentication(authority, connection)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Everything HTTP server returned $responseCode")
            }
            val responseBody = connection.inputStream
                .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val results = JSONObject(responseBody).optJSONArray("results") ?: return
            for (index in 0 until results.length()) {
                if (Thread.interrupted()) {
                    throw InterruptedIOException()
                }
                consumer(results.getJSONObject(index))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun toFtpFile(item: JSONObject, name: String): FTPFile =
        FTPFile().apply {
            setName(name)
            val type = item.optString("type", "file")
            setType(
                if (type.equals("folder", ignoreCase = true)) {
                    FTPFile.DIRECTORY_TYPE
                } else {
                    FTPFile.FILE_TYPE
                }
            )
            size = item.optString("size", "").toLongOrNull() ?: 0L
            timestamp = Calendar.getInstance().apply {
                timeInMillis = parseDateModified(item.optString("date_modified", ""))
            }
        }

    private fun directoryFile(name: String): FTPFile =
        FTPFile().apply {
            setName(name)
            setType(FTPFile.DIRECTORY_TYPE)
            size = 0L
            timestamp = Calendar.getInstance()
        }

    /**
     * Best-effort parse of Everything's localized `date_modified` string (e.g.
     * "2026/8/23 22:40"); falls back to the epoch when unrecognized.
     */
    private fun parseDateModified(text: String): Long {
        text.toLongOrNull()?.let { return it }
        if (text.isBlank()) {
            return 0L
        }
        for (pattern in arrayOf(
            "yyyy/M/d H:m:s", "yyyy/M/d H:m", "yyyy-M-d H:m:s", "yyyy-M-d H:m"
        )) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.ROOT)
                val date: Date = formatter.parse(text.trim()) ?: continue
                return date.time
            } catch (e: Exception) {
                // Try the next pattern.
            }
        }
        return 0L
    }

    private fun httpPort(authority: Authority): Int =
        authority.everythingHttpPort.takeIf { it > 0 } ?: 80

    private fun setAuthentication(authority: Authority, connection: HttpURLConnection) {
        val username = authority.username
        if (username.isEmpty() || username == Authority.ANONYMOUS_USERNAME) {
            return
        }
        val password = Client.authenticator.getPassword(authority) ?: ""
        val credentials = Base64.encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
        )
        connection.setRequestProperty("Authorization", "Basic $credentials")
    }

    /**
     * Builds the URL Everything's HTTP server serves [windowsPath] at, matching the web
     * UI: `http://host:port/C:/foo/bar.txt`, with UNC paths escaped as
     * `http://host:port/%5C%5Cserver/share/file.txt`.
     */
    private fun fileUrl(authority: Authority, windowsPath: String): String {
        val normalized = windowsPath.replace('\\', '/')
        val isUnc = normalized.startsWith("//")
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        val encoded = segments.map { segment ->
            URLEncoder.encode(segment, "UTF-8")
                .replace("+", "%20")
                .replace("%3A", ":")
                .replace("%28", "(")
                .replace("%29", ")")
        }.toMutableList()
        if (isUnc && encoded.isNotEmpty()) {
            encoded[0] = "%5C%5C" + encoded[0]
        }
        return "http://${authority.host}:${httpPort(authority)}/" + encoded.joinToString("/")
    }

    @Throws(IOException::class)
    private fun openConnection(path: Client.Path, rangeStart: Long): HttpURLConnection {
        val windowsPath = windowsPathOrNull(path) ?: throw NoSuchFileException(path.toString())
        val url = URL(fileUrl(path.authority, windowsPath))
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        if (rangeStart > 0) {
            connection.setRequestProperty("Range", "bytes=$rangeStart-")
        }
        setAuthentication(path.authority, connection)
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            if (responseCode == 404) {
                throw NoSuchFileException(path.toString())
            }
            throw IOException("Everything HTTP server returned $responseCode")
        }
        return connection
    }

    /**
     * A read-only seekable channel over the Everything HTTP server. Sequential reads
     * stream the current connection; seeking backwards reopens it with a Range request
     * (Everything supports Range), so viewers can seek without downloading everything.
     */
    private class HttpReadonlyChannel(
        private val path: Client.Path
    ) : SeekableByteChannel {

        private var position: Long = 0
        private var size: Long = -1
        private var stream: InputStream? = null
        private var streamStartPosition: Long = -1
        private var closed = false

        @Throws(IOException::class)
        private fun ensureStream(): InputStream {
            if (closed) {
                throw ClosedChannelException()
            }
            var current = stream
            if (current == null || streamStartPosition != position) {
                current?.close()
                val connection = openConnection(path, position)
                if (position == 0L) {
                    val contentLength = connection.contentLengthLong
                    if (contentLength >= 0) {
                        size = contentLength
                    }
                }
                current = connection.inputStream
                stream = current
                streamStartPosition = position
            }
            return current
        }

        @Throws(IOException::class)
        override fun read(destination: ByteBuffer): Int {
            if (closed) {
                throw ClosedChannelException()
            }
            val size = size
            if (size >= 0 && position >= size) {
                return -1
            }
            val inputStream = ensureStream()
            if (!destination.hasRemaining()) {
                return 0
            }
            val buffer = ByteArray(minOf(destination.remaining(), 64 * 1024))
            val bytesRead = inputStream.read(buffer)
            if (bytesRead < 0) {
                return -1
            }
            destination.put(buffer, 0, bytesRead)
            position += bytesRead
            return bytesRead
        }

        @Throws(IOException::class)
        override fun position(): Long = position

        @Throws(IOException::class)
        override fun position(newPosition: Long): SeekableByteChannel {
            if (newPosition < 0) {
                throw IllegalArgumentException("Negative position: $newPosition")
            }
            if (closed) {
                throw ClosedChannelException()
            }
            if (newPosition != position) {
                stream?.close()
                stream = null
                streamStartPosition = -1
                position = newPosition
            }
            return this
        }

        override fun size(): Long {
            if (size < 0) {
                // Force a header read to learn the size.
                ensureStream()
            }
            return size
        }

        override fun write(source: ByteBuffer?): Int = throw NonWritableChannelException()

        override fun truncate(newSize: Long): SeekableByteChannel =
            throw NonWritableChannelException()

        override fun isOpen(): Boolean = !closed

        @Throws(IOException::class)
        override fun close() {
            if (closed) {
                return
            }
            closed = true
            try {
                stream?.close()
            } finally {
                stream = null
            }
        }
    }
}
