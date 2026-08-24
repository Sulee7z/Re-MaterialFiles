/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import android.net.Uri
import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotLinkException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.DelegateSchemeFileSystemProvider
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.WatchServicePathObservable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.decodedQueryByteString
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toCopyOptions
import me.zhanghai.android.files.provider.common.toLinkOptions
import me.zhanghai.android.files.provider.common.toOpenOptions
import me.zhanghai.android.files.provider.ftp.client.Authority
import me.zhanghai.android.files.provider.ftp.client.Client
import me.zhanghai.android.files.provider.ftp.client.EverythingHttpClient
import me.zhanghai.android.files.provider.ftp.client.Mode
import me.zhanghai.android.files.provider.ftp.client.Protocol
import me.zhanghai.android.files.provider.ftp.client.mapEverythingPathToRemotePath
import me.zhanghai.android.files.provider.ftp.client.mapRemotePathToEverythingWindowsPath
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

object FtpFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private val HIDDEN_FILE_NAME_PREFIX = ".".toByteString()

    private val fileSystems = mutableMapOf<Authority, FtpFileSystem>()

    private val lock = Any()

    override fun getScheme(): String = Protocol.FTP.scheme

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val authority = uri.ftpAuthority
        synchronized(lock) {
            if (fileSystems[authority] != null) {
                throw FileSystemAlreadyExistsException(authority.toString())
            }
            return newFileSystemLocked(authority)
        }
    }

    internal fun getOrNewFileSystem(authority: Authority): FtpFileSystem =
        synchronized(lock) { fileSystems[authority] ?: newFileSystemLocked(authority) }

    private fun newFileSystemLocked(authority: Authority): FtpFileSystem {
        val fileSystem = FtpFileSystem(this, authority)
        fileSystems[authority] = fileSystem
        return fileSystem
    }

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val authority = uri.ftpAuthority
        return synchronized(lock) { fileSystems[authority] }
            ?: throw FileSystemNotFoundException(authority.toString())
    }

    internal fun removeFileSystem(fileSystem: FtpFileSystem) {
        val authority = fileSystem.authority
        synchronized(lock) { fileSystems.remove(authority) }
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val authority = uri.ftpAuthority
        val path = uri.decodedPathByteString
            ?: throw IllegalArgumentException("URI must have a path")
        return getOrNewFileSystem(authority).getPath(path)
    }

    private fun URI.requireSameScheme() {
        val scheme = scheme
        require(scheme in Protocol.SCHEMES) { "URI scheme $scheme must be in ${Protocol.SCHEMES}" }
    }

    private val URI.ftpAuthority: Authority
        get() {
            val protocol = Protocol.fromScheme(scheme)
            val port = if (port != -1) port else protocol.defaultPort
            val username = userInfo.orEmpty()
            val queryUri = decodedQueryByteString?.toString()?.let { Uri.parse(it) }
            val mode = queryUri?.getQueryParameter(FtpPath.QUERY_PARAMETER_MODE)
                ?.let { mode -> Mode.entries.first { it.name.equals(mode, true) } }
                ?: Authority.DEFAULT_MODE
            val encoding = queryUri?.getQueryParameter(FtpPath.QUERY_PARAMETER_ENCODING)
                ?: Authority.DEFAULT_ENCODING
            val everythingWindowsRoot = queryUri
                ?.getQueryParameter(FtpPath.QUERY_PARAMETER_EVERYTHING_WINDOWS_ROOT)
                .orEmpty()
            val everythingHttpPort = queryUri
                ?.getQueryParameter(FtpPath.QUERY_PARAMETER_EVERYTHING_HTTP_PORT)
                ?.toIntOrNull() ?: 0
            return Authority(
                protocol, host, port, username, mode, encoding,
                everythingWindowsRoot, everythingHttpPort
            )
        }

    @Throws(IOException::class)
    override fun newInputStream(file: Path, vararg options: OpenOption): InputStream {
        file as? FtpPath ?: throw ProviderMismatchException(file.toString())
        val openOptions = options.toOpenOptions()
        openOptions.checkForFtp()
        if (openOptions.write) {
            throw UnsupportedOperationException(StandardOpenOption.WRITE.toString())
        }
        if (openOptions.append) {
            throw UnsupportedOperationException(StandardOpenOption.APPEND.toString())
        }
        if (openOptions.truncateExisting) {
            throw UnsupportedOperationException(StandardOpenOption.TRUNCATE_EXISTING.toString())
        }
        if (openOptions.create || openOptions.createNew || openOptions.noFollowLinks) {
            val fileFile = try {
                Client.listFileOrNull(file, true)
            } catch (e: IOException) {
                throw e.toFileSystemExceptionForFtp(file.toString())
            }
            if (openOptions.createNew && fileFile != null) {
                throw FileAlreadyExistsException(file.toString())
            }
            if (openOptions.noFollowLinks && fileFile != null && fileFile.isSymbolicLink) {
                throw FileSystemException(
                    file.toString(), null, "File is a symbolic link: $fileFile"
                )
            }
            if ((openOptions.create || openOptions.createNew) && fileFile == null) {
                try {
                    Client.createFile(file)
                } catch (e: IOException) {
                    throw e.toFileSystemExceptionForFtp(file.toString())
                }
            }
        }
        try {
            return Client.retrieveFile(file)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(file.toString())
        }
    }

    @Throws(IOException::class)
    override fun newOutputStream(file: Path, vararg options: OpenOption): OutputStream {
        file as? FtpPath ?: throw ProviderMismatchException(file.toString())
        val optionsSet = mutableSetOf(*options)
        if (optionsSet.isEmpty()) {
            optionsSet += StandardOpenOption.CREATE
            optionsSet += StandardOpenOption.TRUNCATE_EXISTING
        }
        optionsSet += StandardOpenOption.WRITE
        val openOptions = optionsSet.toOpenOptions()
        openOptions.checkForFtp()
        if (!openOptions.truncateExisting && !openOptions.createNew) {
            throw UnsupportedOperationException("Missing ${StandardOpenOption.TRUNCATE_EXISTING}")
        }
        val fileFile = try {
            Client.listFileOrNull(file, true)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(file.toString())
        }
        if (openOptions.createNew && fileFile != null) {
            throw FileAlreadyExistsException(file.toString())
        }
        if (!(openOptions.create || openOptions.createNew) && fileFile == null) {
            throw NoSuchFileException(file.toString())
        }
        try {
            return Client.storeFile(file)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(file.toString())
        }
    }

    @Throws(IOException::class)
    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        file as? FtpPath ?: throw ProviderMismatchException(file.toString())
        options.toOpenOptions().checkForFtp()
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        file as? FtpPath ?: throw ProviderMismatchException(file.toString())
        val openOptions = options.toOpenOptions()
        openOptions.checkForFtp()
        if (openOptions.write && !openOptions.truncateExisting) {
            throw UnsupportedOperationException("Missing ${StandardOpenOption.TRUNCATE_EXISTING}")
        }
        var fileExists = false
        if (openOptions.write || openOptions.create || openOptions.createNew ||
            openOptions.noFollowLinks) {
            val fileFile = try {
                Client.listFileOrNull(file, true)
            } catch (e: IOException) {
                throw e.toFileSystemExceptionForFtp(file.toString())
            }
            fileExists = fileFile != null
            if (openOptions.createNew && fileFile != null) {
                throw FileAlreadyExistsException(file.toString())
            }
            if (openOptions.noFollowLinks && fileFile != null && fileFile.isSymbolicLink) {
                throw FileSystemException(
                    file.toString(), null, "File is a symbolic link: $fileFile"
                )
            }
            if (fileFile == null) {
                if (!(openOptions.create || openOptions.createNew)) {
                    throw NoSuchFileException(file.toString())
                }
                try {
                    Client.createFile(file)
                } catch (e: IOException) {
                    throw e.toFileSystemExceptionForFtp(file.toString())
                }
            }
        }
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        try {
            // Truncate is only needed when the file already exists: FTP's REST+STOR does not
            // truncate the tail of an existing file by itself.
            return Client.openByteChannel(
                file, openOptions.append, openOptions.truncateExisting && fileExists
            )
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(file.toString())
        }
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        directory as? FtpPath ?: throw ProviderMismatchException(directory.toString())
        val paths = try {
            @Suppress("UNCHECKED_CAST")
            Client.listDirectory(directory) as List<Path>
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(directory.toString())
        }
        return PathListDirectoryStream(paths, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        directory as? FtpPath ?: throw ProviderMismatchException(directory.toString())
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        try {
            Client.createDirectory(directory)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(directory.toString())
        }
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        link as? FtpPath ?: throw ProviderMismatchException(link.toString())
        when (target) {
            is FtpPath, is ByteStringPath -> {}
            else -> throw ProviderMismatchException(target.toString())
        }
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    override fun createLink(link: Path, existing: Path) {
        link as? FtpPath ?: throw ProviderMismatchException(link.toString())
        existing as? FtpPath ?: throw ProviderMismatchException(existing.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        try {
            Client.delete(path)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(path.toString())
        }
    }

    override fun readSymbolicLink(link: Path): Path {
        link as? FtpPath ?: throw ProviderMismatchException(link.toString())
        val linkFile = try {
            Client.listFile(link, true)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(link.toString())
        }
        if (!linkFile.isSymbolicLink) {
            throw NotLinkException(link.toString(), null, linkFile.toString())
        }
        val target = linkFile.link ?: throw FileSystemException(
            link.toString(), null, "FTPFile.getLink() returned null: $linkFile"
        )
        return ByteStringPath(target.toByteString())
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        source as? FtpPath ?: throw ProviderMismatchException(source.toString())
        target as? FtpPath ?: throw ProviderMismatchException(target.toString())
        val copyOptions = options.toCopyOptions()
        FtpCopyMove.copy(source, target, copyOptions)
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? FtpPath ?: throw ProviderMismatchException(source.toString())
        target as? FtpPath ?: throw ProviderMismatchException(target.toString())
        val copyOptions = options.toCopyOptions()
        FtpCopyMove.move(source, target, copyOptions)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        return path == path2
    }

    override fun isHidden(path: Path): Boolean {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        val fileName = path.fileNameByteString ?: return false
        return fileName.startsWith(HIDDEN_FILE_NAME_PREFIX)
    }

    override fun getFileStore(path: Path): FileStore {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        val accessModes = modes.toAccessModes()
        if (accessModes.write) {
            throw UnsupportedOperationException(AccessMode.WRITE.toString())
        }
        if (accessModes.execute) {
            throw UnsupportedOperationException(AccessMode.EXECUTE.toString())
        }
        // Assume the file can be read if it can be listed.
        try {
            Client.listFile(path, false)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(path.toString())
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, *options) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(FtpFileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        if (!type.isAssignableFrom(BasicFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, *options).readAttributes() as A
    }

    private fun getFileAttributeView(path: Path, vararg options: LinkOption): FtpFileAttributeView {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        val linkOptions = options.toLinkOptions()
        return FtpFileAttributeView(path, linkOptions.noFollowLinks)
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        path as? FtpPath ?: throw ProviderMismatchException(path.toString())
        return WatchServicePathObservable(path, intervalMillis)
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? FtpPath ?: throw ProviderMismatchException(directory.toString())
        val authority = directory.authority
        val windowsRoot = authority.everythingWindowsRoot
        // The Everything HTTP server JSON API is used for search (the ETP/FTP QUERY flow
        // turned out to be unreliable against real servers): it is the same API the web UI
        // and the EverythingDroid client use, and is confirmed to work.
        if (windowsRoot.isNotEmpty() && authority.everythingHttpPort != 0) {
            try {
                val windowsScopePath =
                    mapRemotePathToEverythingWindowsPath(directory.remotePath, windowsRoot)
                val scopedQuery = if (!windowsScopePath.isNullOrEmpty()) {
                    "path:\"$windowsScopePath\" $query"
                } else {
                    query
                }
                EverythingHttpClient.searchEverything(
                    authority, scopedQuery, intervalMillis
                ) { windowsPaths ->
                    // The server filters by the search terms, but re-filter here so even a
                    // quirky server cannot leak non-matches: every space-separated term
                    // must appear in the entry name (Everything's default AND semantics,
                    // mirroring what the tree-walk fallback guarantees).
                    val terms = query.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
                    val paths = windowsPaths.mapNotNull { windowsPath ->
                        val remotePath =
                            mapEverythingPathToRemotePath(windowsPath, windowsRoot)
                                ?: return@mapNotNull null
                        // Everything's `path:` is a substring match, so re-filter with a
                        // boundary check: only this directory and its descendants count.
                        val directoryRemotePath = directory.remotePath
                        val inScope = if (directoryRemotePath == "/") {
                            true
                        } else {
                            remotePath == directoryRemotePath ||
                                remotePath.startsWith("$directoryRemotePath/")
                        }
                        if (!inScope) {
                            return@mapNotNull null
                        }
                        val relative = remotePath.removePrefix(directoryRemotePath)
                            .trimStart('/')
                        val childPath = if (relative.isEmpty()) {
                            directory
                        } else {
                            directory.resolve(relative)
                        }
                        if (terms.isNotEmpty()) {
                            val name = childPath.fileName?.toString() ?: relative
                            val allTermsMatch = terms.all { term ->
                                name.contains(term, ignoreCase = true)
                            }
                            if (!allTermsMatch) {
                                return@mapNotNull null
                            }
                        }
                        childPath
                    }
                    if (paths.isNotEmpty()) {
                        listener(paths)
                    }
                }
                return
            } catch (e: IOException) {
                e.printStackTrace()
                // Fall back to the recursive walk.
            }
        }
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }
}

val FtpsFileSystemProvider =
    DelegateSchemeFileSystemProvider(Protocol.FTPS.scheme, FtpFileSystemProvider)

val FtpesFileSystemProvider =
    DelegateSchemeFileSystemProvider(Protocol.FTPES.scheme, FtpFileSystemProvider)
