/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.FileVisitOption
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WalkFileTreeSearchable {
    @Throws(IOException::class)
    fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        val results = ConcurrentLinkedQueue<Path>()
        val cancelled = AtomicBoolean()
        val resultCount = java.util.concurrent.atomic.AtomicInteger()
        // Cap the number of delivered results: searching huge trees can match hundreds of
        // thousands of files and loading/rendering them all is the real bottleneck.
        val maxResults = 1000
        // Shared batched delivery, thread-safe.
        val batchLock = Any()
        val pending = mutableListOf<Path>()
        var lastProgressMillis = System.currentTimeMillis()
        fun deliver(paths: List<Path>) {
            synchronized(batchLock) {
                if (paths.isEmpty()) {
                    return
                }
                listener(paths)
            }
        }
        fun addResult(path: Path) {
            if (resultCount.get() >= maxResults) {
                cancelled.set(true)
                return
            }
            resultCount.incrementAndGet()
            synchronized(batchLock) {
                pending.add(path)
                val currentTimeMillis = System.currentTimeMillis()
                if (currentTimeMillis >= lastProgressMillis + intervalMillis) {
                    val batch = pending.toList()
                    pending.clear()
                    lastProgressMillis = currentTimeMillis
                    listener(batch)
                }
            }
        }

        // Top-level results are delivered immediately in small batches so the user sees
        // results without waiting for the whole tree walk.
        fun addResultImmediate(path: Path) {
            if (resultCount.get() >= maxResults) {
                cancelled.set(true)
                return
            }
            resultCount.incrementAndGet()
            synchronized(batchLock) {
                pending.add(path)
                if (pending.size >= 50) {
                    val batch = pending.toList()
                    pending.clear()
                    listener(batch)
                }
            }
        }

        val visitor = object : FileVisitor<Path> {
            @Throws(InterruptedIOException::class)
            override fun preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (cancelled.get()) {
                    return FileVisitResult.TERMINATE
                }
                if (dir != directory) {
                    visit(dir)
                }
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (cancelled.get()) {
                    return FileVisitResult.TERMINATE
                }
                visit(file)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
                if (cancelled.get()) {
                    return FileVisitResult.TERMINATE
                }
                if (exception is InterruptedIOException) {
                    throw exception
                }
                if (file != directory) {
                    visit(file)
                }
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun postVisitDirectory(dir: Path, exception: IOException?): FileVisitResult {
                if (exception is InterruptedIOException) {
                    throw exception
                }
                throwIfInterrupted()
                return if (cancelled.get()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            private fun visit(path: Path) {
                val fileName = path.fileName
                if (fileName != null && containsIgnoreCase(fileName.toString(), query)) {
                    results.add(path)
                    addResult(path)
                }
            }
        }

        // Read the top level first, then walk each child directory in parallel.
        val rootEntries = try {
            directory.newDirectoryStream().use { it.toList() }
        } catch (e: IOException) {
            visitor.visitFileFailed(directory, e)
            return
        }
        val directories = mutableListOf<Path>()
        for (path in rootEntries) {
            if (cancelled.get()) {
                break
            }
            val attributes = try {
                path.readAttributes(BasicFileAttributes::class.java)
            } catch (e: IOException) {
                continue
            }
            // Top-level matches are delivered immediately (small batches) so results appear
            // right away instead of after the whole tree has been walked.
            val fileName = path.fileName
            if (fileName != null && containsIgnoreCase(fileName.toString(), query)) {
                addResultImmediate(path)
            }
            if (attributes.isDirectory) {
                directories.add(path)
            }
        }
        visitor.postVisitDirectory(directory, null)

        if (directories.isEmpty()) {
            deliver(pending)
            return
        }
        val parallelism = minOf(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(2), directories.size
        )
        val executor = Executors.newFixedThreadPool(parallelism)
        val latch = CountDownLatch(directories.size)
        try {
            for (child in directories) {
                executor.execute {
                    try {
                        Files.walkFileTree(
                            child, setOf(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, visitor
                        )
                    } catch (e: Exception) {
                        // Individual directory failures are ignored.
                    } finally {
                        latch.countDown()
                    }
                }
            }
            // Wait for completion (interruptible so search cancellation works).
            while (true) {
                try {
                    latch.await()
                    break
                } catch (e: InterruptedException) {
                    cancelled.set(true)
                    throw InterruptedIOException().apply { initCause(e) }
                }
            }
        } finally {
            executor.shutdown()
        }
        deliver(pending)
    }

    private fun containsIgnoreCase(value: String, query: String): Boolean {
        if (query.isEmpty()) {
            return true
        }
        if (query.length > value.length) {
            return false
        }
        val limit = value.length - query.length
        for (index in 0..limit) {
            if (value.regionMatches(index, query, 0, query.length, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }
}

