/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java8.nio.file.Path

/**
 * Remembers the most recent "move" so the UI can offer an Undo action. Each entry is a
 * (source, target) pair of an actually-moved file/directory; undo moves each target back
 * to its source's parent directory. Cleared once the snackbar is dismissed.
 */
object MoveUndoManager {

    data class MoveUndo(
        val entries: List<Pair<Path, Path>>
    )

    private val _undoLiveData = MutableLiveData<MoveUndo?>(null)
    val undoLiveData: LiveData<MoveUndo?> = _undoLiveData

    fun record(entries: List<Pair<Path, Path>>) {
        if (entries.isEmpty()) {
            return
        }
        _undoLiveData.postValue(MoveUndo(entries))
    }

    fun clear() {
        _undoLiveData.postValue(null)
    }
}
