/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import roro.stellar.Stellar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SuiFileServiceLauncher {
    private val lock = Any()

    private var isSuiIntialized = false

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isSuiAvailable(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            if (!isSuiIntialized) {
                Sui.init(application.packageName)
                isSuiIntialized = true
            }
            return Sui.isSui()
        }
    }

    // Shizuku can be started with ADB or root. When it is started with ADB (without root/Sui),
    // the user service will run as shell (uid 2000) which is allowed to access Android/data of
    // other applications via the Linux file system, so the user can browse it without root.
    // @see <a href="https://shizuku.rikka.app/introduction/">Shizuku</a>
    // Only checks whether the Shizuku binder is reachable, regardless of whether our permission
    // has been granted yet; the permission request is handled in launchService() so that it can
    // be triggered from file access instead of being silently skipped.
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isShizukuBinderAvailable(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            return try {
                Shizuku.pingBinder()
            } catch (e: Throwable) {
                // Shizuku isn't installed, or the binder isn't available yet.
                false
            }
        }
    }

    // The Stellar manager (a Shizuku fork) exposes a Shizuku-compatible binder when its
    // compatibility layer is enabled, so Shizuku.pingBinder() alone does not mean the real
    // Shizuku manager is present. Only the real Shizuku manager can run our user service
    // through the original Shizuku startUserService() path (the Stellar manager's
    // attachUserService() cannot unmarshal the BinderContainer and fails), so check that
    // the Shizuku manager is actually installed before preferring that path.
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isShizukuManagerInstalled(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            return try {
                application.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE_NAME, 0) != null
            } catch (e: Throwable) {
                // The Shizuku manager isn't installed.
                false
            }
        }
    }

    private const val SHIZUKU_MANAGER_PACKAGE_NAME = "moe.shizuku.privileged.api"

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isShizukuAvailable(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            return try {
                Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) {
                // Shizuku isn't installed, or the binder isn't available yet.
                false
            }
        }
    }

    // Stellar (https://github.com/roro2239/Stellar) is a Shizuku fork with its own
    // privileged API framework; support is kept alongside the original Shizuku
    // integration. Like Shizuku it can be started with ADB or root.
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isStellarBinderAvailable(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            return try {
                Stellar.pingBinder()
            } catch (e: Throwable) {
                // Stellar isn't installed, or the binder isn't available yet.
                false
            }
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isStellarAvailable(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            return try {
                Stellar.pingBinder() && Stellar.checkSelfPermission()
            } catch (e: Throwable) {
                // Stellar isn't installed, or the binder isn't available yet.
                false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        synchronized(lock) {
            if (!isSuiAvailable() && !isShizukuBinderAvailable() && !isStellarBinderAvailable()) {
                throw RemoteFileSystemException("Shizuku/Stellar isn't available")
            }
            // The original Shizuku backend takes precedence, but only when the real Shizuku
            // manager is installed: the Stellar manager's Shizuku compatibility layer also
            // answers Shizuku.pingBinder(), yet its startUserService/attachUserService path
            // cannot deliver our user service binder, so fall back to the Stellar-specific
            // path in that case.
            return if (isShizukuBinderAvailable() && isShizukuManagerInstalled()) {
                launchShizukuService()
            } else {
                launchStellarService()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(RemoteFileSystemException::class)
    private fun launchShizukuService(): IRemoteFileService {
        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            // Shizuku was uninstalled (or its binder died) since the availability
            // probe: surface a proper RemoteFileSystemException instead of crashing.
            throw RemoteFileSystemException(e)
        }
        if (!permissionGranted) {
            val granted = try {
                runBlocking<Boolean> {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : Shizuku.OnRequestPermissionResultListener {
                            override fun onRequestPermissionResult(
                                requestCode: Int,
                                grantResult: Int
                            ) {
                                Shizuku.removeRequestPermissionResultListener(this)
                                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                                continuation.resume(granted)
                            }
                        }
                        Shizuku.addRequestPermissionResultListener(listener)
                        continuation.invokeOnCancellation {
                            Shizuku.removeRequestPermissionResultListener(listener)
                        }
                        Shizuku.requestPermission(listener.hashCode())
                    }
                }
            } catch (e: InterruptedException) {
                throw RemoteFileSystemException(e)
            } catch (e: Throwable) {
                // The permission dialog cannot be shown (Shizuku gone mid-request):
                // degrade gracefully.
                throw RemoteFileSystemException(e)
            }
            if (!granted) {
                throw RemoteFileSystemException("Sui permission isn't granted")
            }
        }
        return try {
            runBlocking {
                try {
                    withTimeout(RootFileService.TIMEOUT_MILLIS) {
                        suspendCancellableCoroutine { continuation ->
                            val serviceArgs = Shizuku.UserServiceArgs(
                                ComponentName(application, SuiFileServiceInterface::class.java)
                            )
                                .debuggable(BuildConfig.DEBUG)
                                .daemon(false)
                                .processNameSuffix("sui")
                                .version(BuildConfig.VERSION_CODE)
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(
                                    name: ComponentName,
                                    service: IBinder
                                ) {
                                    val serviceInterface =
                                        IRemoteFileService.Stub.asInterface(service)
                                    continuation.resume(serviceInterface)
                                }

                                override fun onServiceDisconnected(name: ComponentName) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(
                                                "Sui service disconnected"
                                            )
                                        )
                                    }
                                }

                                override fun onBindingDied(name: ComponentName) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            RemoteFileSystemException("Sui binding died")
                                        )
                                    }
                                }

                                override fun onNullBinding(name: ComponentName) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            RemoteFileSystemException("Sui binding is null")
                                        )
                                    }
                                }
                            }
                            Shizuku.bindUserService(serviceArgs, connection)
                            continuation.invokeOnCancellation {
                                Shizuku.unbindUserService(serviceArgs, connection, true)
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw RemoteFileSystemException(e)
                }
            }
        } catch (e: InterruptedException) {
            throw RemoteFileSystemException(e)
        } catch (e: Throwable) {
            // Shizuku went away while binding: degrade gracefully.
            throw RemoteFileSystemException(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(RemoteFileSystemException::class)
    private fun launchStellarService(): IRemoteFileService {
        val permissionGranted = try {
            Stellar.checkSelfPermission()
        } catch (e: Throwable) {
            // Stellar manager was uninstalled (or disconnected) since the availability
            // probe: surface a proper RemoteFileSystemException instead of crashing.
            throw RemoteFileSystemException(e)
        }
        if (!permissionGranted) {
            val granted = try {
                runBlocking<Boolean> {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : Stellar.OnRequestPermissionResultListener {
                            override fun onRequestPermissionResult(
                                requestCode: Int, allowed: Boolean, onetime: Boolean
                            ) {
                                Stellar.removeRequestPermissionResultListener(this)
                                continuation.resume(allowed)
                            }
                        }
                        Stellar.addRequestPermissionResultListener(listener)
                        continuation.invokeOnCancellation {
                            Stellar.removeRequestPermissionResultListener(listener)
                        }
                        Stellar.requestPermission(requestCode = listener.hashCode())
                    }
                }
            } catch (e: InterruptedException) {
                throw RemoteFileSystemException(e)
            } catch (e: Throwable) {
                // The permission dialog cannot be shown (Stellar gone mid-request):
                // degrade gracefully.
                throw RemoteFileSystemException(e)
            }
            if (!granted) {
                throw RemoteFileSystemException("Stellar permission isn't granted")
            }
        }
        return try {
            runBlocking {
                try {
                    withTimeout(RootFileService.TIMEOUT_MILLIS) {
                        suspendCancellableCoroutine { continuation ->
                            val callback = object : StellarUserServiceCompat.ServiceCallback {
                                override fun onServiceConnected(service: IBinder) {
                                    continuation.resume(
                                        IRemoteFileService.Stub.asInterface(service)
                                    )
                                }

                                override fun onServiceDisconnected() {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(
                                                "Stellar service disconnected"
                                            )
                                        )
                                    }
                                }

                                override fun onServiceStartFailed(errorCode: Int, message: String) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(
                                                "Stellar service start failed: $message"
                                            )
                                        )
                                    }
                                }
                            }
                            StellarUserServiceCompat.expectBinder(callback)
                            try {
                                // Start the user service process directly through
                                // Stellar.newProcess() with THIS app's APK as the
                                // CLASSPATH (same sh -c form the Stellar manager uses for
                                // its UserServiceStarter), instead of the manager's
                                // startUserService() (whose attachUserService cannot
                                // unmarshal the BinderContainer and fails). The process
                                // delivers its binder back through our StellarProviderCompat.
                                val apkPath = application.packageManager
                                    .getApplicationInfo(application.packageName, 0).sourceDir
                                val processName = "${application.packageName}:stellar"
                                val command = "CLASSPATH='$apkPath' /system/bin/app_process " +
                                    "/system/bin --nice-name='$processName' " +
                                    "me.zhanghai.android.files.provider.root.StellarUserServiceMain"
                                val cmd: Array<String?> = arrayOf("sh", "-c", command)
                                val process = Stellar.newProcess(cmd, null, null)
                                stellarProcess = process
                            } catch (e: Throwable) {
                                StellarUserServiceCompat.onUserServiceStartFailed(
                                    -1, "newProcess failed: ${e.message}"
                                )
                            }
                            continuation.invokeOnCancellation {
                                stellarProcess?.destroy()
                                stellarProcess = null
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw RemoteFileSystemException(e)
                }
            }
        } catch (e: InterruptedException) {
            throw RemoteFileSystemException(e)
        } catch (e: Throwable) {
            // Stellar went away while binding: degrade gracefully.
            throw RemoteFileSystemException(e)
        }
    }

    private var stellarProcess: roro.stellar.StellarRemoteProcess? = null
}

@Keep
@RequiresApi(Build.VERSION_CODES.M)
class SuiFileServiceInterface : RemoteFileServiceInterface() {
    init {
        RootFileService.main()
    }
}
