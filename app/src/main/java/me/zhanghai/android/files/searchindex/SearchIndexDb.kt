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
    private const val DATABASE_VERSION = 1
    private const val TABLE = "files"

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

    @Synchronized
    fun delete(path: String) {
        helper.writableDatabase.delete(TABLE, "$COL_PATH = ?", arrayOf(path))
    }

    @Synchronized
    fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
    }

    @Synchronized
    fun search(query: String, limit: Int = 200): List<IndexedFile> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%$escaped%"
        return helper.readableDatabase.query(
            TABLE, null, "$COL_NAME LIKE ? ESCAPE '\\'",
            arrayOf(pattern), null, null, "$COL_NAME COLLATE NOCASE", limit.toString()
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    fun File.isIndexableRoot(): Boolean = true
}
