/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux.syscall

import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Loads the app's native libraries inside the app_process user service processes
 * (Stellar/Shizuku). Such processes start with CLASSPATH=<apk>, so their class loader
 * cannot resolve libraries inside the APK and plain System.loadLibrary() throws
 * UnsatisfiedLinkError; the libraries are extracted to /data/local/tmp (writable by
 * shell, uid 2000) and loaded with System.load() instead.
 */
object NativeLibraryLoader {

    private const val TAG = "NativeLibraryLoader"

    private const val EXTRACTION_DIR = "/data/local/tmp/me.zhanghai.android.files"

    private const val SYS_CALL_LIBRARY = "syscall"

    /** Extracts libsyscall.so from the CLASSPATH APK and loads it, if not yet loaded. */
    fun ensureSyscallLibrary(): Boolean {
        try {
            try {
                // Succeeds in normal app processes; fails in app_process user services.
                System.loadLibrary(SYS_CALL_LIBRARY)
                return true
            } catch (e: UnsatisfiedLinkError) {
                // Expected in user service processes; fall through to extraction.
            }
            val libraryPath = extractLibrary(SYS_CALL_LIBRARY) ?: return false
            System.load(libraryPath)
            Log.i(TAG, "Loaded lib$SYS_CALL_LIBRARY.so from $libraryPath")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load lib$SYS_CALL_LIBRARY.so", t)
            return false
        }
    }

    /**
     * Extracts lib<name>.so (matching this process's ABI) from the first CLASSPATH APK
     * that contains it, and returns the extraction path (or null if unavailable).
     */
    private fun extractLibrary(name: String): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val entryName = "lib/$abi/lib$name.so"
        val classPath = System.getProperty("java.class.path") ?: return null
        val apkPath = classPath.split(File.pathSeparator).firstOrNull { apk ->
            runCatching { ZipFile(apk).getEntry(entryName) != null }.getOrDefault(false)
        } ?: return null
        val dir = File(EXTRACTION_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        val output = File(dir, "lib$name.so")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            if (!output.exists() || output.length() != entry.size) {
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { outputStream -> input.copyTo(outputStream) }
                }
                output.setReadable(true, false)
                output.setExecutable(true, false)
            }
        }
        return output.absolutePath
    }
}
