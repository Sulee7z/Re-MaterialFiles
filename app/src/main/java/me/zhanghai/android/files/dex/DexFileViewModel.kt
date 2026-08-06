/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Files
import java8.nio.file.Path
import me.zhanghai.android.files.util.DataState

class DexFileViewModel(private val path: Path) : ViewModel() {

    private val _dexFileLiveData = MutableLiveData<DataState<DexFile>>()
    val dexFileLiveData: LiveData<DataState<DexFile>>
        get() = _dexFileLiveData

    init {
        load()
    }

    private var disassembler: DexDisassembler? = null

    fun load() {
        _dexFileLiveData.value = DataState.Loading()
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value: DataState<DexFile> = try {
                // Files.readAllBytes() requires channel.size(), which is unsupported on remote
                // file systems (FTP/SFTP/SMB/WebDAV), so read the stream instead.
                val bytes = Files.newInputStream(path).use { it.readBytes() }
                val dexFile = DexParser.parse(bytes)
                disassembler = DexDisassembler(dexFile)
                DataState.Success(dexFile)
            } catch (throwable: Throwable) {
                DataState.Error(null, throwable)
            }
            _dexFileLiveData.postValue(value)
        }
    }

    fun disassemble(method: DexMethodDef): String = disassembler?.disassemble(method) ?: ""
}
