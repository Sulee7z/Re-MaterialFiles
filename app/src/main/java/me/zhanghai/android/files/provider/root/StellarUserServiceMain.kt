/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.AttributionSource
import android.content.Context
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.stellar.api.BinderContainer
import rikka.hidden.compat.ActivityManagerApis

/**
 * Entry point of the Stellar user service process, started by the app through
 * Stellar.newProcess() with this APK as the CLASSPATH (bypassing the Stellar manager's
 * startUserService()/attachUserService(), which is broken on the current manager build).
 *
 * Mirrors what the Stellar manager's UserServiceStarter does inside the manager:
 * initialize the Android runtime, create the service interface (which starts
 * RootFileService) and deliver the service binder back to the app's provider.
 */
@Keep
object StellarUserServiceMain {

    private const val TAG = "StellarUserServiceMain"

    private const val EXTRA_BINDER = "roro.stellar.manager.intent.extra.BINDER"
    private const val EXTRA_CLIENT_BINDER = "roro.stellar.manager.intent.extra.CLIENT_BINDER"
    private const val METHOD_SEND_USER_SERVICE = "sendUserService"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        // This process is an app_process (uid 2000) and cannot load native libraries from
        // the CLASSPATH APK; extract and load libsyscall.so before RootFileService starts.
        me.zhanghai.android.files.provider.linux.syscall.NativeLibraryLoader.ensureSyscallLibrary()
        try {
            // Same runtime bootstrap as the Stellar manager's UserServiceStarter.
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
            val systemContext = activityThreadClass.getMethod("getSystemContext")
                .invoke(activityThread) as Context
            val context = systemContext.createPackageContext(
                BuildConfigHolder.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
            // Instantiating the interface runs RootFileService.main() in its init block.
            // Load it through the package context's class loader (the same way the
            // Shizuku/Stellar managers' UserServiceStarter loads the service class) so
            // that its System.loadLibrary() calls can resolve libraries inside this APK;
            // the app_process system class loader only searches system library paths.
            val serviceClass = context.classLoader.loadClass(
                "me.zhanghai.android.files.provider.root.SuiFileServiceInterface"
            )
            val serviceInterface = serviceClass.getConstructor().newInstance()
            val binder = serviceInterface.javaClass.getMethod("asBinder").invoke(serviceInterface)
                as IBinder
            Log.i(TAG, "User service created, delivering binder")
            val extras = Bundle().apply {
                classLoader = BinderContainer::class.java.classLoader
                putParcelable(EXTRA_BINDER, BinderContainer(binder))
            }
            // ContentResolver.call() fails in this app_process process: the process (uid
            // 2000) has no package context, so the system rejects "calling package android
            // vs uid 2000". ActivityManager.getContentProviderExternal() is a hidden API
            // that plain reflection cannot see (and setHiddenApiExemptions() is silently
            // ignored for shell processes), so use rikka.hidden.compat.ActivityManagerApis,
            // the same library the Stellar manager's UserServiceStarter uses to deliver its
            // binder through "sendUserService".
            val authority = "${BuildConfigHolder.APPLICATION_ID}.stellar"
            val provider = ActivityManagerApis.getContentProviderExternal(
                authority, 0, null, BuildConfigHolder.APPLICATION_ID
            )
            if (provider == null || !provider.asBinder().pingBinder()) {
                throw IllegalStateException("Provider $authority is not reachable")
            }
            val callResult = callCompat(provider, authority, METHOD_SEND_USER_SERVICE, extras)
            Log.i(TAG, "Binder delivered to app provider, reply=$callResult")
            try {
                ActivityManagerApis.removeContentProviderExternal(authority, null)
            } catch (ignored: Throwable) {
            }
            // One-time mode, like the managers' UserServiceStarter: exit when the app's
            // client binder dies so no user service process outlives the app.
            callResult?.classLoader = BinderContainer::class.java.classLoader
            callResult?.getParcelable<BinderContainer>(EXTRA_CLIENT_BINDER)?.binder?.linkToDeath({
                Log.i(TAG, "Client app died, exiting user service")
                System.exit(0)
            }, 0)
            Log.i(TAG, "User service ready")
        } catch (t: Throwable) {
            Log.e(TAG, "User service startup failed", t)
            System.exit(1)
        }
        Looper.loop()
    }

    /**
     * Mirrors the Stellar manager's IContentProviderUtils.callCompat(): the IContentProvider
     * call signature changed across API levels, so pick the right one for this device.
     */
    private fun callCompat(
        provider: IContentProvider,
        authority: String,
        method: String,
        extras: Bundle
    ): Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        provider.call(
            AttributionSource.Builder(Process.myUid()).setPackageName(null).build(),
            authority, method, null, extras
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        provider.call(null as String?, null, authority, method, null, extras)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        provider.call(null as String?, authority, method, null, extras)
    } else {
        provider.call(null, method, null, extras)
    }

    /** Avoids referencing BuildConfig from a raw app_process classloader context issue. */
    private object BuildConfigHolder {
        const val APPLICATION_ID: String =
            me.zhanghai.android.files.BuildConfig.APPLICATION_ID
    }
}
