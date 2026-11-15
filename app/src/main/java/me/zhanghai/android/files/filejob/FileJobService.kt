/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.annotation.MainThread
import java8.nio.file.Path
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.navigation.NavigationItemListLiveData
import me.zhanghai.android.files.util.ForegroundNotificationManager
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.removeFirst
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.compat.removeFirstCompat

class FileJobService : Service() {
    private lateinit var wakeWifiLock: WakeWifiLock

    internal lateinit var notificationManager: ForegroundNotificationManager
        private set

    private val executorService = Executors.newCachedThreadPool()

    private val runningJobs = mutableMapOf<FileJob, Future<*>>()
    private var lastStartId = 0
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()

        wakeWifiLock = WakeWifiLock(FileJobService::class.java.simpleName)
        notificationManager = ForegroundNotificationManager(this)
        instance = this
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            notificationManager.start(
                fileJobNotificationTemplate.createBuilder(this)
                    .setContentTitle(getString(R.string.notification_channel_file_job_name))
                    .setProgress(0, 0, true)
                    .build()
            )
        } catch (e: RuntimeException) {
            // Do not retain jobs that failed to acquire foreground-service eligibility.
            // Replaying them on a later request could unexpectedly mutate files.
            pendingJobs.clear()
            e.printStackTrace()
            showToast(e.toString())
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        while (pendingJobs.isNotEmpty()) {
            startJob(pendingJobs.removeFirstCompat())
        }
        stopIfIdle()
        // Jobs and their progress only live in memory; a sticky restart cannot resume them.
        return START_NOT_STICKY
    }

    private val jobCount: Int
        get() = synchronized(runningJobs) { runningJobs.size }

    private fun startJob(job: FileJob) {
        // Synchronize on runningJobs to prevent a job from removing itself before being added.
        synchronized(runningJobs) {
            val future = object : FutureTask<Unit>({ job.runOn(this@FileJobService) }, Unit) {
                override fun run() {
                    try {
                        super.run()
                    } finally {
                        // Also runs for cancellation before the task starts. A cancelled
                        // task remains counted until its worker has actually finished.
                        mainHandler.post {
                            if (!isDestroyed) {
                                synchronized(runningJobs) {
                                    runningJobs.remove(job)
                                    updateWakeWifiLockLocked()
                                }
                                if (jobCount == 0) {
                                    stopIfIdle()
                                    NavigationItemListLiveData.refresh()
                                }
                            }
                        }
                    }
                }
            }
            runningJobs[job] = future
            updateWakeWifiLockLocked()
            executorService.execute(future)
        }
    }

    @MainThread
    private fun stopIfIdle() {
        if (jobCount != 0 || pendingJobs.isNotEmpty()) {
            return
        }
        // stopSelfResult protects a newer start request not yet delivered to onStartCommand.
        if (stopSelfResult(lastStartId)) {
            if (instance === this) {
                instance = null
            }
            notificationManager.stop()
        }
    }

    private fun cancelJob(id: Int) {
        synchronized(runningJobs) {
            runningJobs.entries.find { it.key.id == id }?.value?.cancel(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isDestroyed = true
        if (instance === this) {
            instance = null
        }

        synchronized(runningJobs) {
            while (runningJobs.isNotEmpty()) {
                runningJobs.removeFirst().value.cancel(true)
            }
            updateWakeWifiLockLocked()
        }
        executorService.shutdown()
        notificationManager.stop()
    }

    // Synchronize on runningJobs to avoid the potential race condition that the lock is
    // acquired after all jobs are finished in a very short time.
    private fun updateWakeWifiLockLocked() {
        wakeWifiLock.isAcquired = jobCount > 0
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private var instance: FileJobService? = null

        private val pendingJobs = mutableListOf<FileJob>()

        val runningJobCount: Int
            @MainThread
            get() = instance?.jobCount ?: 0

        private fun startJob(job: FileJob, context: Context) {
            val appContext = context.applicationContext
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { startJob(job, appContext) }
                return
            }
            pendingJobs.add(job)
            try {
                // Always go through the system, including when an instance already exists.
                ContextCompat.startForegroundService(
                    appContext, Intent(appContext, FileJobService::class.java)
                )
            } catch (e: RuntimeException) {
                pendingJobs.remove(job)
                e.printStackTrace()
                appContext.showToast(e.toString())
            }
        }

        fun archive(
            sources: List<Path>,
            archiveFile: Path,
            format: Int,
            filter: Int,
            password: String?,
            context: Context
        ) {
            startJob(ArchiveFileJob(sources, archiveFile, format, filter, password), context)
        }

        fun copy(sources: List<Path>, targetDirectory: Path, context: Context) {
            startJob(CopyFileJob(sources, targetDirectory), context)
        }

        fun create(path: Path, createDirectory: Boolean, context: Context) {
            startJob(CreateFileJob(path, createDirectory), context)
        }

        fun delete(paths: List<Path>, context: Context) {
            startJob(DeleteFileJob(paths), context)
        }

        fun trashDelete(paths: List<Path>, context: Context) {
            startJob(TrashFileJob(paths), context)
        }

        fun trashCleanup(context: Context) {
            startJob(TrashCleanupJob(), context)
        }

        fun clearTrash(context: Context) {
            startJob(ClearTrashJob(), context)
        }

        fun restoreFromTrash(entries: List<Pair<Path, Path>>, context: Context) {
            startJob(RestoreFromTrashJob(entries), context)
        }

        fun permanentDelete(paths: List<Path>, context: Context) {
            startJob(PermanentDeleteJob(paths), context)
        }

        fun move(sources: List<Path>, targetDirectory: Path, context: Context) {
            startJob(MoveFileJob(sources, targetDirectory), context)
        }

        fun installApk(file: Path, context: Context) {
            startJob(InstallApkJob(file), context)
        }

        fun open(file: Path, mimeType: MimeType, withChooser: Boolean, context: Context) {
            startJob(OpenFileJob(file, mimeType, withChooser), context)
        }

        fun rename(path: Path, newName: String, context: Context) {
            startJob(RenameFileJob(path, newName), context)
        }

        fun restoreSeLinuxContext(path: Path, recursive: Boolean, context: Context) {
            startJob(RestoreFileSeLinuxContextJob(path, recursive), context)
        }

        fun save(source: Path, target: Path, context: Context) {
            startJob(SaveFileJob(source, target), context)
        }

        fun setGroup(path: Path, group: PosixGroup, recursive: Boolean, context: Context) {
            startJob(SetFileGroupJob(path, group, recursive), context)
        }

        fun setMode(
            path: Path,
            mode: Set<PosixFileModeBit>,
            recursive: Boolean,
            uppercaseX: Boolean,
            context: Context
        ) {
            startJob(SetFileModeJob(path, mode, recursive, uppercaseX), context)
        }

        fun setOwner(path: Path, owner: PosixUser, recursive: Boolean, context: Context) {
            startJob(SetFileOwnerJob(path, owner, recursive), context)
        }

        fun setSeLinuxContext(
            path: Path,
            seLinuxContext: String,
            recursive: Boolean,
            context: Context
        ) {
            startJob(SetFileSeLinuxContextJob(path, seLinuxContext, recursive), context)
        }

        fun write(
            file: Path,
            content: ByteArray,
            context: Context,
            listener: ((Boolean) -> Unit)?
        ) {
            startJob(WriteFileJob(file, content, listener), context)
        }

        @MainThread
        fun cancelJob(id: Int) {
            pendingJobs.removeFirst { it.id == id }
            instance?.cancelJob(id)
        }
    }
}
