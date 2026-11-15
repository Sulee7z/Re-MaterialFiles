package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileVisitResult
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class TrashTreeDeleterTest {
    private val path = Proxy.newProxyInstance(
        Path::class.java.classLoader, arrayOf(Path::class.java)
    ) { _, method, _ ->
        if (method.name == "toString") "trash-test" else throw UnsupportedOperationException(method.name)
    } as Path

    @Test
    fun missingEntryIsSuccessButPermissionFailureIsNot() {
        val visitor = TrashTreeDeleter { fail("Must not delete on visit failure") }
        assertEquals(FileVisitResult.CONTINUE, visitor.visitFileFailed(path, NoSuchFileException("gone")))
        assertThrows(AccessDeniedException::class.java) {
            visitor.visitFileFailed(path, AccessDeniedException("unreadable"))
        }
    }

    @Test
    fun failedDirectoryTraversalNeverDeletesParent() {
        var deleted = false
        val visitor = TrashTreeDeleter { deleted = true }
        assertThrows(IOException::class.java) { visitor.postVisitDirectory(path, IOException("scan failed")) }
        assertFalse(deleted)
    }

    @Test
    fun failedDeletePropagatesSoIndexIsNotRemoved() {
        val visitor = TrashTreeDeleter { throw IOException("delete failed") }
        assertThrows(IOException::class.java) { visitor.postVisitDirectory(path, null) }
    }

    @Test
    fun cancellationDoesNotDeleteAndPreservesInterruptStatus() {
        var deleted = false
        val visitor = TrashTreeDeleter { deleted = true }
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { visitor.postVisitDirectory(path, null) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(deleted)
        } finally {
            Thread.interrupted()
        }
    }
}
