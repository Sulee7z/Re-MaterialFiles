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

    fun search(query: String) {
        if (query.isBlank()) {
            _resultsLiveData.value = emptyList()
            return
        }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                SearchIndexDb.search(query)
            }
            _resultsLiveData.value = results
        }
    }

    fun refreshIndexInfo() {
        _indexInfoLiveData.value =
            FileIndexer.lastIndexedEntryCount to FileIndexer.lastIndexedTimeMillis
    }

    fun rebuildIndex(roots: List<Path>) {
        _indexingLiveData.value = true
        FileIndexer.startIndex(
            roots,
            onProgress = { count ->
                _indexInfoLiveData.value = count to System.currentTimeMillis()
            },
            onDone = { throwable ->
                _indexingLiveData.value = false
                refreshIndexInfo()
            }
        )
    }

    fun getIndexRoots(): List<Path> =
        me.zhanghai.android.files.storage.StorageVolumeListLiveData.value
            ?.filter { it.state == "mounted" }
            ?.mapNotNull { volume ->
                val directory = volume.directory ?: return@mapNotNull null
                Paths.get(directory.absolutePath)
            } ?: emptyList()
}
