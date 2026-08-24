/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * SQLite database backing the Everything-style file name index.
 */
object SearchIndexDb {

    private const val DATABASE_NAME = "search_index.db"
    private const val DATABASE_VERSION = 2
    private const val TABLE = "files"
    private const val META_TABLE = "meta"

    private const val COL_PATH = "path"
    private const val COL_NAME = "name"
    private const val COL_SIZE = "size"
    private const val COL_MTIME = "mtime"
    private const val COL_IS_DIR = "is_dir"

    private lateinit var helper: Helper

    fun initialize(context: Context) {
        if (::helper.isInitialized) {
            return
        }
        helper = Helper(context)
    }

    @Synchronized
    fun count(): Long = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE", null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    /**
     * Whether any indexed file lives at or below [pathPrefix]. Used to decide whether the
     * index covers a directory: if it does, search results can be trusted (an empty result
     * really means no match) and the expensive recursive tree walk can be skipped.
     */
    @Synchronized
    fun hasEntriesUnder(pathPrefix: String): Boolean {
        val normalizedPrefix = pathPrefix.trimEnd('/')
        return helper.readableDatabase.rawQuery(
            // Byte-range form of "path = prefix OR path starts with prefix/", which the
            // path primary-key index can serve directly ('/' is the only character in
            // the ['/', '0') byte range, so [prefix/, prefix0) is exactly the subtree).
            "SELECT 1 FROM $TABLE WHERE $COL_PATH = ? OR ($COL_PATH >= ? AND $COL_PATH < ?)" +
                " LIMIT 1",
            arrayOf(normalizedPrefix, "$normalizedPrefix/", "${normalizedPrefix}0")
        ).use { cursor -> cursor.moveToFirst() }
    }

    @Synchronized
    fun insert(path: String, name: String, size: Long, mtime: Long, isDir: Boolean) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE, null,
            ContentValues().apply {
                put(COL_PATH, path)
                put(COL_NAME, name)
                put(COL_SIZE, size)
                put(COL_MTIME, mtime)
                put(COL_IS_DIR, if (isDir) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Inserts many rows inside a single transaction, which is orders of magnitude faster
     * than individual autocommit inserts (used by the indexer worker threads).
     */
    @Synchronized
    fun insertBatch(items: List<IndexedFileInsert>) {
        if (items.isEmpty()) {
            return
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                db.insertWithOnConflict(
                    TABLE, null,
                    ContentValues().apply {
                        put(COL_PATH, item.path)
                        put(COL_NAME, item.name)
                        put(COL_SIZE, item.size)
                        put(COL_MTIME, item.mtime)
                        put(COL_IS_DIR, if (item.isDir) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class IndexedFileInsert(
        val path: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val isDir: Boolean
    )

    @Synchronized
    fun delete(path: String) {
        helper.writableDatabase.delete(TABLE, "$COL_PATH = ?", arrayOf(path))
    }

    @Synchronized
    fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
    }

    /** Persists a long value (e.g. the last index completion time) next to the index. */
    @Synchronized
    fun putMetaLong(key: String, value: Long) {
        helper.writableDatabase.insertWithOnConflict(
            META_TABLE, null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun getMetaLong(key: String): Long? =
        helper.readableDatabase.query(
            META_TABLE, arrayOf("value"), "key = ?", arrayOf(key), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    /**
     * Searches the index with an Everything-style query: space-separated AND terms, `|` OR
     * alternatives, `!` exclusions, `"..."` exact phrases, `file:`/`folder:`/`doc:`/`pic:`/
     * `video:`/`zip:` category filters, `size:`/`dm:` numeric/date filters and an optional
     * directory scope. Results are ordered by modification time (newest first).
     */
    @Synchronized
    fun search(query: SearchQuery, limit: Int = 200): List<IndexedFile> {
        // Each OR subgroup contributes its own conjunction of AND terms; empty subgroups
        // (pure-filter queries like "size:>10mb") are treated as matching everything.
        val nonEmptySubgroups = query.subgroups.filter { it.isNotEmpty() }
        val subgroupConditions = nonEmptySubgroups.map { nameConditions(it) }
        val groupSql = subgroupConditions.joinToString(" OR ") { (conditions, _) ->
            "(${conditions.joinToString(" AND ")})"
        }
        val commonConditions = mutableListOf<String>()
        val commonArgs = mutableListOf<Any>()

        query.excludeTerms.forEach { term ->
            val escaped = escapeLike(term)
            commonConditions += "$COL_NAME NOT LIKE ? ESCAPE '\\'"
            commonArgs += "%$escaped%"
        }
        query.exactPhrases.forEach { phrase ->
            val escaped = escapeLike(phrase)
            commonConditions += "$COL_NAME LIKE ? ESCAPE '\\'"
            commonArgs += "%$escaped%"
        }
        when {
            query.fileOnly -> commonConditions += "$COL_IS_DIR = 0"
            query.folderOnly -> commonConditions += "$COL_IS_DIR = 1"
        }
        if (query.docOnly) {
            commonConditions += extensionCondition("doc", "docx", "pdf", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "md")
        }
        if (query.picOnly) {
            commonConditions += extensionCondition("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif", "ico", "tif", "tiff")
        }
        if (query.videoOnly) {
            commonConditions += extensionCondition("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts")
        }
        if (query.zipOnly) {
            commonConditions += extensionCondition("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "jar", "apk")
        }
        query.minSize?.let {
            commonConditions += "$COL_SIZE >= ?"
            commonArgs += it
        }
        query.maxSize?.let {
            commonConditions += "$COL_SIZE <= ?"
            commonArgs += it
        }
        query.minMtimeMillis?.let {
            commonConditions += "$COL_MTIME >= ?"
            commonArgs += it
        }
        query.maxMtimeMillis?.let {
            commonConditions += "$COL_MTIME <= ?"
            commonArgs += it
        }
        query.pathPrefix?.let { prefix ->
            val normalizedPrefix = prefix.trimEnd('/')
            // Byte-range form of "path = prefix OR path starts with prefix/" so scoped
            // searches are served by the path primary-key index instead of forcing a
            // full-table LIKE scan ('/' is the only character in ['/', '0'), so the
            // range [prefix/, prefix0) is exactly the prefix subtree). Binary collation
            // (the default for these columns) makes the byte bounds exact.
            commonConditions += "($COL_PATH = ? OR ($COL_PATH >= ? AND $COL_PATH < ?))"
            commonArgs += normalizedPrefix
            commonArgs += "$normalizedPrefix/"
            commonArgs += "${normalizedPrefix}0"
        }

        val selectionParts = mutableListOf<String>()
        val allArgs = mutableListOf<Any>()
        if (subgroupConditions.isNotEmpty()) {
            selectionParts += "($groupSql)"
            subgroupConditions.forEach { (_, args) ->
                allArgs.addAll(args)
            }
        }
        selectionParts.addAll(commonConditions)
        allArgs.addAll(commonArgs)
        if (selectionParts.isEmpty()) {
            return emptyList()
        }
        val selection = selectionParts.joinToString(" AND ")
        return helper.readableDatabase.query(
            TABLE, null, selection, allArgs.map { it.toString() }.toTypedArray(), null, null,
            "$COL_MTIME DESC, $COL_NAME COLLATE NOCASE", limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        IndexedFile(
                            cursor.getString(0), cursor.getString(1),
                            cursor.getLong(2), cursor.getLong(3), cursor.getInt(4) != 0
                        )
                    )
                }
            }
        }
    }

    /** Backwards-compatible string query entry point (used by the index search screen). */
    @Synchronized
    fun search(query: String, pathPrefix: String? = null, limit: Int = 200): List<IndexedFile> {
        val parsed = SearchQueryParser.parse(query)
        return search(
            parsed.copy(pathPrefix = parsed.pathPrefix ?: pathPrefix),
            limit
        )
    }

    private fun nameConditions(terms: List<String>): Pair<List<String>, List<Any>> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        for (term in terms) {
            val escaped = escapeLike(term)
            conditions +=
                "($COL_NAME LIKE ? ESCAPE '\\' OR ${compactDisplayNameExpression()} LIKE ? ESCAPE '\\')"
            args += "%$escaped%"
            args += "%$escaped%"
        }
        return conditions to args
    }

    private fun extensionCondition(vararg extensions: String): String =
        extensions.joinToString(" OR ") { "LOWER($COL_NAME) GLOB '*.$it'" }

    /**
     * The display name with spaces, hyphens, underscores and dots removed, e.g.
     * "my-app_1.0.txt" becomes "myapp10txt". Mirrors the compact-name matching used by the
     * quick-search reference implementation so "myapp" finds "my-app_1.0".
     */
    private fun compactDisplayNameExpression(): String {
        val lowerName = "LOWER($COL_NAME)"
        val noSpaces = "REPLACE($lowerName, ' ', '')"
        val noHyphens = "REPLACE($noSpaces, '-', '')"
        val noUnderscores = "REPLACE($noHyphens, '_', '')"
        return "REPLACE($noUnderscores, '.', '')"
    }

    private fun escapeLike(query: String): String =
        buildString(query.length) {
            query.forEach { char ->
                when (char) {
                    '\\', '%', '_' -> {
                        append('\\')
                        append(char)
                    }
                    else -> append(char)
                }
            }
        }

    data class IndexedFile(
        val path: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val isDir: Boolean
    )

    private class Helper(context: Context) : SQLiteOpenHelper(
        context, DATABASE_NAME, null, DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COL_PATH TEXT PRIMARY KEY, $COL_NAME TEXT, " +
                    "$COL_SIZE INTEGER, $COL_MTIME INTEGER, $COL_IS_DIR INTEGER)"
            )
            db.execSQL("CREATE INDEX idx_name ON $TABLE ($COL_NAME)")
            db.execSQL("CREATE TABLE $META_TABLE (key TEXT PRIMARY KEY, value INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    fun File.isIndexableRoot(): Boolean = true
}
