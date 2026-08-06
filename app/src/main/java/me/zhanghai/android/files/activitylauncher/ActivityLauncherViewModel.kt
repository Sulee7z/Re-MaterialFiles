/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.activitylauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.packageManager
import me.zhanghai.android.files.util.DataState

data class LauncherApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)

data class AppActivities(
    val packageName: String,
    val activities: List<ActivityInfo>,
    val launchableActivityNames: Set<String>
)

class ActivityLauncherViewModel(context: Context) : ViewModel() {

    private val packageManager = context.packageManager

    private val _appsLiveData = MutableLiveData<DataState<List<LauncherApp>>>()
    val appsLiveData: LiveData<DataState<List<LauncherApp>>>
        get() = _appsLiveData

    init {
        load()
    }

    fun load() {
        _appsLiveData.value = DataState.Loading()
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value: DataState<List<LauncherApp>> = try {
                @Suppress("DEPRECATION")
                val applications = packageManager.getInstalledApplications(0)
                val apps = applications.map { applicationInfo ->
                    LauncherApp(
                        applicationInfo.packageName,
                        applicationInfo.loadLabel(packageManager).toString(),
                        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                    )
                }.sortedBy { it.label.lowercase() }
                DataState.Success(apps)
            } catch (throwable: Throwable) {
                DataState.Error(null, throwable)
            }
            _appsLiveData.postValue(value)
        }
    }

    fun loadActivities(packageName: String): AppActivities? {
        return try {
            @Suppress("DEPRECATION")
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val launchable = HashSet<String>()
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchableActivities = packageManager.queryIntentActivities(launcherIntent, 0)
            launchableActivities.forEach { resolveInfo ->
                resolveInfo.activityInfo?.let {
                    if (it.packageName == packageName) {
                        launchable.add(it.name)
                    }
                }
            }
            AppActivities(packageName, packageInfo.activities?.toList() ?: emptyList(), launchable)
        } catch (e: Exception) {
            null
        }
    }

    fun launchActivity(packageName: String, activityName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName(packageName, activityName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            application.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
