/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.arsc

import java.nio.charset.StandardCharsets

/**
 * Parser for the binary resources.arsc format (see
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h).
 *
 * aapt2 optimizations are handled by converting to the canonical model:
 *  - FLAG_COMPACT entries (dataType in the high byte of flags + inline data) are expanded
 *    into a full Res_value,
 *  - FLAG_SPARSE types are expanded into the dense entry-offset layout.
 * The writer therefore always emits dense, non-compact output that every platform reads.
 */
object ArscParser {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_RESOURCE_MAP_TYPE = 0x0180

    private const val UTF8_FLAG = 0x100
    private const val NO_ENTRY = 0xffffffffL

    private const val FLAG_COMPLEX = 0x0001
    private const val FLAG_COMPACT = 0x0008

    fun parse(bytes: ByteArray): ArscFile {
        if (bytes.size < 12) {
            throw ArscParseException("File too small to be resources.arsc")
        }
        if (u16At(bytes, 0) != RES_TABLE_TYPE) {
            throw ArscParseException("Not a resource table (bad header type)")
        }
        val packageCount = u32At(bytes, 12).toInt()
        var globalPool = ArscStringPool(ArrayList())
        var resourceMap = IntArray(0)
        val packages = ArrayList<ArscPackage>(packageCount)

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val type = u16At(bytes, pos)
            val chunkSize = u32At(bytes, pos + 4).toInt()
            if (chunkSize < 8 || pos + chunkSize > bytes.size) {
                break
            }
            when (type) {
                RES_STRING_POOL_TYPE -> globalPool = parseStringPool(bytes, pos)
                RES_TABLE_RESOURCE_MAP_TYPE -> resourceMap = parseResourceMap(bytes, pos)
                RES_TABLE_PACKAGE_TYPE -> packages.add(parsePackage(bytes, pos, chunkSize))
            }
            pos += chunkSize
        }
        return ArscFile(globalPool, resourceMap, packages)
    }

    private fun parsePackage(bytes: ByteArray, start: Int, chunkSize: Int): ArscPackage {
        // header(8) + id(4) + name char16[128](256) + typeStrings(4) + lastPublicType(4)
        // + keyStrings(4) + lastPublicKey(4) + typeIdOffset(4) = 288.
        val id = u32At(bytes, start + 8).toInt()
        val name = decodeUtf16Fixed(bytes, start + 12, 128)
        val typeStringsOffset = u32At(bytes, start + 268).toInt()
        val keyStringsOffset = u32At(bytes, start + 276).toInt()
        val headerSize = u16At(bytes, start + 2)
        val typeStrings = if (typeStringsOffset > 0) {
            parseStringPool(bytes, start + typeStringsOffset)
        } else {
            ArscStringPool(ArrayList())
        }
        val keyStrings = if (keyStringsOffset > 0) {
            parseStringPool(bytes, start + keyStringsOffset)
        } else {
            ArscStringPool(ArrayList())
        }
        val typeSpecs = ArrayList<ArscTypeSpec>()
        val types = ArrayList<ArscType>()
        val contentStart = if (headerSize > 0) headerSize else 288
        var pos = start + contentStart
        val end = start + chunkSize
        while (pos + 8 <= end) {
            val type = u16At(bytes, pos)
            val size = u32At(bytes, pos + 4).toInt()
            if (size < 8 || pos + size > end) {
                break
            }
            when (type) {
                RES_TABLE_TYPE_SPEC_TYPE -> typeSpecs.add(parseTypeSpec(bytes, pos, size))
                RES_TABLE_TYPE_TYPE -> types.add(parseType(bytes, pos, size))
            }
            pos += size
        }
        return ArscPackage(id, name, typeStrings, keyStrings, typeSpecs, types)
    }

    private fun parseTypeSpec(bytes: ByteArray, start: Int, chunkSize: Int): ArscTypeSpec {
        val id = bytes[start + 8].toInt() and 0xff
        val entryCount = u32At(bytes, start + 12).toInt()
        val flags = IntArray(entryCount)
        var pos = start + 16
        repeat(entryCount) { index ->
            if (pos + 4 <= start + chunkSize) {
                flags[index] = u32At(bytes, pos).toInt()
                pos += 4
            }
        }
        return ArscTypeSpec(id, flags)
    }

    private fun parseType(bytes: ByteArray, start: Int, chunkSize: Int): ArscType {
        val id = bytes[start + 8].toInt() and 0xff
        val flags = bytes[start + 9].toInt() and 0xff
        val entryCount = u32At(bytes, start + 12).toInt()
        val entriesStart = u32At(bytes, start + 16).toInt()
        val configSize = u32At(bytes, start + 20).toInt()
        // The config is a 28/52/56-byte blob; clamp it to the chunk so a malformed size
        // cannot read past the end.
        val configLength = when {
            configSize < 28 -> 28
            start + 20 + configSize > start + chunkSize -> start + chunkSize - (start + 20)
            else -> configSize
        }
        val config = bytes.copyOfRange(start + 20, start + 20 + configLength)
        val offsetsStart = start + 20 + configLength

        // Convert sparse into the dense layout: NO_ENTRY everywhere, real offsets where set.
        // NOTE: entry offsets are relative to entriesStart, not to the chunk start.
        val entryOffsets = IntArray(entryCount) { -1 }
        if (flags and ArscType.FLAG_SPARSE != 0) {
            val sparseCount = (chunkSize - (offsetsStart - start)) / 4
            repeat(sparseCount) { index ->
                val raw = u32At(bytes, offsetsStart + index * 4).toInt()
                val entryIndex = raw and 0xffff
                val offsetUnits = (raw ushr 16) and 0xffff
                if (entryIndex < entryCount) {
                    entryOffsets[entryIndex] = entriesStart + offsetUnits * 4
                }
            }
        } else {
            repeat(entryCount) { index ->
                val offset = u32At(bytes, offsetsStart + index * 4)
                if (offset.toInt() != NO_ENTRY.toInt()) {
                    entryOffsets[index] = entriesStart + offset.toInt()
                }
            }
        }

        val entries = ArrayList<ArscEntry?>(entryCount)
        repeat(entryCount) { index ->
            val offset = entryOffsets[index]
            entries.add(
                if (offset < 0) null
                else parseEntry(bytes, start + offset, start + chunkSize)
            )
        }
        return ArscType(id, flags, config, entries)
    }

    private fun parseEntry(bytes: ByteArray, offset: Int, end: Int): ArscEntry {
        if (offset + 8 > end) {
            throw ArscParseException("Entry out of range")
        }
        // Layout: [size u16][flags u16]... — the COMPACT flag lives in flags, not size.
        val flags = u16At(bytes, offset + 2)
        // Compact entry: [key u16][flags u16 (dataType in high byte)][data u32] — 8 bytes
        // total, no separate Res_value follows.
        if (flags and FLAG_COMPACT != 0) {
            val keyIndex = u16At(bytes, offset)
            val dataType = (flags ushr 8) and 0xff
            val data = u32At(bytes, offset + 4)
            return ArscEntry(
                keyIndex,
                flags and 0xfff7, // strip COMPACT (and the dataType bits) from flags
                ArscValue(dataType, data),
                null,
                null
            )
        }
        val keyIndex = u32At(bytes, offset + 4).toInt()
        if (flags and FLAG_COMPLEX != 0) {
            if (offset + 16 > end) {
                throw ArscParseException("Complex entry out of range")
            }
            val parent = u32At(bytes, offset + 8)
            val count = u32At(bytes, offset + 12).toInt()
            val items = ArrayList<ArscMapItem>(count)
            var pos = offset + 16
            repeat(count) {
                if (pos + 12 > end) {
                    throw ArscParseException("Map item out of range")
                }
                val name = u32At(bytes, pos)
                val value = parseValue(bytes, pos + 4)
                items.add(ArscMapItem(name, value))
                pos += 12
            }
            return ArscEntry(keyIndex, flags, null, parent, items)
        }
        return ArscEntry(keyIndex, flags, parseValue(bytes, offset + 8), null, null)
    }

    private fun parseValue(bytes: ByteArray, offset: Int): ArscValue {
        val dataType = bytes[offset + 3].toInt() and 0xff
        val data = u32At(bytes, offset + 4)
        return ArscValue(dataType, data)
    }

    private fun parseStringPool(bytes: ByteArray, start: Int): ArscStringPool {
        val headerSize = u16At(bytes, start + 2)
        if (headerSize < 28) {
            return ArscStringPool(ArrayList())
        }
        val stringCount = u32At(bytes, start + 8).toInt()
        val flags = u32At(bytes, start + 16)
        val stringsStart = u32At(bytes, start + 20).toInt()
        val isUtf8 = flags and UTF8_FLAG.toLong() != 0L
        if (stringCount < 0 || stringCount > (bytes.size - start - 28) / 4) {
            throw ArscParseException("Invalid string pool count")
        }
        val strings = ArrayList<String>(stringCount)
        for (index in 0 until stringCount) {
            val stringOffset = u32At(bytes, start + 28 + index * 4).toInt()
            val absoluteOffset = start + stringsStart + stringOffset
            strings.add(
                if (isUtf8) readUtf8String(bytes, absoluteOffset)
                else readUtf16String(bytes, absoluteOffset)
            )
        }
        return ArscStringPool(strings)
    }

    private fun parseResourceMap(bytes: ByteArray, start: Int): IntArray {
        val chunkSize = u32At(bytes, start + 4).toInt()
        val count = (chunkSize - 8) / 4
        val ids = IntArray(count)
        repeat(count) { index ->
            ids[index] = u32At(bytes, start + 8 + index * 4).toInt()
        }
        return ids
    }

    private fun readUtf8String(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) {
            return ""
        }
        var position = offset
        var length = bytes[position].toInt() and 0xff
        position++
        // AOSP encodes a 2-byte length as ((first & 0x7F) << 8) | second.
        if (length and 0x80 != 0) {
            length = ((length and 0x7f) shl 8) or (bytes[position].toInt() and 0xff)
            position++
        }
        var byteLength = bytes[position].toInt() and 0xff
        position++
        if (byteLength and 0x80 != 0) {
            byteLength = ((byteLength and 0x7f) shl 8) or (bytes[position].toInt() and 0xff)
            position++
        }
        val end = minOf(position + byteLength, bytes.size)
        return String(bytes, position, end - position, StandardCharsets.UTF_8)
    }

    private fun readUtf16String(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 1 >= bytes.size) {
            return ""
        }
        var position = offset
        val length = u16At(bytes, position)
        position += 2
        val charArray = CharArray(length)
        var index = 0
        while (index < length && position + 1 < bytes.size) {
            charArray[index] = u16At(bytes, position).toChar()
            position += 2
            index++
        }
        return String(charArray, 0, index)
    }

    private fun decodeUtf16Fixed(bytes: ByteArray, offset: Int, charCount: Int): String {
        val builder = StringBuilder(charCount)
        var pos = offset
        repeat(charCount) {
            if (pos + 1 < bytes.size) {
                val char = u16At(bytes, pos)
                if (char == 0) {
                    return builder.toString()
                }
                builder.append(char.toChar())
                pos += 2
            }
        }
        return builder.toString()
    }

    private fun u16At(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32At(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            (bytes[offset + 1].toLong() and 0xff shl 8) or
            (bytes[offset + 2].toLong() and 0xff shl 16) or
            (bytes[offset + 3].toLong() and 0xff shl 24)
}
