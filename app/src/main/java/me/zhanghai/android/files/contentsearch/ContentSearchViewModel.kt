/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.contentsearch

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Files
import java8.nio.file.Path
import me.zhanghai.android.files.util.DataState
import java.util.ArrayDeque

data class ContentSearchResult(
    val path: Path,
    val fileName: String,
    val relativePath: String,
    val matchCount: Int
)

class ContentSearchViewModel(private val directory: Path) : ViewModel() {

    companion object {
        private const val MAX_FILE_SIZE = 2L * 1024 * 1024
        private const val MAX_RESULTS = 500
        private const val MAX_SCAN_ITEMS = 50000
    }

    private val _resultsLiveData = MutableLiveData<DataState<List<ContentSearchResult>>>()
    val resultsLiveData: LiveData<DataState<List<ContentSearchResult>>>
        get() = _resultsLiveData

    private val _progressLiveData = MutableLiveData<Int>()
    val progressLiveData: LiveData<Int>
        get() = _progressLiveData

    private var cancelled = false

    fun search(query: String, caseSensitive: Boolean, textOnly: Boolean) {
        cancelled = true
        if (query.isEmpty()) {
            _resultsLiveData.value = DataState.Success(emptyList())
            return
        }
        _resultsLiveData.value = DataState.Loading()
        _progressLiveData.value = 0
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val results = ArrayList<ContentSearchResult>()
            var scanned = 0
            try {
                val searchBytes = query.toByteArray(Charsets.UTF_8)
                fun searchFile(path: Path, relative: String): Boolean {
                    scanned++
                    if (scanned % 25 == 0) {
                        _progressLiveData.postValue(scanned)
                    }
                    try {
                        val size = Files.size(path)
                        if (size > MAX_FILE_SIZE) {
                            return false
                        }
                        val bytes = Files.newInputStream(path).use { it.readBytes() }
                        if (textOnly) {
                            val limit = minOf(bytes.size, 4096)
                            for (index in 0 until limit) {
                                if (bytes[index].toInt() == 0) {
                                    return false
                                }
                            }
                        }
                        val text = String(bytes, Charsets.UTF_8)
                        val count = if (caseSensitive) {
                            text.split(query).size - 1
                        } else {
                            text.split(query, ignoreCase = true).size - 1
                        }
                        if (count > 0) {
                            results.add(
                                ContentSearchResult(
                                    path, path.fileName.toString(), relative, count
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Skip unreadable files.
                    }
                    return results.size >= MAX_RESULTS || scanned >= MAX_SCAN_ITEMS
                }
                // Iterative walk with visited-set protection against symbolic link loops.
                val visited = HashSet<String>()
                val queue = ArrayDeque<Pair<Path, String>>()
                queue.addLast(directory to "")
                while (queue.isNotEmpty() && !cancelled && results.size < MAX_RESULTS &&
                    scanned < MAX_SCAN_ITEMS
                ) {
                    val (current, relative) = queue.removeFirst()
                    if (current.toString() in visited) {
                        continue
                    }
                    visited += current.toString()
                    val entries = try {
                        Files.newDirectoryStream(current).use { it.toList() }
                    } catch (e: Exception) {
                        continue
                    }
                    for (entry in entries.sortedBy { it.fileName.toString() }) {
                        if (cancelled || results.size >= MAX_RESULTS || scanned >= MAX_SCAN_ITEMS) {
                            break
                        }
                        if (entry.toString() in visited) {
                            continue
                        }
                        val isDirectory = try {
                            Files.isDirectory(entry)
                        } catch (e: Exception) {
                            false
                        }
                        if (isDirectory) {
                            queue.addLast(entry to "$relative/${entry.fileName}")
                        } else {
                            searchFile(entry, relative)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (!cancelled) {
                    _resultsLiveData.postValue(DataState.Error(null, e))
                }
                return@execute
            }
            if (!cancelled) {
                _progressLiveData.postValue(scanned)
                _resultsLiveData.postValue(DataState.Success(results))
            }
        }
    }

    override fun onCleared() {
        cancelled = true
    }
}

