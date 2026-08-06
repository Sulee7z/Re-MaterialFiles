/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.logcat

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.topjohnwu.superuser.Shell
import me.zhanghai.android.files.util.DataState

class LogcatViewModel : ViewModel() {

    private val _logsLiveData = MutableLiveData<DataState<List<String>>>()
    val logsLiveData: LiveData<DataState<List<String>>>
        get() = _logsLiveData

    init {
        load(null)
    }

    fun load(level: String?) {
        _logsLiveData.value = DataState.Loading()
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value: DataState<List<String>> = try {
                DataState.Success(readLogcat(level))
            } catch (throwable: Throwable) {
                DataState.Error(null, throwable)
            }
            _logsLiveData.postValue(value)
        }
    }

    private fun readLogcat(level: String?): List<String> {
        val levelArg = level?.let { "*:$it" }
        try {
            if (Shell.isAppGrantedRoot() == true) {
                val command = if (levelArg != null) {
                    "logcat -d -v threadtime $levelArg"
                } else {
                    "logcat -d -v threadtime"
                }
                val result = Shell.cmd(command).exec()
                if (result.isSuccess) {
                    return result.out
                }
            }
        } catch (e: Throwable) {
            // Fall back to running logcat without root.
        }
        val arguments = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (levelArg != null) {
            arguments.add(levelArg)
        }
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readLines()
    }
}
