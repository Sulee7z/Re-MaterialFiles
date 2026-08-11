/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkkiller

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Byte-level patch for a DEX class_def access_flags, used to make an app's Application
 * class extendable by the killer (clear ACC_FINAL, add ACC_PUBLIC). Only the 4 flag
 * bytes change, so the file is rewritten in place and the header checksum (adler32) and
 * signature (sha1) are recomputed — exactly what the dex format requires.
 */
object DexAccessFlagPatcher {

    const val ACC_PUBLIC = 0x0001
    const val ACC_FINAL = 0x0010

    /**
     * Clears [clearFlags] and sets [setFlags] on the class_def of [className]
     * (a type descriptor like "Lcom/example/App;").
     *
     * @return the patched dex bytes, or null when the class was not found.
     */
    fun patchClassFlags(
        dex: ByteArray, className: String, clearFlags: Int, setFlags: Int
    ): ByteArray? {
        val typeIndex = findTypeIndex(dex, className) ?: return null
        val classDefsSize = u32At(dex, 96).toInt()
        val classDefsOffset = u32At(dex, 100).toInt()
        for (index in 0 until classDefsSize) {
            val offset = classDefsOffset + index * 32
            if (offset + 32 > dex.size) {
                break
            }
            if (u32At(dex, offset).toInt() != typeIndex) {
                continue
            }
            val flagsOffset = offset + 4
            val oldFlags = u32At(dex, flagsOffset).toInt()
            val newFlags = (oldFlags and clearFlags.inv()) or setFlags
            if (newFlags == oldFlags) {
                return dex
            }
            writeU32(dex, flagsOffset, newFlags.toLong())
            recomputeChecksum(dex)
            return dex
        }
        return null
    }

    /** Finds the type_ids index for [className], or null. */
    private fun findTypeIndex(dex: ByteArray, className: String): Int? {
        val stringIdsSize = u32At(dex, 56).toInt()
        val stringIdsOffset = u32At(dex, 60).toInt()
        val typeIdsSize = u32At(dex, 64).toInt()
        val typeIdsOffset = u32At(dex, 68).toInt()
        for (typeIndex in 0 until typeIdsSize) {
            val stringIndex = u32At(dex, typeIdsOffset + typeIndex * 4).toInt()
            if (stringIndex < 0 || stringIndex >= stringIdsSize) {
                continue
            }
            val stringOffset = u32At(dex, stringIdsOffset + stringIndex * 4).toInt()
            if (stringOffset < 0 || stringOffset >= dex.size) {
                continue
            }
            if (readMutf8(dex, stringOffset) == className) {
                return typeIndex
            }
        }
        return null
    }

    private fun readMutf8(dex: ByteArray, offset: Int): String {
        var position = offset
        // Skip the ULEB128 length.
        while (position < dex.size && dex[position].toInt() and 0x80 != 0) {
            position++
        }
        position++
        val builder = StringBuilder()
        while (position < dex.size && dex[position].toInt() != 0) {
            val first = dex[position++].toInt() and 0xff
            if (first < 0x80) {
                builder.append(first.toChar())
            } else if (first < 0xe0) {
                if (position >= dex.size) break
                val second = dex[position++].toInt() and 0xff
                builder.append(((first and 0x1f) shl 6 or (second and 0x3f)).toChar())
            } else {
                if (position + 1 >= dex.size) break
                val second = dex[position++].toInt() and 0xff
                val third = dex[position++].toInt() and 0xff
                builder.append(
                    ((first and 0x0f) shl 12 or ((second and 0x3f) shl 6) or (third and 0x3f))
                        .toChar()
                )
            }
        }
        return builder.toString()
    }

    /** Recomputes the dex header signature (sha1 over bytes 32..) and checksum (adler32 over 12..). */
    private fun recomputeChecksum(dex: ByteArray) {
        // NOTE: do not use MessageDigest.digest(byte[], int, int) — desugaring maps it to
        // the Java 9 overload that WRITES into the input and returns an Int length.
        val messageDigest = MessageDigest.getInstance("SHA-1")
        messageDigest.update(dex, 32, dex.size - 32)
        val sha1 = messageDigest.digest()
        System.arraycopy(sha1, 0, dex, 12, 20)
        val adler32 = Adler32()
        adler32.update(dex, 12, dex.size - 12)
        writeU32(dex, 8, adler32.value)
    }

    private fun u32At(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            (bytes[offset + 1].toLong() and 0xff shl 8) or
            (bytes[offset + 2].toLong() and 0xff shl 16) or
            (bytes[offset + 3].toLong() and 0xff shl 24)

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
}
