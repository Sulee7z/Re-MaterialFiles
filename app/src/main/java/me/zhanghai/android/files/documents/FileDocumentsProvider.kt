/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread
import android.os.Environment
import android.os.storage.StorageManager
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.stateCompat
import me.zhanghai.android.files.compat.storageVolumesCompat

/**
 * Exposes this app's file system to the system file picker (DocumentsUI) and other SAF
 * clients. Document IDs are absolute POSIX paths. Roots are the mounted storage volumes
 * plus the device "/" root when it is readable — restricted paths (e.g. /data) are
 * served through the app's root/Shizuku file service, so other apps can pick files from
 * them via this provider.
 *
 * Android never allows a third-party app to REPLACE the system picker for
 * ACTION_OPEN_DOCUMENT/CREATE_DOCUMENT (those always open DocumentsUI, where this
 * provider appears as a root in the side menu). Legacy ACTION_GET_CONTENT resolvers can
 * offer this app directly (declared on FileListActivity).
 */
class FileDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    // ---------------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val context = context!!
        val storageManager = context.getSystemService(StorageManager::class.java)
        for (volume in storageManager.storageVolumesCompat) {
            if (volume.stateCompat != Environment.MEDIA_MOUNTED) {
                continue
            }
            val directory = volume.directoryCompat ?: continue
            val path = directory.absolutePath.trimEnd('/')
            val title = if (android.os.Build.VERSION.SDK_INT >= 30) {
                volume.getDescription(context)
            } else {
                volumeTitleFromPath(directory.absolutePath)
            }
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID_PREFIX + path)
                .add(Root.COLUMN_ICON, R.mipmap.launcher_icon)
                .add(Root.COLUMN_TITLE, title)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_DOCUMENT_ID, path)
                .add(Root.COLUMN_AVAILABLE_BYTES, directory.usableSpace)
        }
        if (isDirectoryReadable(Paths.get("/"))) {
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID_DEVICE_ROOT)
                .add(Root.COLUMN_ICON, R.mipmap.launcher_icon)
                .add(Root.COLUMN_TITLE, context.getString(R.string.documents_provider_root_title))
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_DOCUMENT_ID, "/")
        }
        return cursor
    }

    private fun volumeTitleFromPath(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    private fun isDirectoryReadable(path: Path): Boolean =
        try {
            Files.newDirectoryStream(path).use { true }
        } catch (e: Exception) {
            false
        }

    // ------------------------------------------------------------------ documents

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        includeDocument(cursor, pathForDocumentId(documentId))
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val parent = pathForDocumentId(parentDocumentId)
        val entries = try {
            Files.newDirectoryStream(parent).use { it.toList() }
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to list $parent: ${e.message}")
        }
        // Directories first, then by name (case-insensitive), matching the in-app list.
        val sorted = entries.sortedWith(
            compareBy<Path> { !Files.isDirectory(it) }
                .thenBy { it.fileName.toString().lowercase() }
        )
        for (entry in sorted) {
            includeDocument(cursor, entry)
        }
        return cursor
    }

    private fun includeDocument(cursor: MatrixCursor, path: Path) {
        val attributes: BasicFileAttributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to read $path: ${e.message}")
        }
        val isDirectory = attributes.isDirectory
        val displayName = path.fileName?.toString() ?: path.toString()
        var flags = 0
        if (!isDirectory) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (Files.isWritable(path)) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, path.toString())
            .add(Document.COLUMN_DISPLAY_NAME, displayName)
            .add(Document.COLUMN_MIME_TYPE, mimeFor(displayName, isDirectory))
            .add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_SIZE, attributes.size())
            .add(Document.COLUMN_LAST_MODIFIED, attributes.lastModifiedTime().toMillis())
    }

    private fun mimeFor(name: String, isDirectory: Boolean): String =
        if (isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
        }

    // ---------------------------------------------------------------------- files

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val path = pathForDocumentId(documentId)
        val modeBits = ParcelFileDescriptor.parseMode(mode)
        localFileOrNull(path)?.let { file ->
            return ParcelFileDescriptor.open(file, modeBits)
        }
        // Remote/root-backed paths cannot be opened as a seekable local file; stream
        // through a reliable pipe on a worker thread.
        return if (modeBits and ParcelFileDescriptor.MODE_WRITE_ONLY != 0) {
            val options = buildSet {
                if (modeBits and ParcelFileDescriptor.MODE_CREATE != 0) {
                    add(StandardOpenOption.CREATE)
                }
                if (modeBits and ParcelFileDescriptor.MODE_TRUNCATE != 0) {
                    add(StandardOpenOption.TRUNCATE_EXISTING)
                }
                add(StandardOpenOption.WRITE)
            }
            pipeWrite(path, options.toTypedArray())
        } else {
            pipeRead(path)
        }
    }

    /** Streams [path] into a pipe so a client can read it. */
    private fun pipeRead(path: Path): ParcelFileDescriptor {
        val (readEnd, writeEnd) = createPipe()
        thread(name = "DocumentsProviderPipe") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                    Files.newInputStream(path).use { input ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                try {
                    writeEnd.closeWithError(e.toString())
                } catch (_: IOException) {
                }
            }
        }
        return readEnd
    }

    /** Streams a pipe into [path] so a client can write it. */
    private fun pipeWrite(
        path: Path,
        options: Array<StandardOpenOption>
    ): ParcelFileDescriptor {
        val (readEnd, writeEnd) = createPipe()
        thread(name = "DocumentsProviderPipe") {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    Files.newOutputStream(path, *options).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                try {
                    readEnd.closeWithError(e.toString())
                } catch (_: IOException) {
                }
            }
        }
        return writeEnd
    }

    private fun createPipe(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val pair = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (e: IOException) {
            throw IOException("Failed to create pipe", e)
        }
        return pair[0] to pair[1]
    }

    // ------------------------------------------------------------ write operations

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = pathForDocumentId(parentDocumentId)
        val name = uniqueFileName(parent, displayName)
        val path = parent.resolve(name)
        try {
            if (mimeType == Document.MIME_TYPE_DIR) {
                Files.createDirectory(path)
            } else {
                Files.createFile(path)
            }
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to create $path: ${e.message}")
        }
        notifyChildrenChanged(parent)
        return path.toString()
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val path = pathForDocumentId(documentId)
        val parent = path.parent
        try {
            Files.walkFileTree(path, object : java8.nio.file.FileVisitor<Path> {
                override fun preVisitDirectory(
                    dir: Path,
                    attributes: BasicFileAttributes
                ): java8.nio.file.FileVisitResult = java8.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): java8.nio.file.FileVisitResult {
                    Files.delete(file)
                    return java8.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exception: IOException
                ): java8.nio.file.FileVisitResult = java8.nio.file.FileVisitResult.CONTINUE

                override fun postVisitDirectory(
                    dir: Path,
                    exception: IOException?
                ): java8.nio.file.FileVisitResult {
                    Files.delete(dir)
                    return java8.nio.file.FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to delete $path: ${e.message}")
        }
        parent?.let { notifyChildrenChanged(it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val path = pathForDocumentId(documentId)
        val parent = path.parent ?: throw FileNotFoundException(documentId)
        val target = parent.resolve(displayName)
        try {
            Files.move(path, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to rename $path to $target: ${e.message}")
        }
        notifyChildrenChanged(parent)
        return target.toString()
    }

    // -------------------------------------------------------------------- helpers

    private fun pathForDocumentId(documentId: String): Path {
        val path = Paths.get(documentId)
        if (!path.isAbsolute) {
            throw FileNotFoundException("Document ID must be an absolute path: $documentId")
        }
        return path.normalize()
    }

    private fun localFileOrNull(path: Path): File? =
        try {
            path.toFile()?.takeIf { it.exists() }
        } catch (e: UnsupportedOperationException) {
            null
        }

    private fun uniqueFileName(parent: Path, displayName: String): String {
        val hasExtension = displayName.substringAfterLast('.', "").isNotEmpty()
        val baseName = if (hasExtension) {
            displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
        } else {
            displayName
        }
        val extension = if (hasExtension) displayName.substringAfterLast('.', "") else ""
        for (index in 0..9999) {
            val candidate = when {
                index == 0 -> displayName
                hasExtension -> "$baseName ($index).$extension"
                else -> "$baseName ($index)"
            }
            if (Files.notExists(parent.resolve(candidate))) {
                return candidate
            }
        }
        return displayName
    }

    private fun notifyChildrenChanged(parent: Path) {
        try {
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parent.toString()),
                null
            )
        } catch (e: Exception) {
            // Notification failures must never break the mutation itself.
        }
    }

    companion object {
        const val AUTHORITY = "me.zhanghai.android.files.documents"

        private const val ROOT_ID_PREFIX = "volume:"
        private const val ROOT_ID_DEVICE_ROOT = "device_root"

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
    }
}
