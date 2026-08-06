/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.elf

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Files
import java8.nio.file.Path
import me.zhanghai.android.files.util.DataState

class ElfFileViewModel(private val path: Path) : ViewModel() {

    private val _elfFileLiveData = MutableLiveData<DataState<ElfFile>>()
    val elfFileLiveData: LiveData<DataState<ElfFile>>
        get() = _elfFileLiveData

    init {
        load()
    }

    fun load() {
        _elfFileLiveData.value = DataState.Loading()
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value: DataState<ElfFile> = try {
                val bytes = Files.newInputStream(path).use { it.readBytes() }
                DataState.Success(ElfParser.parse(bytes))
            } catch (throwable: Throwable) {
                DataState.Error(null, throwable)
            }
            _elfFileLiveData.postValue(value)
        }
    }
}
