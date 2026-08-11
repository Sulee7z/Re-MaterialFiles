/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkkiller

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import me.zhanghai.android.files.apksign.AutoSigner
import me.zhanghai.android.files.apkutil.ApkRebuilder
import me.zhanghai.android.files.apkutil.BinaryXmlPatcher
import me.zhanghai.android.files.dex.DexParser
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions

/**
 * One-click signature-verification removal (the core of ApkSignatureKillerEx):
 *
 *  1. extract the original APK certificates,
 *  2. generate the KillerApplication smali with those certificates embedded,
 *  3. assemble it to dex with the on-device smali assembler (d8 is a desktop JVM tool),
 *  4. merge the killer class into classes.dex via dexlib2 (falls back to a new
 *     classesN.dex entry when the dex is too large),
 *  5. patch the binary AndroidManifest.xml so <application> points at the killer,
 *  6. rebuild the APK and re-sign with the app's auto-generated key.
 *
 * Only effective for apps that verify their signature through
 * PackageManager.getPackageInfo(pkg, GET_SIGNATURES).signatures, which is what the
 * killer hooks.
 */
object ApkSignatureKiller {

    private const val ACC_FINAL = 0x10
    private const val ACC_PUBLIC = 0x1
    private const val SMALI_API_LEVEL = 34
    // Interning a whole dex into DexPool is memory heavy; above this size the killer
    // class goes into its own new dex entry instead.
    private const val MERGE_DEX_SIZE_LIMIT = 96L * 1024 * 1024

    /**
     * Runs the whole pipeline for [sourcePath], writing the patched+re-signed APK to
     * [outputFile]. [sourcePath] may be on any file system the app can read; a local
     * cache copy is made first.
     */
    fun kill(context: Context, sourcePath: java8.nio.file.Path, outputFile: File) {
        val cacheDirectory = File(context.cacheDir, "apk-kill")
        val inputFile = ApkRebuilder.copyToCache(sourcePath, cacheDirectory, "input.apk")
        Log.d(TAG, "kill: input=${inputFile.absolutePath} size=${inputFile.length()}")
        try {
            Log.d(TAG, "step 1: extracting certificates")
            val certificates = ApkSignatureExtractor.extractCertificates(context, inputFile)
            Log.d(TAG, "step 1 done: ${certificates.size} certificates")
            val payload = ApkSignatureExtractor.buildPayload(certificates)

            Log.d(TAG, "step 2: reading manifest")
            val manifestBytes = ApkRebuilder.readEntry(inputFile, "AndroidManifest.xml")
                ?: throw ApkKillException("AndroidManifest.xml not found in APK")
            val info = BinaryXmlPatcher.readManifestInfo(manifestBytes)
            val superclass = resolveSuperclass(info.packageName, info.applicationName)
            Log.d(TAG, "step 2 done: package=${info.packageName} application=${info.applicationName} superclass=$superclass")
            // A final/package-private Application class is made extendable by patching its
            // access flags in the cached APK copy's classes.dex (before the merge reads it).
            checkExtendable(inputFile, superclass)

            Log.d(TAG, "step 3: assembling killer smali")
            val smali = KillerApplicationSmali.build(
                superclass,
                android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
            )
            val killerDex = assembleKiller(smali, cacheDirectory)
            Log.d(TAG, "step 3 done: killerDex=${killerDex.size} bytes")

            val classesDexBytes = ApkRebuilder.readEntry(inputFile, "classes.dex")
                ?: throw ApkKillException("classes.dex not found in APK")
            Log.d(TAG, "step 4: merging killer into classes.dex (${classesDexBytes.size} bytes)")
            val (dexName, dexBytes) = mergeOrInject(classesDexBytes, killerDex, inputFile)
            Log.d(TAG, "step 4 done: $dexName = ${dexBytes.size} bytes")

            Log.d(TAG, "step 5: patching manifest")
            val newManifest = BinaryXmlPatcher.setApplicationClassName(
                manifestBytes, KillerApplicationSmali.CLASS_NAME
            )
            Log.d(TAG, "step 5 done: manifest ${manifestBytes.size} -> ${newManifest.size} bytes")

            Log.d(TAG, "step 6: rebuilding apk")
            val rebuilt = File(cacheDirectory, "rebuilt.apk")
            ApkRebuilder.rebuild(
                inputFile, rebuilt,
                mapOf("AndroidManifest.xml" to newManifest, dexName to dexBytes)
            )
            Log.d(TAG, "step 6 done: rebuilt=${rebuilt.length()} bytes")

            Log.d(TAG, "step 7: signing with auto key")
            AutoSigner.sign(context, rebuilt, outputFile)
            Log.d(TAG, "step 7 done: output=${outputFile.length()} bytes")
        } finally {
            inputFile.delete()
        }
    }

    private const val TAG = "ApkSignatureKiller"

    /** Resolves the manifest's Application name to a dex type descriptor. */
    private fun resolveSuperclass(packageName: String?, applicationName: String?): String {
        if (applicationName.isNullOrEmpty()) {
            return "Landroid/app/Application;"
        }
        var name = applicationName
        if (name.startsWith(".") && packageName != null) {
            name = packageName + name
        }
        return "L" + name.replace('.', '/') + ";"
    }

    /**
     * The killer class extends the app's real Application class. When that class is final
     * or package-private, its access flags are patched in classes.dex (clear ACC_FINAL,
     * add ACC_PUBLIC) so the subclass verifies at load time. A final attachBaseContext
     * cannot be overridden, so that case still bails out with a clear message.
     */
    private fun checkExtendable(inputFile: File, superclassDescriptor: String) {
        if (superclassDescriptor == "Landroid/app/Application;") {
            return
        }
        val classesDex = ApkRebuilder.readEntry(inputFile, "classes.dex") ?: return
        val dexFile = try {
            DexParser.parse(classesDex)
        } catch (e: Throwable) {
            return
        }
        val dexClass = dexFile.classes.find { it.className == superclassDescriptor } ?: return
        val attachBaseContext = dexClass.methods.find {
            it.method.name == "attachBaseContext" &&
                it.method.shortDescriptor == "(Landroid/content/Context;)V"
        }
        if (attachBaseContext != null && attachBaseContext.accessFlags and ACC_FINAL != 0) {
            throw ApkKillException("The app's attachBaseContext() is final and cannot be overridden")
        }
        if (dexClass.accessFlags and ACC_FINAL != 0) {
            // Patch classes.dex in place: the killer dex merge happens on the patched copy.
            patchClassFlags(inputFile, superclassDescriptor, ACC_FINAL, 0)
        }
        if (dexClass.accessFlags and ACC_PUBLIC == 0) {
            patchClassFlags(inputFile, superclassDescriptor, 0, ACC_PUBLIC)
        }
    }

    /**
     * Reads classes.dex from [inputFile], patches [superclassDescriptor]'s access flags
     * and writes the patched dex back into the APK cache copy, so the subsequent merge
     * sees the patched bytes. Returns true when a patch was applied.
     */
    private fun patchClassFlags(
        inputFile: File, superclassDescriptor: String, clearFlags: Int, setFlags: Int
    ): Boolean {
        val classesDex = ApkRebuilder.readEntry(inputFile, "classes.dex") ?: return false
        val patched = DexAccessFlagPatcher.patchClassFlags(
            classesDex, superclassDescriptor, clearFlags, setFlags
        ) ?: return false
        if (patched === classesDex) {
            return false
        }
        // Replace the entry in the cached APK copy so readEntry() picks it up later.
        val rebuilt = File(inputFile.parentFile, "classes-patched.apk")
        ApkRebuilder.rebuild(inputFile, rebuilt, mapOf("classes.dex" to patched))
        rebuilt.copyTo(inputFile, overwrite = true)
        rebuilt.delete()
        return true
    }

    /**
     * Assembles the killer smali text into a standalone dex (bytes). smali 2.5.2 writes
     * its output to SmaliOptions.outputDexFile and diagnostics to System.err, which is
     * captured temporarily (the call happens on a background thread).
     */
    private fun assembleKiller(smali: String, cacheDirectory: File): ByteArray {
        val smaliFile = File(cacheDirectory, "KillerApplication.smali")
        smaliFile.writeText(smali)
        val dexFile = File(cacheDirectory, "KillerApplication.dex")
        val options = SmaliOptions().apply {
            apiLevel = SMALI_API_LEVEL
            verboseErrors = true
            outputDexFile = dexFile.absolutePath
        }
        val originalErr = System.err
        val errBuffer = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuffer, true, Charsets.UTF_8.name()))
        try {
            val success = try {
                Smali.assemble(options, smaliFile.absolutePath)
            } catch (e: Exception) {
                throw ApkKillException("Smali assembly failed: ${e.message}", e)
            }
            if (!success) {
                val error = errBuffer.toString(Charsets.UTF_8.name()).trim()
                throw ApkKillException(
                    "Smali assembly failed" + (if (error.isEmpty()) "" else ":\n$error")
                )
            }
            return dexFile.readBytes()
        } finally {
            System.setErr(originalErr)
        }
    }

    /**
     * Merges the killer class into classes.dex through dexlib2's DexPool (a true dex
     * merge, so the APK stays single-dex). Falls back to injecting the killer as the
     * next classesN.dex entry when the merge is too heavy or fails.
     */
    private fun mergeOrInject(
        classesDexBytes: ByteArray, killerDexBytes: ByteArray, inputFile: File
    ): Pair<String, ByteArray> {
        if (classesDexBytes.size <= MERGE_DEX_SIZE_LIMIT) {
            try {
                return "classes.dex" to mergeIntoDex(classesDexBytes, killerDexBytes)
            } catch (e: Throwable) {
                // Fall through to injection.
            }
        }
        return nextDexName(inputFile) to killerDexBytes
    }

    private fun mergeIntoDex(classesDexBytes: ByteArray, killerDexBytes: ByteArray): ByteArray {
        val killerDexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), ByteArrayInputStream(killerDexBytes)
        )
        val sourceDexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), ByteArrayInputStream(classesDexBytes)
        )
        val dexPool = DexPool(Opcodes.getDefault())
        killerDexFile.classes.forEach { dexPool.internClass(it) }
        sourceDexFile.classes.forEach { dexPool.internClass(it) }
        val store = MemoryDataStore()
        dexPool.writeTo(store)
        return store.data.copyOf(store.size)
    }

    private fun nextDexName(inputFile: File): String {
        var maxIndex = 1 // "classes.dex"
        java.util.zip.ZipFile(inputFile).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    val number = name.removePrefix("classes").removeSuffix(".dex")
                    number.toIntOrNull()?.let { if (it > maxIndex) maxIndex = it }
                }
            }
        }
        return "classes${maxIndex + 1}.dex"
    }
}
