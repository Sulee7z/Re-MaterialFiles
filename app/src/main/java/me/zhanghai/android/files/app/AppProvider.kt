/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log

lateinit var application: Application private set

fun isApplicationInitialized(): Boolean = ::application.isInitialized

private const val SLOW_INITIALIZER_LOG_THRESHOLD_MILLIS = 5L

class AppProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        application = context as Application
        // Cold start runs before Application.onCreate, so every millisecond spent here is
        // user-visible launcher lag. Log per-initializer durations (visible with
        // `adb logcat -s AppStart`) so startup regressions are easy to spot.
        val startUptimeMillis = SystemClock.uptimeMillis()
        appInitializers.forEach { initializer ->
            val initializerStartUptimeMillis = SystemClock.uptimeMillis()
            initializer()
            val durationMillis =
                SystemClock.uptimeMillis() - initializerStartUptimeMillis
            if (durationMillis >= SLOW_INITIALIZER_LOG_THRESHOLD_MILLIS) {
                Log.i(
                    "AppStart",
                    "${initializer.name} took ${durationMillis}ms"
                )
            }
        }
        Log.i(
            "AppStart",
            "AppProvider.onCreate total ${SystemClock.uptimeMillis() - startUptimeMillis}ms"
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? {
        throw UnsupportedOperationException()
    }

    override fun getType(uri: Uri): String? {
        throw UnsupportedOperationException()
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        throw UnsupportedOperationException()
    }
}
