/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkmanifest

import java.nio.charset.StandardCharsets

class ManifestDecodeException(message: String) : Exception(message)

/**
 * Decodes a binary AndroidManifest.xml (AXML) into readable XML text. See
 * https://github.com/android/platform_frameworks_base/blob/master/libs/androidfw/include/androidfw/ResourceTypes.h
 */
object AndroidManifestDecoder {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    private const val UTF8_FLAG = 0x100
    private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"

    private const val TYPE_REFERENCE = 1
    private const val TYPE_STRING = 3
    private const val TYPE_INT_DEC = 16
    private const val TYPE_INT_HEX = 17
    private const val TYPE_INT_BOOLEAN = 18

    class Reader(val bytes: ByteArray) {
        var position = 0

        fun u8(): Int = bytes[position++].toInt() and 0xff

        fun u16(): Int {
            val value = u8() or (u8() shl 8)
            return value
        }

        fun u32(): Long {
            val value = (u8().toLong()) or (u8().toLong() shl 8) or
                (u8().toLong() shl 16) or (u8().toLong() shl 24)
            return value
        }

        fun u32At(offset: Int): Long =
            (bytes[offset].toLong() and 0xff) or
                (bytes[offset + 1].toLong() and 0xff shl 8) or
                (bytes[offset + 2].toLong() and 0xff shl 16) or
                (bytes[offset + 3].toLong() and 0xff shl 24)

        fun u16At(offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size < 16) {
            throw ManifestDecodeException("File too small")
        }
        val reader = Reader(bytes)
        // XML chunk header (type 0x0003).
        val xmlType = reader.u16()
        val xmlHeaderSize = reader.u16()
        val xmlChunkSize = reader.u32().toInt()
        if (xmlType != 0x0003 || xmlHeaderSize != 8 || xmlChunkSize > bytes.size) {
            throw ManifestDecodeException("Invalid XML header")
        }
        // String pool chunk.
        val poolStart = reader.position
        val poolType = reader.u16()
        val poolHeaderSize = reader.u16()
        val poolChunkSize = reader.u32().toInt()
        if (poolType != RES_STRING_POOL_TYPE) {
            throw ManifestDecodeException("No string pool found")
        }
        if (poolChunkSize > bytes.size) {
            throw ManifestDecodeException("Invalid string pool size")
        }
        val stringPool = StringPool(bytes, poolStart, poolHeaderSize)
        reader.position = poolStart + poolChunkSize
        val namespaces = ArrayDeque<Pair<Long, Long>>()
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var indent = 0
        var lastStartElementName = -1L
        var lastStartElementNs = -1L
        while (reader.position + 8 <= bytes.size) {
            val start = reader.position
            val type = reader.u16()
            val headerSize = reader.u16()
            val size = reader.u32().toInt()
            if (size < headerSize || start + size > bytes.size) {
                break
            }
            when (type) {
                RES_XML_START_NAMESPACE_TYPE -> {
                    val (prefix, uri) = readNamespace(reader, start, stringPool)
                    namespaces.addLast(prefix to uri)
                }
                RES_XML_END_NAMESPACE_TYPE -> {
                    if (namespaces.isNotEmpty()) {
                        namespaces.removeLast()
                    }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    reader.position = start + 8
                    val lineNumber = reader.u32()
                    val comment = reader.u32()
                    val ns = reader.u32()
                    val name = reader.u32()
                    val attributeStart = reader.u16()
                    val attributeSize = reader.u16()
                    val attributeCount = reader.u16()
                    // The attributeStart field is the byte offset of the attributes from the
                    // extended header, which follows the 8-byte chunk header and the 8-byte
                    // lineNumber/comment fields.
                    val attributesStart = start + 16 + attributeStart
                    appendIndent(builder, indent)
                    builder.append('<')
                    if (ns.toInt() != -1) {
                        val prefix = namespacePrefix(stringPool, namespaces, ns)
                        if (prefix != null) {
                            builder.append(prefix).append(':')
                        }
                    }
                    builder.append(escape(stringPool.string(name.toInt())))
                    repeat(attributeCount) { index ->
                        val attributeOffset = attributesStart + index * 20
                        val attrNs = u32At(reader, attributeOffset)
                        val attrName = u32At(reader, attributeOffset + 4)
                        val rawValue = u32At(reader, attributeOffset + 8)
                        val dataType = reader.bytes[attributeOffset + 15].toInt() and 0xff
                        val data = u32At(reader, attributeOffset + 16)
                        builder.append(' ')
                        if (attrNs.toInt() != -1) {
                            val prefix = namespacePrefix(stringPool, namespaces, attrNs)
                            if (prefix != null) {
                                builder.append(prefix).append(':')
                            }
                        }
                        builder.append(escape(stringPool.string(attrName.toInt())))
                        builder.append("=\"")
                        builder.append(
                            escape(
                                formatValue(
                                    stringPool, rawValue, dataType, data
                                )
                            )
                        )
                        builder.append('"')
                    }
                    lastStartElementName = name
                    lastStartElementNs = ns
                    indent++
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    indent--
                    reader.position = start + 8
                    val ns = reader.u32()
                    val name = reader.u32()
                    if (lastStartElementName == name && lastStartElementNs == ns) {
                        builder.append("/>\n")
                    } else {
                        appendIndent(builder, indent)
                        builder.append("</")
                        if (ns.toInt() != -1) {
                            val prefix = namespacePrefix(stringPool, namespaces, ns)
                            if (prefix != null) {
                                builder.append(prefix).append(':')
                            }
                        }
                        builder.append(escape(stringPool.string(name.toInt()))).append(">\n")
                    }
                    lastStartElementName = -1
                }
                RES_XML_CDATA_TYPE -> {
                    reader.position = start + 8
                    val data = reader.u32()
                    appendIndent(builder, indent)
                    builder.append(escape(stringPool.string(data.toInt()))).append('\n')
                }
                else -> {
                    // Skip unknown chunks.
                }
            }
            reader.position = start + size
        }
        return builder.toString()
    }

    private fun u32At(reader: Reader, offset: Int): Long = reader.u32At(offset)

    private fun readNamespace(
        reader: Reader, start: Int, stringPool: StringPool
    ): Pair<Long, Long> {
        // The namespace fields are at +8/+12 in the classic layout, but aapt2 places them at
        // +16/+20, so prefer the pair where both indices are valid string indices.
        val classic = reader.u32At(start + 8) to reader.u32At(start + 12)
        val aapt2 = reader.u32At(start + 16) to reader.u32At(start + 20)
        return if (stringPool.isValidIndex(classic.first) && stringPool.isValidIndex(classic.second)) {
            classic
        } else {
            aapt2
        }
    }

    private fun namespacePrefix(
        stringPool: StringPool, namespaces: ArrayDeque<Pair<Long, Long>>, uriIndex: Long
    ): String? {
        for ((prefixIndex, uri) in namespaces) {
            if (uri == uriIndex) {
                return stringPool.string(prefixIndex.toInt())
            }
        }
        return null
    }

    private fun appendIndent(builder: StringBuilder, indent: Int) {
        repeat(indent) { builder.append("    ") }
    }

    private fun formatValue(
        stringPool: StringPool, rawValue: Long, dataType: Int, data: Long
    ): String {
        if (rawValue.toInt() != -1) {
            return stringPool.string(rawValue.toInt())
        }
        return when (dataType) {
            TYPE_STRING -> stringPool.string(data.toInt())
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_BOOLEAN -> if (data != 0L) "true" else "false"
            TYPE_REFERENCE -> "@0x" + data.toString(16)
            TYPE_INT_HEX -> "0x" + data.toString(16)
            else -> "0x" + data.toString(16)
        }
    }

    private fun escape(string: String): String {
        val builder = StringBuilder(string.length)
        string.forEach { char ->
            when (char) {
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '&' -> builder.append("&amp;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                '\n' -> builder.append("\\n")
                '\t' -> builder.append("\\t")
                '\r' -> builder.append("\\r")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private class StringPool(private val bytes: ByteArray, poolStart: Int, headerSize: Int) {
        private val strings: List<String>

        init {
            val stringCount = u32At(bytes, poolStart + 8).toInt()
            val flags = u32At(bytes, poolStart + 16)
            // stringsStart is relative to the start of the string pool chunk.
            val stringsStart = u32At(bytes, poolStart + 20).toInt()
            val isUtf8 = flags and UTF8_FLAG.toLong() != 0L
            val list = ArrayList<String>(stringCount)
            for (index in 0 until stringCount) {
                val stringOffset = u32At(bytes, poolStart + 28 + index * 4).toInt()
                val absoluteOffset = poolStart + stringsStart + stringOffset
                list.add(
                    if (isUtf8) readUtf8String(bytes, absoluteOffset)
                    else readUtf16String(bytes, absoluteOffset)
                )
            }
            strings = list
        }

        fun string(index: Int): String =
            if (index in strings.indices) strings[index] else ""

        fun isValidIndex(index: Long): Boolean =
            index in strings.indices

        private fun readUtf8String(bytes: ByteArray, offset: Int): String {
            var position = offset
            // UTF-16 length (in characters).
            var length = bytes[position].toInt() and 0xff
            position++
            if (length and 0x80 != 0) {
                length = (length and 0x7f) or ((bytes[position].toInt() and 0xff) shl 8)
                position++
            }
            // UTF-8 length (in bytes).
            var byteLength = bytes[position].toInt() and 0xff
            position++
            if (byteLength and 0x80 != 0) {
                byteLength = (byteLength and 0x7f) or ((bytes[position].toInt() and 0xff) shl 8)
                position++
            }
            val end = minOf(position + byteLength, bytes.size)
            return String(bytes, position, end - position, StandardCharsets.UTF_8)
        }

        private fun readUtf16String(bytes: ByteArray, offset: Int): String {
            var position = offset
            val length = (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8)
            position += 2
            val charArray = CharArray(length)
            var index = 0
            while (index < length && position + 1 < bytes.size) {
                charArray[index] = ((bytes[position].toInt() and 0xff) or
                    ((bytes[position + 1].toInt() and 0xff) shl 8)).toChar()
                position += 2
                index++
            }
            return String(charArray, 0, index)
        }

        private companion object {
            fun u32At(bytes: ByteArray, offset: Int): Long =
                (bytes[offset].toLong() and 0xff) or
                    (bytes[offset + 1].toLong() and 0xff shl 8) or
                    (bytes[offset + 2].toLong() and 0xff shl 16) or
                    (bytes[offset + 3].toLong() and 0xff shl 24)
        }
    }
}
