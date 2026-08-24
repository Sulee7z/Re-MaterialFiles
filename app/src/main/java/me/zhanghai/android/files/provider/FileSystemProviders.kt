/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider

import java8.nio.file.Files
import java8.nio.file.ProviderNotFoundException
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.ArchiveFileSystemProvider
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.content.ContentFileSystemProvider
import me.zhanghai.android.files.provider.document.DocumentFileSystemProvider
import me.zhanghai.android.files.provider.ftp.FtpFileSystemProvider
import me.zhanghai.android.files.provider.ftp.FtpesFileSystemProvider
import me.zhanghai.android.files.provider.ftp.FtpsFileSystemProvider
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.sftp.SftpFileSystemProvider
import me.zhanghai.android.files.provider.smb.SmbFileSystemProvider
import me.zhanghai.android.files.provider.webdav.WebDavFileSystemProvider
import me.zhanghai.android.files.provider.webdav.WebDavsFileSystemProvider

object FileSystemProviders {
    /**
     * If set, WatchService implementations will skip processing any event data and simply send an
     * overflow event to all the registered keys upon successful read from the inotify fd. This can
     * help reducing the JNI and GC overhead when large amount of inotify events are generated.
     * Simply sending an overflow event to all the keys is okay because we use only one key per
     * service for WatchServicePathObservable.
     */
    @Volatile
    var overflowWatchEvents = false

    fun install() {
        // Runs on the cold-start critical path; log slow providers (see `adb logcat -s
        // AppStart`) so native-library loading regressions are easy to spot.
        fun installProvider(name: String, install: () -> Unit) {
            val startUptimeMillis = android.os.SystemClock.uptimeMillis()
            install()
            val durationMillis = android.os.SystemClock.uptimeMillis() - startUptimeMillis
            if (durationMillis >= SLOW_PROVIDER_LOG_THRESHOLD_MILLIS) {
                android.util.Log.i("AppStart", "install $name took ${durationMillis}ms")
            }
        }
        installProvider("Linux") {
            FileSystemProvider.installDefaultProvider(LinuxFileSystemProvider)
        }
        installProvider("Archive") {
            FileSystemProvider.installProvider(ArchiveFileSystemProvider)
        }
        if (!isRunningAsRoot) {
            installProvider("Content") {
                FileSystemProvider.installProvider(ContentFileSystemProvider)
            }
            installProvider("Document") {
                FileSystemProvider.installProvider(DocumentFileSystemProvider)
            }
            installProvider("Ftp") {
                FileSystemProvider.installProvider(FtpFileSystemProvider)
            }
            installProvider("Ftps") {
                FileSystemProvider.installProvider(FtpsFileSystemProvider)
            }
            installProvider("Ftpes") {
                FileSystemProvider.installProvider(FtpesFileSystemProvider)
            }
            installProvider("Smb") {
                FileSystemProvider.installProvider(SmbFileSystemProvider)
            }
            installProvider("WebDav") {
                FileSystemProvider.installProvider(WebDavFileSystemProvider)
            }
            installProvider("WebDavs") {
                FileSystemProvider.installProvider(WebDavsFileSystemProvider)
            }
        }
        Files.installFileTypeDetector(AndroidFileTypeDetector)
    }

    /**
     * Loads the SFTP provider's class graph (SSHJ + BouncyCastle, roughly a second of
     * class loading/verification) without registering anything. Call it on a background
     * thread before [installSftp] so the cost stays off the cold-start critical path.
     */
    fun warmUpSftpClass() {
        Class.forName(SFTP_PROVIDER_CLASS_NAME, true, java8.nio.file.spi.FileSystemProvider::class.java.classLoader)
    }

    /**
     * Registers the SFTP provider. Must be called on the main thread after
     * [warmUpSftpClass]: the provider registry is only ever mutated from the main
     * thread, so concurrent readers of installedProviders() never race an install.
     */
    fun installSftp() {
        if (isRunningAsRoot) {
            return
        }
        val startUptimeMillis = android.os.SystemClock.uptimeMillis()
        FileSystemProvider.installProvider(SftpFileSystemProvider)
        val durationMillis = android.os.SystemClock.uptimeMillis() - startUptimeMillis
        if (durationMillis >= SLOW_PROVIDER_LOG_THRESHOLD_MILLIS) {
            android.util.Log.i("AppStart", "install Sftp took ${durationMillis}ms")
        }
    }

    private const val SFTP_PROVIDER_CLASS_NAME =
        "me.zhanghai.android.files.provider.sftp.SftpFileSystemProvider"

    private const val SLOW_PROVIDER_LOG_THRESHOLD_MILLIS = 5L

    operator fun get(scheme: String): FileSystemProvider {
        for (provider in FileSystemProvider.installedProviders()) {
            if (provider.scheme.equals(scheme, ignoreCase = true)) {
                return provider
            }
        }
        throw ProviderNotFoundException(scheme)
    }
}
