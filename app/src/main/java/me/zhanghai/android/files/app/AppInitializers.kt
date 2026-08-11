/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.os.AsyncTask
import android.os.Build
import android.webkit.WebView
import jcifs.context.SingletonContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.coil.initializeCoil
import me.zhanghai.android.files.filejob.fileJobNotificationTemplate
import me.zhanghai.android.files.ftpserver.ftpServerServiceNotificationTemplate
import me.zhanghai.android.files.hiddenapi.HiddenApi
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.root.StellarUserServiceCompat
import me.zhanghai.android.files.searchindex.FileIndexer
import me.zhanghai.android.files.searchindex.SearchIndexDb
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.FtpServerAuthenticator
import me.zhanghai.android.files.storage.SftpServerAuthenticator
import me.zhanghai.android.files.storage.SmbServerAuthenticator
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.storage.WebDavServerAuthenticator
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper
import java.util.Properties
import me.zhanghai.android.files.provider.ftp.client.Client as FtpClient
import me.zhanghai.android.files.provider.sftp.client.Client as SftpClient
import me.zhanghai.android.files.provider.smb.client.Client as SmbClient
import me.zhanghai.android.files.provider.webdav.client.Client as WebDavClient
import roro.stellar.Stellar

val appInitializers = listOf(
    ::initializeCrashlytics,
    ::disableHiddenApiChecks,
    ::initializeWebViewDebugging,
    ::initializeCoil,
    ::initializeFileSystemProviders,
    ::initializeSearchIndexDb,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializeStellarListeners,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels,
    ::preloadSearchIndexIfNeeded
)

private fun initializeCrashlytics() {
//#ifdef NONFREE
    me.zhanghai.android.files.nonfree.CrashlyticsInitializer.initialize()
//#endif
}

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun initializeWebViewDebugging() {
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        SingletonContext.init(
            Properties().apply {
                setProperty("jcifs.netbios.cachePolicy", "0")
                setProperty("jcifs.smb.client.maxVersion", "SMB1")
            }
        )
    }
    FtpClient.authenticator = FtpServerAuthenticator
    SftpClient.authenticator = SftpServerAuthenticator
    SmbClient.authenticator = SmbServerAuthenticator
    WebDavClient.authenticator = WebDavServerAuthenticator
}

private fun initializeLiveDataObjects() {
// Force initialization of LiveData objects so that it won't happen on a background thread.
StorageVolumeListLiveData.value
Settings.FILE_LIST_DEFAULT_DIRECTORY.value
SearchIndexDb.initialize(application)
}

private fun initializeStellarListeners() {
    // Stellar (https://github.com/roro2239/Stellar), a Shizuku fork with a privileged API
    // framework, is supported alongside the original Shizuku integration. When the Stellar
    // service goes away, notify the waiting user-service connections so that the next
    // launch attempt starts a fresh user service process.
    // @see <a href="https://github.com/roro2239/Stellar/blob/main/INTEGRATION_GUIDE.md">Stellar</a>
    val binderDeadListener = Stellar.OnBinderDeadListener {
        StellarUserServiceCompat.onBinderDead()
    }
    Stellar.addBinderDeadListener(binderDeadListener)
}

private fun initializeSearchIndexDb() = SearchIndexDb.initialize(application)

/**
 * Builds the file name index in the background shortly after app start (first launch or
 * after app data wipe only), so the in-file-list search can query the SQLite index instead
 * of walking the whole directory tree. Skipped when an index already exists; rebuild it
 * manually from the index search screen if it goes stale.
 */
private fun preloadSearchIndexIfNeeded() {
    val hasIndex = try {
        SearchIndexDb.count() > 0
    } catch (e: Exception) {
        e.printStackTrace()
        true
    }
    if (hasIndex) {
        return
    }
    Thread {
        // Let the app finish launching before hammering the storage.
        try {
            Thread.sleep(3000)
        } catch (e: InterruptedException) {
            return@Thread
        }
        // Storage volumes, plus /data trees when root/Shizuku is available (see FileIndexer).
        val roots = FileIndexer.getIndexRoots()
        if (roots.isEmpty()) {
            return@Thread
        }
        FileIndexer.startIndex(roots, onProgress = {}, onDone = {})
    }.apply {
        name = "SearchIndexPreloader"
        priority = Thread.MIN_PRIORITY
    }.start()
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}
