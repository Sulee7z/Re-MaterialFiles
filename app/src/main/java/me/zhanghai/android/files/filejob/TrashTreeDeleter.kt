package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes

/** A missing entry is already deleted; an unreadable entry must retain its index record. */
internal class TrashTreeDeleter(
    private val delete: (Path) -> Unit = { Files.deleteIfExists(it) }
) : SimpleFileVisitor<Path>() {
    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
        checkCancelled()
        delete(file)
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
        checkCancelled()
        if (exception !is NoSuchFileException) throw exception
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
        checkCancelled()
        if (exception != null) throw exception
        delete(directory)
        return FileVisitResult.CONTINUE
    }
}

internal fun deleteTrashTree(path: Path) {
    // Default traversal does not follow symbolic links.
    Files.walkFileTree(path, TrashTreeDeleter())
}
