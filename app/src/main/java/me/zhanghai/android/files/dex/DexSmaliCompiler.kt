/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions

/**
 * The round-trip behind the dex++ smali editor. To keep memory use flat (a full
 * baksmali->smali round trip of a large dex can exceed the on-device heap and OOM),
 * only the class being edited is touched:
 *
 *  1. baksmali disassembles JUST that class to smali (the 5-arg overload takes the
 *     class list),
 *  2. the edited method block is swapped back into the class smali,
 *  3. smali assembles JUST that class into a tiny dex,
 *  4. dexlib2's DexPool merges the tiny dex into a copy of the original dex
 *     (the edited class replaces the original with the same type name).
 *
 * dexlib2/smali/baksmali are pure-Java libraries, which is why they can run on-device
 * where the desktop d8 tool cannot.
 */
object DexSmaliCompiler {

    class CompileException(message: String, cause: Throwable? = null) : Exception(message, cause)

    const val API_LEVEL = 34

    /**
     * Disassembles only [className] (a type descriptor like "Lcom/foo/Bar;") from
     * [dexBytes] into [outputDirectory], as "com/foo/Bar.smali".
     */
    fun disassembleClass(dexBytes: ByteArray, outputDirectory: File, className: String) {
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), ByteArrayInputStream(dexBytes)
        )
        val options = BaksmaliOptions().apply {
            apiLevel = API_LEVEL
        }
        val success = Baksmali.disassembleDexFile(
            dexFile, outputDirectory, 1, options, listOf(className)
        )
        if (!success) {
            throw CompileException("baksmali failed to disassemble the class")
        }
    }

    /**
     * Finds the [.method .. .end method] block whose signature equals [methodKey]
     * (e.g. "onCreate(Landroid/os/Bundle;)V" or "<init>()V") inside [classSmali].
     *
     * @return the start line (inclusive) and end line (exclusive) of the block, or null.
     */
    fun findMethodBlock(classSmali: String, methodKey: String): Pair<Int, Int>? {
        val lines = classSmali.split("\n")
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            if (trimmed.startsWith(".method ")) {
                val signature = trimmed.substringAfter(".method ").trim()
                val token = signature.substringAfterLast(' ')
                if (token == methodKey) {
                    // Walk to the matching .end method (annotations can nest inside).
                    var depth = 1
                    var end = index + 1
                    while (end < lines.size) {
                        val line = lines[end].trimStart()
                        if (line.startsWith(".method ")) {
                            depth++
                        } else if (line.startsWith(".end method")) {
                            depth--
                            if (depth == 0) {
                                return index to (end + 1)
                            }
                        }
                        end++
                    }
                    return null
                }
            }
            index++
        }
        return null
    }

    /**
     * Replaces the method block identified by [methodKey] with [newBlock] (which may omit
     * the trailing ".end method"; it is appended automatically).
     *
     * @return the new class smali text, or null when the method was not found.
     */
    fun replaceMethodBlock(classSmali: String, methodKey: String, newBlock: String): String? {
        val (start, end) = findMethodBlock(classSmali, methodKey) ?: return null
        val block = newBlock.trimEnd() +
            if (newBlock.trimEnd().endsWith(".end method")) "" else "\n.end method"
        val lines = classSmali.split("\n")
        return buildString {
            if (start > 0) {
                append(lines.subList(0, start).joinToString("\n"))
                append('\n')
            }
            append(block)
            if (end < lines.size) {
                append('\n')
                append(lines.subList(end, lines.size).joinToString("\n"))
            }
        }
    }

    /** Assembles a single .smali file into a dex and returns its bytes. */
    fun assembleClass(classSmaliFile: File): ByteArray {
        val outputFile = File(classSmaliFile.parentFile, "assembled-class.dex")
        val options = SmaliOptions().apply {
            apiLevel = API_LEVEL
            verboseErrors = true
            outputDexFile = outputFile.absolutePath
        }
        val originalErr = System.err
        val errBuffer = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuffer, true, Charsets.UTF_8.name()))
        try {
            val success = try {
                Smali.assemble(options, classSmaliFile.absolutePath)
            } catch (e: Exception) {
                throw CompileException("Smali assembly failed: ${e.message}", e)
            }
            if (!success) {
                val error = errBuffer.toString(Charsets.UTF_8.name()).trim()
                throw CompileException(
                    "Smali assembly failed" + (if (error.isEmpty()) "" else ":\n$error")
                )
            }
            return outputFile.readBytes()
        } finally {
            System.setErr(originalErr)
            outputFile.delete()
        }
    }

    /**
     * Merges [newClassDex] (containing the edited class) into [originalDexBytes],
     * replacing any class with the same type name. Returns the merged dex bytes.
     */
    fun mergeDex(originalDexBytes: ByteArray, newClassDex: ByteArray): ByteArray {
        val originalFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), ByteArrayInputStream(originalDexBytes)
        )
        val newClassFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(), ByteArrayInputStream(newClassDex)
        )
        val newTypes = HashSet<String>()
        newClassFile.classes.forEach { newTypes.add(it.type) }
        val dexPool = DexPool(Opcodes.getDefault())
        originalFile.classes.forEach { dexClass ->
            if (newTypes.contains(dexClass.type)) {
                return@forEach // replaced by the edited class below
            }
            dexPool.internClass(dexClass)
        }
        newClassFile.classes.forEach { dexPool.internClass(it) }
        val store = MemoryDataStore()
        dexPool.writeTo(store)
        return store.data.copyOf(store.size)
    }
}
