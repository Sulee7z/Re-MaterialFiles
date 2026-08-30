/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java8.nio.file.Path

/**
 * Remembers the most recent "delete to trash" so the UI can offer an Undo action. Each
 * entry is an (originalPath, trashPath) pair of a file/directory that was moved into the
 * app's trash directory instead of being permanently deleted; undo moves each trash entry
 * back to its original path. Once the snackbar is dismissed without undo, the trash
 * entries are permanently deleted.
 */
object DeleteUndoManager {

    data class DeleteUndo(
        val entries: List<Pair<Path, Path>>
    )

    private val _undoLiveData = MutableLiveData<DeleteUndo?>(null)
    val undoLiveData: LiveData<DeleteUndo?> = _undoLiveData

    fun record(entries: List<Pair<Path, Path>>) {
        if (entries.isEmpty()) {
            return
        }
        _undoLiveData.postValue(DeleteUndo(entries))
    }

    fun clear() {
        _undoLiveData.postValue(null)
    }
}
