/*
 * Copyright (c) 2026 Sulee7z
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.Path

/**
 * In-memory overlay of edits made while browsing an archive. All write operations on an
 * opened archive are recorded here instead of touching the archive file; the changes are
 * flushed back to the archive only when the user explicitly saves.
 *
 * Each change is keyed by the archive-relative [Path] inside the archive's own file system
 * (e.g. "/a/b.txt").
 */
internal class ArchiveEditSession {
    /** Original entries whose content was replaced (path -> new content). */
    val modifiedEntries = mutableMapOf<Path, ByteArray>()

    /** Entries removed from the archive (recursively means every subpath too). */
    val deletedEntries = mutableSetOf<Path>()

    /** Entries renamed (original path -> new path). */
    val renamedEntries = mutableMapOf<Path, Path>()

    /** Newly created entries (path -> content, null means an empty directory). */
    val createdEntries = mutableMapOf<Path, ByteArray?>()

    var dirtySinceSave = false

    val isDirty: Boolean
        get() = dirtySinceSave || modifiedEntries.isNotEmpty() || deletedEntries.isNotEmpty() ||
            renamedEntries.isNotEmpty() || createdEntries.isNotEmpty()

    fun applyAndCheckDirty(block: () -> Unit) {
        block()
        dirtySinceSave = true
    }

    fun clear() {
        modifiedEntries.clear()
        deletedEntries.clear()
        renamedEntries.clear()
        createdEntries.clear()
        dirtySinceSave = false
    }
}
