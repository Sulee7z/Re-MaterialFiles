/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchIndexViewModel : ViewModel() {

    private val _resultsLiveData = MutableLiveData<List<SearchIndexDb.IndexedFile>>()
    val resultsLiveData: LiveData<List<SearchIndexDb.IndexedFile>>
        get() = _resultsLiveData

    private val _indexingLiveData = MutableLiveData(false)
    val indexingLiveData: LiveData<Boolean>
        get() = _indexingLiveData

    private val _indexInfoLiveData = MutableLiveData<Pair<Long, Long>>(0L to 0L)
    val indexInfoLiveData: LiveData<Pair<Long, Long>>
        get() = _indexInfoLiveData

    private var searchJob: Job? = null

    fun search(query: String) {
        // Cancel the in-flight query and debounce: one SQLite search per settled input
        // instead of one per keystroke, and a slow older query can never land after a
        // newer one and overwrite its results with stale rows.
        searchJob?.cancel()
        if (query.isBlank()) {
            _resultsLiveData.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val results = withContext(Dispatchers.IO) {
                SearchIndexDb.search(query)
            }
            _resultsLiveData.value = results
        }
    }

    fun refreshIndexInfo() {
        _indexInfoLiveData.postValue(
            FileIndexer.lastIndexedEntryCount to FileIndexer.lastIndexedTimeMillis
        )
    }

    /** Publishes index info restored from the database by the fragment. */
    fun postIndexInfo(count: Long, timeMillis: Long) {
        _indexInfoLiveData.value = count to timeMillis
    }

    fun rebuildIndex(roots: List<Path>) {
        _indexingLiveData.postValue(true)
        FileIndexer.startIndex(
            roots,
            onProgress = { count ->
                // Called on the indexer thread; must use postValue.
                _indexInfoLiveData.postValue(count to System.currentTimeMillis())
            },
            onDone = { throwable ->
                _indexingLiveData.postValue(false)
                refreshIndexInfo()
            }
        )
    }

    fun getIndexRoots(): List<Path> = FileIndexer.getIndexRoots()

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 200L
    }
}
