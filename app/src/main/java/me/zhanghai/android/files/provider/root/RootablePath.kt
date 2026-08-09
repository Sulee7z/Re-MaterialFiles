/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import java8.nio.file.Path
import me.zhanghai.android.files.app.isApplicationInitialized
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException

interface RootablePath {
    fun isRootRequired(isAttributeAccess: Boolean): Boolean
}

private val rootStrategy: RootStrategy
    get() {
        if (isRunningAsRoot) {
            return RootStrategy.NEVER
        }
        if (!isApplicationInitialized()) {
            // We may be running inside a root user service process (e.g. via Shizuku with ADB,
            // uid 2000) where the app's Application/AppProvider has never been initialized, so
            // Settings cannot be read here (and its class initialization would fail and be
            // permanently rejected). Such a service process already runs with sufficient
            // privileges to access restricted paths directly, so use NEVER (direct access)
            // without ever touching Settings.
            return RootStrategy.NEVER
        }
        return Settings.ROOT_STRATEGY.valueCompat
    }

@Throws(IOException::class)
fun <T, R> callRootable(
    path: Path,
    isAttributeAccess: Boolean,
    localObject: T,
    rootObject: T, block: T.() -> R
): R {
    path as? RootablePath ?: throw IllegalArgumentException("$path is not a RootablePath")
    return when (rootStrategy) {
        RootStrategy.NEVER -> localObject.block()
        RootStrategy.AUTOMATIC ->
            if (path.isRootRequired(isAttributeAccess)) {
                rootObject.block()
            } else {
                localObject.block()
            }
        RootStrategy.ALWAYS -> rootObject.block()
        // SHIZUKU routes through the remote service only for restricted paths
        // (Android/data and Android/obb of other applications), just like
        // AUTOMATIC. The remote service is launched through Shizuku (shell uid
        // 2000) whenever possible, so those paths can be accessed without root
        // (with Shizuku running via ADB/wireless debugging). Regular paths keep
        // using the local file system so the whole storage stays usable even
        // when Shizuku isn't available.
        // @see RootFileService
        RootStrategy.SHIZUKU ->
            if (path.isRootRequired(isAttributeAccess)) {
                rootObject.block()
            } else {
                localObject.block()
            }
    }
}

@Throws(IOException::class)
fun <T, R> callRootable(
    path1: Path,
    path2: Path,
    isAttributeAccess: Boolean,
    localObject: T,
    rootObject: T,
    block: T.() -> R
): R {
    path1 as? RootablePath ?: throw IllegalArgumentException("$path1 is not a RootablePath")
    path2 as? RootablePath ?: throw IllegalArgumentException("$path2 is not a RootablePath")
    return when (rootStrategy) {
        RootStrategy.NEVER ->
            localObject.block()
        RootStrategy.AUTOMATIC ->
            if (path1.isRootRequired(isAttributeAccess)
                || path2.isRootRequired(isAttributeAccess)) {
                rootObject.block()
            } else {
                localObject.block()
            }
        RootStrategy.ALWAYS ->
            rootObject.block()
        // @see the single-path callRootable() above for the SHIZUKU semantics.
        RootStrategy.SHIZUKU ->
            if (path1.isRootRequired(isAttributeAccess)
                || path2.isRootRequired(isAttributeAccess)) {
                rootObject.block()
            } else {
                localObject.block()
            }
    }
}
