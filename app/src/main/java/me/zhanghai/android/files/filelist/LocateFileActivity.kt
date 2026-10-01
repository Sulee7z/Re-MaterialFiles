/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.ContentResolver
import android.content.Intent
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import java.io.File
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.compat.DocumentsContractCompat
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.util.getParcelableExtraSafe
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import org.xmlpull.v1.XmlPullParser

/**
 * System "locate file" entry (MT Manager style), reachable from BOTH the share sheet
 * (ACTION_SEND with EXTRA_STREAM) and the "open with" chooser (ACTION_VIEW with data):
 * resolves the file's real filesystem path and opens the file list at its parent directory
 * with the file selected.
 *
 * URI resolution, mirroring MT Manager's approach:
 * 1. Platform external-storage document IDs (content://com.android.externalstorage.documents);
 * 2. Providers exposing a _data column (MediaStore, Downloads, ...; readable because the app
 *    holds All-Files-Access);
 * 3. Generic third-party FileProviders: the real path is read back from the opened file
 *    descriptor via /proc/self/fd/N (this is what makes QQ's
 *    content://com.tencent.mobileqq.fileprovider URIs work);
 * 4. Fallback: parse the provider's android.support.FILE_PROVIDER_PATHS metadata and map the
 *    URI path under the declared roots, with a canonical-path containment check.
 */
class LocateFileActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = resolveLocatedPath(intent)
        val directory = path?.parent
        if (directory == null) {
            showToast(R.string.locate_file_error)
            finish()
            return
        }
        startActivity(
            FileListActivity.createViewIntent(directory)
                .putExtra(FileListActivity.EXTRA_LOCATE_FILE_NAME, path.fileName.toString())
        )
        finish()
    }

    private fun resolveLocatedPath(intent: Intent): Path? {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            else -> intent.getParcelableExtraSafe<Uri>(Intent.EXTRA_STREAM)
        } ?: return null
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE, null ->
                uri.path?.takeIfNotEmpty()?.let { runCatching { Paths.get(it) }.getOrNull() }
            ContentResolver.SCHEME_CONTENT -> resolveContentUri(uri)
            else -> null
        }
    }

    private fun resolveContentUri(uri: Uri): Path? {
        documentsUriPath(uri)?.let { return it }
        queryDataPath(uri)?.let { return it }
        fileDescriptorPath(uri)?.let { return it }
        return fileProviderPathsPath(uri)
    }

    /**
     * content://com.android.externalstorage.documents/document/primary%3ADownload%2Ffoo.txt
     * → /storage/emulated/0/Download/foo.txt (the standard system file-picker scheme).
     */
    private fun documentsUriPath(uri: Uri): Path? {
        if (uri.authority != DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
            return null
        }
        val segments = uri.pathSegments
        val documentId = when (segments.size) {
            2 -> if (segments[0] == "document") segments[1] else null
            4 -> if (segments[0] == "tree" && segments[2] == "document") segments[3] else null
            else -> null
        } ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) {
            return null
        }
        val (volume, relativePath) = parts
        val root = when (volume) {
            DocumentsContractCompat.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, "home" ->
                Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        return runCatching {
            if (relativePath.isEmpty()) Paths.get(root) else Paths.get(root, relativePath)
        }.getOrNull()
    }

    /** Generic provider fallback: read the real path from the _data column. */
    private fun queryDataPath(uri: Uri): Path? = try {
        contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIfNotEmpty()?.let {
                        runCatching { Paths.get(it) }.getOrNull()
                    }
                } else {
                    null
                }
            }
    } catch (e: Exception) {
        null
    }

    /**
     * Opens the shared URI and reads the real file path back from the file descriptor
     * (/proc/self/fd/N), like MT Manager: third-party FileProviders (e.g. QQ's) don't expose
     * _data, but the descriptor always points at the real file. Mount aliases are rewritten
     * to the /storage form the file list knows how to browse. The descriptor is the proof of
     * existence, so no local existence check is done here — that would wrongly reject
     * Android/data paths when All-Files-Access is not granted (the file list routes those
     * through root/Shizuku instead).
     */
    private fun fileDescriptorPath(uri: Uri): Path? {
        val descriptor = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        return descriptor.use {
            val link = try {
                Os.readlink("/proc/self/fd/${it.fd}")
            } catch (e: Exception) {
                null
            } ?: return null
            if (!link.startsWith("/") || link.startsWith("/memfd:") || link.startsWith("/dev/")) {
                return null
            }
            var path = link
            path = MOUNT_USER_EMULATED.replaceFirst(path, "/storage/emulated/\$1\$2")
            path = MOUNT_RUNTIME_EMULATED.replaceFirst(path, "/storage/emulated/\$1\$2")
            runCatching { Paths.get(path) }.getOrNull()
        }
    }

    /**
     * Fallback for FileProviders whose descriptor can't be read: parse the provider's
     * android.support.FILE_PROVIDER_PATHS metadata and map the URI path (it always starts
     * with the root's name) under the configured root, with a containment check.
     */
    private fun fileProviderPathsPath(uri: Uri): Path? {
        val authority = uri.authority ?: return null
        val providerInfo = try {
            packageManager.resolveContentProvider(authority, 0)
        } catch (e: Exception) {
            null
        } ?: return null
        val metaData = providerInfo.metaData ?: return null
        val pathsResId = metaData.getInt(META_DATA_FILE_PROVIDER_PATHS, 0)
        if (pathsResId == 0) {
            return null
        }
        val providerPackage = providerInfo.packageName
        val roots = try {
            val resources = packageManager.getResourcesForApplication(providerPackage)
            parseFileProviderRoots(resources.getXml(pathsResId), providerPackage)
        } catch (e: Exception) {
            return null
        }
        val segments = uri.pathSegments
        if (segments.isEmpty()) {
            return null
        }
        val root = roots[segments.first()] ?: return null
        val relativeSegments = segments.drop(1)
        // Lexical traversal guard (canonicalization is avoided: it needs execute permission
        // on every parent, which Android/data paths don't grant without root).
        if (relativeSegments.any { it == ".." }) {
            return null
        }
        val file = File(root, relativeSegments.joinToString(File.separator))
        // Root-aware existence check: goes through the app's provider, which routes
        // Android/data to root/Shizuku when needed.
        val path = runCatching { Paths.get(file.path) }.getOrNull() ?: return null
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        return path
    }

    @Suppress("DEPRECATION")
    private fun parseFileProviderRoots(
        parser: XmlResourceParser,
        providerPackage: String
    ): Map<String, File> {
        val external = Environment.getExternalStorageDirectory()
        val roots = mutableMapOf<String, File>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                        ?: parser.getAttributeValue(null, "name")
                        ?: continue
                    val relativePath = parser.getAttributeValue(ANDROID_NAMESPACE, "path")
                        ?: parser.getAttributeValue(null, "path")
                        ?: "."
                    // Same base directories FileProvider itself uses; constructed from the
                    // provider's package because we only need the path strings here.
                    val base = when (parser.name) {
                        "root-path" -> File(File.separator)
                        "files-path" -> File("/data/data/$providerPackage/files")
                        "cache-path" -> File("/data/data/$providerPackage/cache")
                        "external-path" -> external
                        "external-files-path" ->
                            File(external, "Android/data/$providerPackage/files")
                        "external-cache-path" ->
                            File(external, "Android/data/$providerPackage/cache")
                        "external-media-path" ->
                            File(external, "Android/media/$providerPackage")
                        else -> null
                    }
                    if (base != null) {
                        roots[name] = File(base, relativePath)
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return roots
    }

    private companion object {
        const val META_DATA_FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val MOUNT_USER_EMULATED = Regex("^/mnt/user/\\d+/emulated/(\\d+)(/|$)")
        val MOUNT_RUNTIME_EMULATED =
            Regex("^/mnt/runtime/(?:default|read|write|full)/emulated/(\\d+)(/|$)")
    }
}
