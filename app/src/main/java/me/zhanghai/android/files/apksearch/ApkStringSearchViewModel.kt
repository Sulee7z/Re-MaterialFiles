/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksearch

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File
import java.util.zip.ZipFile
import java8.nio.file.Files
import java8.nio.file.Path
import me.zhanghai.android.files.dex.DexParseException
import me.zhanghai.android.files.dex.DexParser
import me.zhanghai.android.files.util.DataState

data class ApkString(val entryName: String, val string: String)

class ApkStringSearchViewModel(
    private val path: Path,
    private val cacheDirectory: File
) : ViewModel() {

    private val _stringsLiveData = MutableLiveData<DataState<List<ApkString>>>()
    val stringsLiveData: LiveData<DataState<List<ApkString>>>
        get() = _stringsLiveData

    init {
        load()
    }

    fun load() {
        _stringsLiveData.value = DataState.Loading()
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value: DataState<List<ApkString>> = try {
                val strings = ArrayList<ApkString>()
                cacheDirectory.mkdirs()
                // Unique cache name per input path: two searches running concurrently on
                // different APKs must not overwrite each other's cache file.
                val cacheFile = File(
                    cacheDirectory, "apk-string-search-${path.toString().hashCode()}.apk"
                )
                Files.newInputStream(path).use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                ZipFile(cacheFile).use { zipFile ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) {
                            continue
                        }
                        val name = entry.name
                        if (entry.size > MAX_ENTRY_SIZE) {
                            continue
                        }
                        // Read at most MAX_ENTRY_SIZE + 1 bytes: a zip bomb can fake the
                        // entry size field, and readBytes() would then OOM the process.
                        val bytes = zipFile.getInputStream(entry).use { input ->
                            val buffer = ByteArray((MAX_ENTRY_SIZE + 1).toInt())
                            var offset = 0
                            while (offset < buffer.size) {
                                val read = input.read(buffer, offset, buffer.size - offset)
                                if (read == -1) {
                                    break
                                }
                                offset += read
                            }
                            buffer.copyOf(offset)
                        }
                        if (bytes.size > MAX_ENTRY_SIZE) {
                            continue
                        }
                        if (name.startsWith("classes") && name.endsWith(".dex")) {
                            try {
                                val dexFile = DexParser.parse(bytes)
                                dexFile.strings.forEach { string ->
                                    strings.add(ApkString(name, string))
                                }
                            } catch (e: Exception) {
                                // Skip unparsable dex entries; one bad dex must not fail the
                                // whole APK (the parser can throw more than DexParseException).
                            }
                        } else if (name.endsWith(".so")) {
                            extractAsciiStrings(bytes).forEach { string ->
                                strings.add(ApkString(name, string))
                            }
                        }
                    }
                }
                DataState.Success(strings)
            } catch (throwable: Throwable) {
                DataState.Error(null, throwable)
            }
            _stringsLiveData.postValue(value)
        }
    }

    private fun extractAsciiStrings(bytes: ByteArray): List<String> {
        val strings = ArrayList<String>()
        val builder = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xff
            if (byte in 0x20..0x7e) {
                builder.append(byte.toChar())
            } else {
                if (builder.length >= 4) {
                    strings.add(builder.toString())
                }
                builder.setLength(0)
            }
            index++
        }
        if (builder.length >= 4) {
            strings.add(builder.toString())
        }
        return strings
    }

    companion object {
        private const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
    }
}
