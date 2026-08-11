/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import android.os.AsyncTask
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File
import java.util.zip.ZipFile
import java8.nio.file.Files
import java8.nio.file.Path
import me.zhanghai.android.files.util.DataState

class DexFileViewModel(
    private val path: Path,
    private val cacheDirectory: File
) : ViewModel() {

    private val _dexFileLiveData = MutableLiveData<DataState<DexFile>>()
    val dexFileLiveData: LiveData<DataState<DexFile>>
        get() = _dexFileLiveData

    init {
        load()
    }

    private var disassembler: DexDisassembler? = null

    fun load() {
        _dexFileLiveData.value = DataState.Loading()
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value: DataState<DexFile> = try {
                val dexFile = if (path.toString().endsWith(".apk", ignoreCase = true)) {
                    parseApk()
                } else {
                    // Files.readAllBytes() requires channel.size(), which is unsupported on
                    // remote file systems (FTP/SFTP/SMB/WebDAV), so read the stream instead.
                    val bytes = Files.newInputStream(path).use { it.readBytes() }
                    DexParser.parse(bytes)
                }
                disassembler = DexDisassembler(dexFile)
                DataState.Success(dexFile)
            } catch (throwable: Throwable) {
                DataState.Error(null, throwable)
            }
            _dexFileLiveData.postValue(value)
        }
    }

    /**
     * Opens an APK (a ZIP) and parses every classesN.dex entry inside, merging them into
     * one aggregate [DexFile]. The DexFile fields are fully parsed values (strings, not
     * indices), so concatenating the lists is safe — no cross-dex index remapping needed.
     *
     * Mirrors ApkStringSearchViewModel: copy to a local cache file first (ZipFile needs
     * random access, which remote file systems may not support), and guard against zip
     * bombs by capping the entry size.
     */
    private fun parseApk(): DexFile {
        cacheDirectory.mkdirs()
        // Unique cache name per input path: concurrent analyses of different APKs must
        // not overwrite each other's cache file.
        val cacheFile = File(
            cacheDirectory, "dex-analyze-${path.toString().hashCode()}.apk"
        )
        Files.newInputStream(path).use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        val strings = ArrayList<String>()
        val types = ArrayList<String>()
        val fields = ArrayList<DexFieldRef>()
        val methods = ArrayList<DexMethodRef>()
        val classes = ArrayList<DexClass>()
        var version = ""
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
                // Only entries named classesN.dex are DEX files.
                if (!(name.startsWith("classes") && name.endsWith(".dex"))) {
                    continue
                }
                // Read at most MAX_ENTRY_SIZE + 1 bytes: a zip bomb can fake the entry
                // size field, and readBytes() would then OOM the process.
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
                try {
                    val dexFile = DexParser.parse(bytes)
                    if (version.isEmpty()) {
                        version = dexFile.version
                    }
                    strings.addAll(dexFile.strings)
                    types.addAll(dexFile.types)
                    fields.addAll(dexFile.fields)
                    methods.addAll(dexFile.methods)
                    // Record which classesN.dex entry each class came from, so the dex++
                    // editor can reassemble and write back the right entry.
                    classes.addAll(dexFile.classes.map { it.copy(sourceDex = name) })
                } catch (e: Exception) {
                    // Skip unparsable dex entries; one bad dex must not fail the whole APK
                    // (the parser can throw more than DexParseException).
                }
            }
        }
        if (classes.isEmpty() && strings.isEmpty()) {
            throw DexParseException("No classes.dex found in APK")
        }
        return DexFile(version, strings, types, fields, methods, classes)
    }

    fun dexFile(): DexFile? = (dexFileLiveData.value as? DataState.Success)?.data

    fun disassemble(method: DexMethodDef): String = disassembler?.disassemble(method) ?: ""

    fun classByName(className: String): DexClass? =
        dexFile()?.classes?.find { it.className == className }

    fun findClassReferences(typeName: String): List<Pair<String, String>> =
        dexFile()?.findClassReferences(typeName) ?: emptyList()

    fun findMethodReferences(methodKey: String): List<Pair<String, String>> =
        dexFile()?.findMethodReferences(methodKey) ?: emptyList()

    fun findFieldReferences(fieldKey: String): List<Pair<String, String>> =
        dexFile()?.findFieldReferences(fieldKey) ?: emptyList()

    private companion object {
        const val MAX_ENTRY_SIZE: Long = 256L * 1024 * 1024
    }
}
