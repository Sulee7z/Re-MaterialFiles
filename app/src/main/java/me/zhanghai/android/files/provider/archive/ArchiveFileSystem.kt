/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.ClosedFileSystemException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.StandardOpenOption
import java8.nio.file.WatchService
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.archiver.ArchiveReader
import me.zhanghai.android.files.provider.archive.archiver.ArchiveWriter
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.ByteStringListPathCreator
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.libarchive.ArchiveException
import java.io.IOException
import java.io.InputStream

internal class ArchiveFileSystem(
    private val provider: ArchiveFileSystemProvider,
    val archiveFile: Path
) : FileSystem(), ByteStringListPathCreator, Parcelable {
    val rootDirectory = ArchivePath(this, SEPARATOR_BYTE_STRING)

    init {
        if (!rootDirectory.isAbsolute) {
            throw AssertionError("Root directory $rootDirectory must be absolute")
        }
        if (rootDirectory.nameCount != 0) {
            throw AssertionError("Root directory $rootDirectory must contain no names")
        }
    }

    val defaultDirectory: ArchivePath
        get() = rootDirectory

    private val lock = Any()

    private var isOpen = true

    private var passwords = listOf<String>()

    private var isRefreshNeeded = true

    private var entries: Map<Path, ReadArchive.Entry>? = null

    private var tree: Map<Path, List<Path>>? = null

    /**
     * Overlay of unsaved edits made while browsing this archive. Reads are merged with it and
     * writes are recorded in it (see ArchiveEditSession), until the user saves, which flushes
     * the overlay back into the archive file.
     */
    val editSession = ArchiveEditSession()

    /** Whether edits have been recorded since the last save/refresh. */
    val hasUnsavedChanges: Boolean
        get() = synchronized(lock) { editSession.isDirty }

    // Overlay helpers used by provider write operations. The caller must hold the lock.
    fun recordModification(path: Path, content: ByteArray) {
        synchronized(lock) {
            ensureEntriesLocked(path)
            editSession.modifiedEntries[path] = content.copyOf()
        }
    }

    fun recordDeletion(path: Path) {
        synchronized(lock) {
            ensureEntriesLocked(path)
            editSession.deletedEntries.add(path)
        }
    }

    fun recordCreation(path: Path, content: ByteArray?) {
        synchronized(lock) {
            ensureEntriesLocked(path)
            editSession.createdEntries[path] = content?.copyOf()
        }
    }

    fun recordRename(source: Path, target: Path) {
        synchronized(lock) {
            ensureEntriesLocked(source)
            editSession.renamedEntries[source] = target
        }
    }

    /** Marks all saved changes flushed and rebuilds the entry views from the refreshed archive. */
    fun refreshAfterSave() {
        synchronized(lock) {
            editSession.clear()
            isRefreshNeeded = true
        }
    }

    /**
     * Rewrites the archive file with the edit overlay applied: deleted entries dropped,
     * renamed entries moved, modified entries replaced by their new content and created
     * entries appended. The rewrite is written to a temporary file next to the archive and
     * atomically replaces the original, so a failure leaves the original untouched.
     */
    @Throws(IOException::class)
    fun save(successListener: ((Boolean) -> Unit)? = null) {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            if (!editSession.isDirty) {
                successListener?.invoke(true)
                return
            }
            ensureEntriesLocked(rootDirectory)
            val edit = editSession
            val (format, filter) = formatAndFilterFor()
            val tempFile = archiveFile.resolveSibling(
                archiveFile.fileName.toString() + ".save.tmp"
            )
            val channel = try {
                tempFile.newByteChannel(
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            } catch (e: IOException) {
                successListener?.invoke(false)
                throw e
            }
            var successful = false
            try {
                channel.use {
                    ArchiveWriter(channel, format, filter, passwords.firstOrNull()).use { writer ->
                        val originalEntries = entries!!.entries.sortedBy { it.key.toString() }
                        for ((path, entry) in originalEntries) {
                            if (edit.deletedEntries.any { path.startsWith(it) }) {
                                continue
                            }
                            val targetName = edit.renamedEntries[path]?.toString() ?: path.toString()
                            val modified = edit.modifiedEntries[path]
                            if (modified != null) {
                                writer.writeBytes(
                                    targetName, modified, entry.lastModifiedTime,
                                    isDirectory = entry.isDirectory,
                                    symbolicLinkTarget = entry.symbolicLinkTarget
                                )
                            } else {
                                writeOriginalEntryWildcard(writer, targetName, path, entry)
                            }
                        }
                        // Append created entries (files and directories) so nested paths are
                        // written after their parents.
                        val createdPaths = edit.createdEntries.keys
                            .filterNot { edit.deletedEntries.contains(it) }
                            .filterNot { entries!!.containsKey(it) }
                            .sortedBy { it.toString() }
                        for (path in createdPaths) {
                            val content = edit.createdEntries[path]
                            writer.writeBytes(
                                path.toString(), content ?: ByteArray(0), null,
                                isDirectory = content == null
                            )
                        }
                    }
                }
                // Atomically replace the original archive.
                tempFile.moveTo(
                    archiveFile, LinkOption.NOFOLLOW_LINKS,
                    java8.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                refreshAfterSave()
                successful = true
            } finally {
                if (!successful) {
                    try {
                        tempFile.deleteIfExists()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
            successListener?.invoke(successful)
        }
    }

    @Throws(IOException::class)
    private fun writeOriginalEntryWildcard(
        writer: me.zhanghai.android.files.provider.archive.archiver.ArchiveWriter,
        targetName: String,
        path: Path,
        entry: ReadArchive.Entry
    ) {
        when (entry.type) {
            PosixFileType.DIRECTORY -> writer.writeBytes(
                targetName, ByteArray(0), entry.lastModifiedTime, isDirectory = true
            )
            PosixFileType.SYMBOLIC_LINK -> writer.writeBytes(
                targetName, ByteArray(0), entry.lastModifiedTime,
                symbolicLinkTarget = entry.symbolicLinkTarget.orEmpty()
            )
            else -> {
                val byteArray = this.newInputStream(path).use { it.readBytes() }
                if (byteArray.size > MAX_SAVE_ENTRY_SIZE) {
                    throw IOException("Entry too large to save in memory: $path")
                }
                writer.writeBytes(targetName, byteArray, entry.lastModifiedTime)
            }
        }
    }

    private fun formatAndFilterFor(): Pair<Int, Int> {
        val name = archiveFile.fileName.toString().lowercase()
        return when {
            name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".apk") ||
                name.endsWith(".mtz") ->
                me.zhanghai.android.libarchive.Archive.FORMAT_ZIP to
                    me.zhanghai.android.libarchive.Archive.FILTER_NONE
            name.endsWith(".7z") ->
                me.zhanghai.android.libarchive.Archive.FORMAT_7ZIP to
                    me.zhanghai.android.libarchive.Archive.FILTER_NONE
            name.endsWith(".gz") ->
                me.zhanghai.android.libarchive.Archive.FORMAT_TAR to
                    me.zhanghai.android.libarchive.Archive.FILTER_GZIP
            else ->
                me.zhanghai.android.libarchive.Archive.FORMAT_TAR to
                    me.zhanghai.android.libarchive.Archive.FILTER_NONE
        }
    }

    /** Overlay that tells whether a path (or an ancestor) was deleted. */
    private fun isDeletedWithOverlay(path: Path): Boolean =
        editSession.deletedEntries.any { path.startsWith(it) }

    /** Resolve a path against the overlay: returns the entry to show for [path]. */
    private fun resolveOverlayEntry(path: Path): ReadArchive.Entry? {
        val edit = editSession
        if (edit.deletedEntries.any { path.startsWith(it) }) {
            return null
        }
        val renamedSource = edit.renamedEntries.entries.firstOrNull { it.value == path }
        if (renamedSource != null) {
            return entries!![renamedSource.key] ?: return null
        }
        if (edit.createdEntries.containsKey(path)) {
            val content = edit.createdEntries[path]
            return if (content == null) {
                createVirtualDirectoryEntry(path)
            } else {
                createVirtualRegularFileEntry(path, content.size.toLong())
            }
        }
        if (edit.modifiedEntries.containsKey(path)) {
            val content = edit.modifiedEntries[path]!!
            val existing = entries!![path]
            if (existing != null) {
                return existing
            }
            return createVirtualRegularFileEntry(path, content.size.toLong())
        }
        return null
    }

    private fun createVirtualDirectoryEntry(path: Path): ReadArchive.Entry =
        ReadArchive.Entry(
            path.toString(), false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
            PosixFileMode.DIRECTORY_DEFAULT, null
        )

    private fun createVirtualRegularFileEntry(path: Path, size: Long): ReadArchive.Entry =
        ReadArchive.Entry(
            path.toString(), false, null, null, null, PosixFileType.REGULAR_FILE, size,
            null, null, PosixFileMode.FILE_DEFAULT, null
        )

    @Throws(IOException::class)
    fun getEntry(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            ensureEntriesLocked(path)
            resolveOverlayEntry(path) ?: getEntryLocked(path)
        }

    @Throws(IOException::class)
    private fun getEntryLocked(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            entries!![path] ?: throw NoSuchFileException(path.toString())
        }

    @Throws(IOException::class)
    fun newInputStream(file: Path): InputStream =
        synchronized(lock) {
            ensureEntriesLocked(file)
            val modified = editSession.modifiedEntries[file]
            if (modified != null) {
                return java.io.ByteArrayInputStream(modified)
            }
            val created = editSession.createdEntries[file]
            if (created != null) {
                val bytes = created
                if (bytes == null) {
                    throw IsDirectoryException(file.toString())
                }
                return java.io.ByteArrayInputStream(bytes)
            }
            val entry = getEntry(file)
            if (entry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            val inputStream = try {
                ArchiveReader.newInputStream(archiveFile, passwords, entry)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            } ?: throw NoSuchFileException(file.toString())
            ArchiveExceptionInputStream(inputStream, file)
        }

    @Throws(IOException::class)
    fun getDirectoryChildren(directory: Path): List<Path> =
        synchronized(lock) {
            ensureEntriesLocked(directory)
            val entry = getEntry(directory)
            if (!entry.isDirectory) {
                throw NotDirectoryException(directory.toString())
            }
            val edit = editSession
            val result = linkedSetOf<Path>()
            val originalChildren = tree!![directory]
            if (originalChildren != null) {
                for (child in originalChildren) {
                    val deleted = edit.deletedEntries.any { child.startsWith(it) }
                    val renamedSource = edit.renamedEntries.entries.firstOrNull {
                        it.key == child || child.startsWith(it.key)
                    }
                    if (deleted || renamedSource != null) {
                        continue
                    }
                    result.add(child)
                }
            }
            // Renamed entries now living directly under this directory.
            for (rename in edit.renamedEntries) {
                val (_, targetName) = rename
                if (targetName.parent == directory) {
                    result.add(targetName)
                }
            }
            // New entries created directly under this directory.
            for (created in edit.createdEntries.keys) {
                if (created.parent == directory) {
                    result.add(created)
                }
            }
            return result.toList()
        }

    @Throws(IOException::class)
    fun newWritableChannel(file: ArchivePath): java8.nio.channels.SeekableByteChannel =
        synchronized(lock) {
            ensureEntriesLocked(file)
            val existing = runCatching { getEntry(file) }.getOrNull()
            if (existing?.isDirectory == true) {
                throw IsDirectoryException(file.toString())
            }
            object : java8.nio.channels.SeekableByteChannel {
                private var position = 0L
                private var closed = false
                private val writeBuffer = java.io.ByteArrayOutputStream()

                override fun read(dst: java.nio.ByteBuffer): Int {
                    throw java.nio.channels.NonReadableChannelException()
                }

                override fun write(src: java.nio.ByteBuffer): Int {
                    checkOpen()
                    val bytes = ByteArray(src.remaining())
                    src.get(bytes)
                    writeBuffer.write(bytes)
                    position += bytes.size
                    return bytes.size
                }

                override fun position(): Long {
                    checkOpen()
                    return position
                }

                override fun position(newPosition: Long): java8.nio.channels.SeekableByteChannel {
                    checkOpen()
                    position = newPosition
                    return this
                }

                override fun size(): Long {
                    checkOpen()
                    return writeBuffer.size().toLong()
                }

                override fun truncate(size: Long): java8.nio.channels.SeekableByteChannel {
                    checkOpen()
                    val current = writeBuffer.toByteArray()
                    writeBuffer.reset()
                    writeBuffer.write(current, 0, minOf(size.toInt(), current.size))
                    position = minOf(position, size)
                    return this
                }

                override fun isOpen(): Boolean = !closed

                override fun close() {
                    if (closed) {
                        return
                    }
                    closed = true
                    val bytes = writeBuffer.toByteArray()
                    if (existing != null) {
                        recordModification(file, bytes)
                    } else {
                        recordCreation(file, bytes)
                    }
                }

                private fun checkOpen() {
                    if (closed) {
                        throw java.nio.channels.ClosedChannelException()
                    }
                }
            }
        }

    @Throws(IOException::class)
    fun readSymbolicLink(link: Path): String =
        synchronized(lock) {
            ensureEntriesLocked(link)
            val entry = getEntryLocked(link)
            if (!entry.isSymbolicLink) {
                throw NotLinkException(link.toString())
            }
            entry.symbolicLinkTarget.orEmpty()
        }

    fun addPassword(password: String) {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            passwords += password
        }
    }

    fun refresh() {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            isRefreshNeeded = true
        }
    }

    @Throws(IOException::class)
    private fun ensureEntriesLocked(file: Path) {
        if (!isOpen) {
            throw ClosedFileSystemException()
        }
        if (isRefreshNeeded) {
            val entriesAndTree = try {
                ArchiveReader.readEntries(archiveFile, passwords, rootDirectory)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            }
            entries = entriesAndTree.first
            tree = entriesAndTree.second
            isRefreshNeeded = false
        }
    }

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            provider.removeFileSystem(this)
            isRefreshNeeded = false
            entries = null
            tree = null
            isOpen = false
        }
    }

    override fun isOpen(): Boolean = synchronized(lock) { isOpen }

    override fun isReadOnly(): Boolean = false

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)

    override fun getFileStores(): Iterable<FileStore> {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun supportedFileAttributeViews(): Set<String> =
        ArchiveFileAttributeView.SUPPORTED_NAMES

    override fun getPath(first: String, vararg more: String): ArchivePath {
        val path = ByteStringBuilder(first.toByteString())
            .apply { more.forEach { append(SEPARATOR).append(it.toByteString()) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPath(first: ByteString, vararg more: ByteString): ArchivePath {
        val path = ByteStringBuilder(first)
            .apply { more.forEach { append(SEPARATOR).append(it) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        throw UnsupportedOperationException()
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newWatchService(): WatchService {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as ArchiveFileSystem
        return archiveFile == other.archiveFile
    }

    override fun hashCode(): Int = archiveFile.hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(archiveFile as Parcelable, flags)
    }

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        private val SEPARATOR_BYTE_STRING = SEPARATOR.toByteString()
        private const val SEPARATOR_STRING = SEPARATOR.toInt().toChar().toString()

        /** Largest archive entry buffered into memory while saving an archive (64 MiB). */
        private const val MAX_SAVE_ENTRY_SIZE = 64 * 1024 * 1024

        @JvmField
        val CREATOR = object : Parcelable.Creator<ArchiveFileSystem> {
            override fun createFromParcel(source: Parcel): ArchiveFileSystem {
                val archiveFile = source.readParcelable<Parcelable>(Path::class.java.classLoader)
                    as Path
                return ArchiveFileSystemProvider.getOrNewFileSystem(archiveFile)
            }

            override fun newArray(size: Int): Array<ArchiveFileSystem?> = arrayOfNulls(size)
        }
    }
}
