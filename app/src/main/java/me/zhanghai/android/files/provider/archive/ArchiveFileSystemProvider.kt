/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.ProviderMismatchException
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.FileSystemCache
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.decodedQueryByteString
import me.zhanghai.android.files.provider.common.isSameFile
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toOpenOptions
import java.io.IOException
import java.io.InputStream
import java.net.URI

object ArchiveFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
private const val SCHEME = "archive"

/** Largest single archive entry we will buffer into memory for direct reading (64 MiB). */
private const val MAX_READ_SIZE = 64 * 1024 * 1024

    private val fileSystems = FileSystemCache<Path, ArchiveFileSystem>()

    override fun getScheme(): String = SCHEME

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        return fileSystems.create(archiveFile) { newFileSystem(archiveFile) }
    }

    override fun newFileSystem(file: Path, env: Map<String, *>): FileSystem = newFileSystem(file)

    internal fun getOrNewFileSystem(archiveFile: Path): ArchiveFileSystem =
        fileSystems.getOrCreate(archiveFile) { newFileSystem(archiveFile) }

    private fun newFileSystem(archiveFile: Path): ArchiveFileSystem =
        ArchiveFileSystem(this, archiveFile)

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        return fileSystems[archiveFile]
    }

    internal fun removeFileSystem(fileSystem: ArchiveFileSystem) {
        fileSystems.remove(fileSystem.archiveFile, fileSystem)
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        val path = uri.decodedQueryByteString
            ?: throw IllegalArgumentException("URI must have a query")
        return getOrNewFileSystem(archiveFile).getPath(path)
    }

    private fun URI.requireSameScheme() {
        val scheme = scheme
        require(scheme == SCHEME) { "URI scheme $scheme must be $SCHEME" }
    }

    private val URI.archiveFile: Path
        get() {
            val path = decodedPathByteString
                ?: throw IllegalArgumentException("URI must have a path")
            // Drop the first character which is always a slash.
            val archiveUri = URI.create(path.toString().drop(1))
            return Paths.get(archiveUri)
        }

    @Throws(IOException::class)
    override fun newInputStream(file: Path, vararg options: OpenOption): InputStream {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        options.toOpenOptions().checkForArchive()
        return file.fileSystem.newInputStream(file)
    }

    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        options.toOpenOptions().checkForArchive()
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        if (options.toOpenOptions().write) {
            // Writes to an archive entry are buffered and recorded in the file system's
            // edit overlay as soon as the channel is closed. Nothing touches the archive
            // file itself until the user saves.
            return file.fileSystem.newWritableChannel(file)
        }
        options.toOpenOptions().checkForArchive()
        // Read the whole entry into memory and expose it as a read-only channel. Archive
        // entries (files inside zip/7z/...) are typically small, and this is what makes
        // opening files inside an APK/zip (e.g. AndroidManifest.xml via the text viewer)
        // work at all. Uses the existing newInputStream path so passwords/encrypted
        // archives keep working. Entries larger than MAX_READ_SIZE are rejected instead
        // of being buffered into memory (a big .so inside an APK would OOM the process).
        val bytes = file.fileSystem.newInputStream(file).use {
            val buffer = it.readBytes()
            if (buffer.size > MAX_READ_SIZE) {
                throw java.io.IOException("File too large to open in archive: ${file}")
            }
            buffer
        }
        return object : SeekableByteChannel {
            private var position = 0L
            private var closed = false

            override fun read(dst: java.nio.ByteBuffer): Int {
                checkOpen()
                if (position >= bytes.size) {
                    return -1
                }
                val toRead = minOf(dst.remaining().toLong(), bytes.size - position).toInt()
                dst.put(bytes, position.toInt(), toRead)
                position += toRead
                return toRead
            }

            override fun write(src: java.nio.ByteBuffer): Int {
                throw java.nio.channels.NonWritableChannelException()
            }

            override fun position(): Long {
                checkOpen()
                return position
            }

            override fun position(newPosition: Long): SeekableByteChannel {
                checkOpen()
                position = newPosition
                return this
            }

            override fun size(): Long {
                checkOpen()
                return bytes.size.toLong()
            }

            override fun truncate(size: Long): SeekableByteChannel {
                throw java.nio.channels.NonWritableChannelException()
            }

            override fun isOpen(): Boolean = !closed

            override fun close() {
                closed = true
            }

            private fun checkOpen() {
                if (closed) {
                    throw java.nio.channels.ClosedChannelException()
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        directory as? ArchivePath ?: throw ProviderMismatchException(directory.toString())
        val children = directory.fileSystem.getDirectoryChildren(directory)
        return PathListDirectoryStream(children, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        directory as? ArchivePath ?: throw ProviderMismatchException(directory.toString())
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        directory.fileSystem.recordCreation(directory, null)
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        link as? ArchivePath ?: throw ProviderMismatchException(link.toString())
        when (target) {
            is ArchivePath, is ByteStringPath -> {}
            else -> throw ProviderMismatchException(target.toString())
        }
        throw ReadOnlyFileSystemException(link.toString(), target.toString(), null)
    }

    @Throws(IOException::class)
    override fun createLink(link: Path, existing: Path) {
        link as? ArchivePath ?: throw ProviderMismatchException(link.toString())
        existing as? ArchivePath ?: throw ProviderMismatchException(existing.toString())
        throw ReadOnlyFileSystemException(link.toString(), existing.toString(), null)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        // Ensure the entry still exists before recording the deletion (respecting the
        // overlay, e.g. a just-created entry can also be deleted).
        path.fileSystem.getEntry(path)
        path.fileSystem.recordDeletion(path)
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(link: Path): Path {
        link as? ArchivePath ?: throw ProviderMismatchException(link.toString())
        val target = link.fileSystem.readSymbolicLink(link)
        return ByteStringPath(target.toByteString())
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        source as? ArchivePath ?: throw ProviderMismatchException(source.toString())
        target as? ArchivePath ?: throw ProviderMismatchException(target.toString())
        throw ReadOnlyFileSystemException(source.toString(), target.toString(), null)
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? ArchivePath ?: throw ProviderMismatchException(source.toString())
        target as? ArchivePath ?: throw ProviderMismatchException(target.toString())
        val sourceFileSystem = source.fileSystem
        if (sourceFileSystem !== target.fileSystem) {
            throw IllegalStateException(
                "Moving between different archive file systems is unsupported"
            )
        }
        sourceFileSystem.recordRename(source, target)
    }

    @Throws(IOException::class)
    override fun isSameFile(path: Path, path2: Path): Boolean {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        if (path == path2) {
            return true
        }
        if (path2 !is ArchivePath) {
            return false
        }
        val fileSystem = path.fileSystem
        if (!fileSystem.archiveFile.isSameFile(path2.fileSystem.archiveFile)) {
            return false
        }
        return path == fileSystem.getPath(path2.toString())
    }

    override fun isHidden(path: Path): Boolean {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        return false
    }

    override fun getFileStore(path: Path): FileStore {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        val archiveFile = path.fileSystem.archiveFile
        return ArchiveFileStore(archiveFile)
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        val accessModes = modes.toAccessModes()
        path.fileSystem.getEntry(path)
        if (accessModes.write || accessModes.execute) {
            throw AccessDeniedException(path.toString())
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(ArchiveFileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        if (!type.isAssignableFrom(ArchiveFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path).readAttributes() as A
    }

    private fun getFileAttributeView(path: ArchivePath): ArchiveFileAttributeView =
        ArchiveFileAttributeView(path)

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? ArchivePath ?: throw ProviderMismatchException(directory.toString())
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }
}
