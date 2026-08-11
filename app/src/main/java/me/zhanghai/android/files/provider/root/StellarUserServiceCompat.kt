/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Client side of the Stellar user-service binder delivery. The Stellar manager's
 * startUserService() path is unusable with the current manager build (its
 * StellarService.attachUserService() fails to unmarshal com.stellar.api.BinderContainer),
 * so instead the user service process is started directly via Stellar.newProcess() and
 * delivers its binder back to this app through the StellarProviderCompat provider call.
 *
 * Stellar (https://github.com/roro2239/Stellar) is a Shizuku fork; this support is kept
 * alongside the original Shizuku integration.
 */
object StellarUserServiceCompat {

    interface ServiceCallback {
        fun onServiceConnected(service: IBinder)
        fun onServiceDisconnected()
        fun onServiceStartFailed(errorCode: Int, message: String) {}
    }

    private class PendingConnection(
        val callback: ServiceCallback,
        val handler: Handler?
    )

    private val pendingConnections = CopyOnWriteArrayList<PendingConnection>()

    /** Registers a connection that is waiting for the user service binder. */
    fun expectBinder(callback: ServiceCallback, handler: Handler? = Handler(Looper.getMainLooper())) {
        pendingConnections.add(PendingConnection(callback, handler))
    }

    /**
     * Called by [StellarProviderCompat] when the user service process delivers its
     * binder through the "sendUserService" provider call. Delivers it to the first
     * waiting connection.
     */
    fun onUserServiceBinder(binder: IBinder) {
        val connection = pendingConnections.removeFirstOrNull() ?: run {
            Log.w(TAG, "Received user service binder but no connection is waiting")
            return
        }
        dispatchCallback(connection.handler) {
            connection.callback.onServiceConnected(binder)
        }
    }

    /** Notifies the first waiting connection that startup failed. */
    fun onUserServiceStartFailed(errorCode: Int, message: String) {
        val connection = pendingConnections.removeFirstOrNull() ?: return
        dispatchCallback(connection.handler) {
            connection.callback.onServiceStartFailed(errorCode, message)
        }
    }

    /** Notifies all waiting connections that the Stellar service went away. */
    fun onBinderDead() {
        while (pendingConnections.isNotEmpty()) {
            val connection = pendingConnections.removeFirstOrNull() ?: break
            dispatchCallback(connection.handler) {
                connection.callback.onServiceDisconnected()
            }
        }
    }

    private fun dispatchCallback(handler: Handler?, action: () -> Unit) {
        if (handler != null) {
            handler.post(action)
        } else {
            action()
        }
    }

    private const val TAG = "StellarUserServiceCompat"
}
