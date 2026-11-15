/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.app.notificationManager
import me.zhanghai.android.files.compat.stopForegroundCompat

class ForegroundNotificationManager(private val service: Service) {
    private val notifications = mutableMapOf<Int, Notification>()

    private var foregroundId: Int? = null
    private var isForeground = false
    private lateinit var initialNotification: Notification

    // Called from onStartCommand before any file I/O. Progress callbacks must never be
    // responsible for promoting a service after the Activity has gone into the background.
    fun start(notification: Notification) {
        synchronized(notifications) {
            if (isForeground) {
                return
            }
            ServiceCompat.startForeground(
                service, NotificationIds.FILE_JOBS, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
            initialNotification = notification
            isForeground = true
        }
    }

    @SuppressLint("MissingPermission")
    fun notify(id: Int, notification: Notification) {
        synchronized(notifications) {
            check(isForeground) { "File job service has not entered the foreground" }
            if (foregroundId == null) {
                foregroundId = id
            }
            notificationManager.notify(
                if (id == foregroundId) NotificationIds.FILE_JOBS else id, notification
            )
            notifications[id] = notification
        }
    }

    @SuppressLint("MissingPermission")
    fun cancel(id: Int) {
        synchronized(notifications) {
            if (id !in notifications) {
                return
            }
            if (id == foregroundId) {
                if (notifications.size == 1) {
                    notifications -= id
                    foregroundId = null
                    // Other jobs may still be running without having posted progress yet.
                    // Only the service's idle check may stop foreground mode.
                    notificationManager.notify(NotificationIds.FILE_JOBS, initialNotification)
                } else {
                    notifications.entries.find { it.key != id }!!.let {
                        notificationManager.notify(NotificationIds.FILE_JOBS, it.value)
                        notificationManager.cancel(it.key)
                        foregroundId = it.key
                    }
                    notifications -= id
                }
            } else {
                notificationManager.cancel(id)
                notifications -= id
            }
        }
    }

    fun stop() {
        synchronized(notifications) {
            notifications.keys.forEach { notificationManager.cancel(it) }
            notifications.clear()
            if (isForeground) {
                service.stopForegroundCompat(ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            isForeground = false
            foregroundId = null
        }
    }
}
