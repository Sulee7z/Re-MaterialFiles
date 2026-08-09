/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import android.util.Log
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.settings.HiddenPaths
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    private val observer: PathObserver

    private companion object {
        const val LOG_TAG = "SoraEditor"
    }

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                val hiddenPaths = HiddenPaths.getAll()
                var filteredCount = 0
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = mutableListOf<FileItem>()
                    for (path in directoryStream) {
                        if (hiddenPaths.contains(path.toString())) {
                            filteredCount++
                            continue
                        }
                        try {
                            fileList.add(path.loadFileItem())
                        } catch (e: DirectoryIteratorException) {
                            // TODO: Ignoring such a file can be misleading and we need to support
                            //  files without information.
                            Log.w(
                                LOG_TAG,
                                "DirectoryIteratorException while loading item: $path",
                                e
                            )
                        } catch (e: IOException) {
                            // Ignore items whose attributes cannot be loaded, but make sure such
                            // failures are visible in logcat so missing files can be diagnosed.
                            Log.w(LOG_TAG, "IOException while loading item: $path", e)
                        }
                    }
                    Success(fileList as List<FileItem>)
                }.also {
                    Log.i(
                        LOG_TAG,
                        "FileListLiveData.loadValue for $path: hiddenPaths=${hiddenPaths.size}, " +
                            "filtered=$filteredCount, total=${it.value?.size}"
                    )
                }
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        observer.close()
        future?.cancel(true)
    }
}

