/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkutil

/**
 * Surgical binary editor for an AXML (binary AndroidManifest.xml) file. Unlike a full
 * re-encoder, it keeps every untouched chunk byte-identical and only:
 *  1. rebuilds the string pool chunk (same strings, plus the ones we need),
 *  2. patches/inserts the <application> android:name attribute.
 *
 * Because every offset inside AXML is relative to its own chunk, growing the pool only
 * requires bumping the top-level XML chunk size (file size at offset 4).
 */
object BinaryXmlPatcher {

    class ManifestParseException(message: String) : Exception(message)

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    private const val UTF8_FLAG = 0x100

    private const val TYPE_STRING = 3

    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    data class ManifestInfo(val packageName: String?, val applicationName: String?)

    /** Parses <manifest package="..."> and <application android:name="...">. */
    fun readManifestInfo(bytes: ByteArray): ManifestInfo {
        val pool = StringPoolReader(bytes, 8)
        var packageName: String? = null
        var applicationName: String? = null
        var pos = 8 + pool.chunkSize
        while (pos + 8 <= bytes.size) {
            val type = u16At(bytes, pos)
            val chunkSize = u32At(bytes, pos + 4).toInt()
            if (chunkSize < 8 || pos + chunkSize > bytes.size) {
                break
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val ns = u32At(bytes, pos + 16)
                val name = u32At(bytes, pos + 20)
                val elementName = pool.string(name.toInt())
                val attributeStart = u16At(bytes, pos + 24)
                val attributeCount = u16At(bytes, pos + 28)
                val attributesPos = pos + 16 + attributeStart
                if (elementName == "manifest") {
                    repeat(attributeCount) { index ->
                        val attrPos = attributesPos + index * 20
                        val attrNs = u32At(bytes, attrPos)
                        val attrName = pool.string(u32At(bytes, attrPos + 4).toInt())
                        if (attrNs.toInt() == -1 && attrName == "package") {
                            packageName = attributeStringValue(bytes, attrPos, pool)
                        }
                    }
                } else if (elementName == "application") {
                    repeat(attributeCount) { index ->
                        val attrPos = attributesPos + index * 20
                        val attrNs = u32At(bytes, attrPos)
                        val attrName = pool.string(u32At(bytes, attrPos + 4).toInt())
                        if (attrName == "name" &&
                            pool.string(attrNs.toInt()) == ANDROID_NAMESPACE) {
                            applicationName = attributeStringValue(bytes, attrPos, pool)
                        }
                    }
                }
            }
            pos += chunkSize
        }
        return ManifestInfo(packageName, applicationName)
    }

    /**
     * Returns a copy of [bytes] with the <application> element's android:name set to
     * [className]. When the attribute is missing it is inserted as a new string-typed
     * attribute (namespace = android URI, name = "name"), which matches how the framework
     * reads it (by resource id 0x01010003 through the resource map).
     */
    fun setApplicationClassName(bytes: ByteArray, className: String): ByteArray {
        if (bytes.size < 16) {
            throw ManifestParseException("File too small to be AXML")
        }
        if (u16At(bytes, 0) != 0x0003) {
            throw ManifestParseException("Not an AXML file")
        }
        val pool = StringPoolReader(bytes, 8)
        val androidUriIndex = pool.stringIndexOrNull(ANDROID_NAMESPACE)
        val nameStringIndex = pool.stringIndexOrNull("name")
        val classNameIndex = pool.stringIndexOrNull(className)

        // Find the <application> start-element chunk and its android:name attribute.
        var applicationElementPos = -1
        var nameAttributePos = -1
        var pos = 8 + pool.chunkSize
        while (pos + 8 <= bytes.size) {
            val type = u16At(bytes, pos)
            val chunkSize = u32At(bytes, pos + 4).toInt()
            if (chunkSize < 8 || pos + chunkSize > bytes.size) {
                break
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val elementName = pool.string(u32At(bytes, pos + 20).toInt())
                if (elementName == "application") {
                    applicationElementPos = pos
                    val attributeStart = u16At(bytes, pos + 24)
                    val attributeCount = u16At(bytes, pos + 28)
                    val attributesPos = pos + 16 + attributeStart
                    repeat(attributeCount) { index ->
                        val attrPos = attributesPos + index * 20
                        val attrNs = u32At(bytes, attrPos)
                        if (pool.string(u32At(bytes, attrPos + 4).toInt()) == "name" &&
                            pool.string(attrNs.toInt()) == ANDROID_NAMESPACE) {
                            nameAttributePos = attrPos
                        }
                    }
                    break
                }
            }
            pos += chunkSize
        }
        if (applicationElementPos == -1) {
            throw ManifestParseException("No <application> element found")
        }

        // Ensure the required strings exist in the pool.
        val added = ArrayList<String>()
        if (androidUriIndex == null) added += ANDROID_NAMESPACE
        if (nameStringIndex == null) added += "name"
        if (classNameIndex == null) added += className
        val newPool = StringPoolBuilder(pool, added).build()
        val newPoolChunkSize = newPool.size
        // The class name string's index in the REBUILT pool: the original index when it was
        // already present, otherwise the original count plus its position among the additions.
        val newClassNameIndex = classNameIndex ?: (pool.stringCount + added.indexOf(className))

        // Patch the application element chunk.
        val oldElementSize = u32At(bytes, applicationElementPos + 4).toInt()
        val newElement = if (nameAttributePos != -1) {
            // Patch the existing android:name attribute in place.
            val relative = nameAttributePos - applicationElementPos
            val copy = bytes.copyOfRange(applicationElementPos, applicationElementPos + oldElementSize)
            writeU32(copy, relative + 8, newClassNameIndex)  // rawValue
            writeU32(copy, relative + 16, newClassNameIndex) // typedValue.data
            copy
        } else {
            // Insert a new attribute at the end of the attribute array.
            val element = bytes.copyOfRange(applicationElementPos, applicationElementPos + oldElementSize)
            val extended = ByteArray(element.size + 20)
            System.arraycopy(element, 0, extended, 0, element.size)
            val attributeCount = u16At(element, 28)
            val attributeStart = u16At(element, 24)
            val attributesEnd = 16 + attributeStart + attributeCount * 20
            writeU32(extended, attributesEnd, newAndroidUriIndex(bytes, pool))
            writeU32(extended, attributesEnd + 4, newNameIndex(bytes, pool))
            writeU32(extended, attributesEnd + 8, newClassNameIndex)
            writeU32(extended, attributesEnd + 12, 0x03000008) // size=8, res0=0, dataType=STRING
            writeU32(extended, attributesEnd + 16, newClassNameIndex)
            writeU16(extended, 28, attributeCount + 1)
            writeU32(extended, 4, extended.size)
            extended
        }

        // Splice the edited chunks back into the file. The 8-byte XML header chunk spans
        // the whole file, so it is written first (with the new total size) and the walk
        // starts at the string pool.
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes, 0, 8)
        var writePos = 8
        while (writePos + 8 <= bytes.size) {
            val chunkSize = u32At(bytes, writePos + 4).toInt()
            if (writePos == 8) {
                out.write(newPool, 0, newPool.size)
            } else if (writePos == applicationElementPos) {
                out.write(newElement, 0, newElement.size)
            } else {
                out.write(bytes, writePos, chunkSize)
            }
            writePos += chunkSize
        }
        val result = out.toByteArray()
        writeU32(result, 4, result.size)
        return result
    }

    private fun newAndroidUriIndex(bytes: ByteArray, pool: StringPoolReader): Int {
        pool.stringIndexOrNull(ANDROID_NAMESPACE)?.let { return it }
        return pool.stringCount + pool.added.indexOf(ANDROID_NAMESPACE)
    }

    private fun newNameIndex(bytes: ByteArray, pool: StringPoolReader): Int {
        pool.stringIndexOrNull("name")?.let { return it }
        return pool.stringCount + pool.added.indexOf("name")
    }

    private fun attributeStringValue(
        bytes: ByteArray, attrPos: Int, pool: StringPoolReader
    ): String? {
        val rawValue = u32At(bytes, attrPos + 8)
        if (rawValue.toInt() != -1) {
            return pool.string(rawValue.toInt())
        }
        val dataType = bytes[attrPos + 15].toInt() and 0xff
        if (dataType == TYPE_STRING) {
            return pool.string(u32At(bytes, attrPos + 16).toInt())
        }
        return null
    }

    /** Reader for the ResStringPool chunk starting at [poolStart]. */
    private class StringPoolReader(bytes: ByteArray, poolStart: Int) {
        val chunkSize: Int
        val stringCount: Int
        val strings: List<String>
        val isUtf8: Boolean
        val added: MutableList<String> = ArrayList()

        init {
            val headerSize = u16At(bytes, poolStart + 2)
            if (headerSize < 28) {
                throw ManifestParseException("Invalid string pool header")
            }
            chunkSize = u32At(bytes, poolStart + 4).toInt()
            stringCount = u32At(bytes, poolStart + 8).toInt()
            isUtf8 = u32At(bytes, poolStart + 16) and UTF8_FLAG.toLong() != 0L
            val stringsStart = u32At(bytes, poolStart + 20).toInt()
            if (stringCount < 0 || stringCount > (bytes.size - poolStart - 28) / 4) {
                throw ManifestParseException("Invalid string pool count")
            }
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

        fun string(index: Int): String = if (index in strings.indices) strings[index] else ""

        fun stringIndexOrNull(value: String): Int? {
            val index = strings.indexOf(value)
            return if (index >= 0) index else null
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
            return String(bytes, position, end - position, java.nio.charset.StandardCharsets.UTF_8)
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
    }

    /** Rebuilds the string pool chunk with the same strings plus [added] (deduped). */
    private class StringPoolBuilder(
        private val pool: StringPoolReader, private val added: List<String>
    ) {
        fun build(): ByteArray {
            pool.added.addAll(added)
            val all = pool.strings + added
            val headerSize = 28
            val offsetTableSize = all.size * 4
            // stringsStart is relative to the pool chunk start and must be 4-aligned.
            val stringsStart = headerSize + offsetTableSize
            val encoded = all.map { encodeString(it) }
            val stringsDataSize = encoded.sumOf { it.size }
            val chunkSize = stringsStart + stringsDataSize

            val out = ByteArray(chunkSize)
            writeU16(out, 0, RES_STRING_POOL_TYPE)
            writeU16(out, 2, headerSize)
            writeU32(out, 4, chunkSize)
            writeU32(out, 8, all.size)
            writeU32(out, 12, 0) // styleCount
            writeU32(out, 16, if (pool.isUtf8) UTF8_FLAG.toLong() else 0L)
            writeU32(out, 20, stringsStart)
            writeU32(out, 24, 0) // stylesStart
            var offset = 0
            encoded.forEachIndexed { index, stringBytes ->
                writeU32(out, headerSize + index * 4, offset)
                System.arraycopy(stringBytes, 0, out, stringsStart + offset, stringBytes.size)
                offset += stringBytes.size
            }
            return out
        }

        private fun encodeString(string: String): ByteArray {
            return if (pool.isUtf8) encodeUtf8(string) else encodeUtf16(string)
        }

        private fun encodeUtf8(string: String): ByteArray {
            val bytes = string.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val utf16Length = string.length
            val buffer = java.io.ByteArrayOutputStream(bytes.size + 4)
            writeLength(buffer, utf16Length)
            writeLength(buffer, bytes.size)
            buffer.write(bytes)
            buffer.write(0)
            return pad4(buffer.toByteArray())
        }

        private fun encodeUtf16(string: String): ByteArray {
            val buffer = java.io.ByteArrayOutputStream(string.length * 2 + 4)
            writeU16Raw(buffer, string.length)
            string.forEach { char ->
                writeU16Raw(buffer, char.code)
            }
            writeU16Raw(buffer, 0)
            return pad4(buffer.toByteArray())
        }

        private fun writeLength(buffer: java.io.ByteArrayOutputStream, length: Int) {
            if (length < 0x80) {
                buffer.write(length)
            } else {
                // AOSP reads a 2-byte length as ((first & 0x7F) << 8) | second.
                buffer.write(0x80 or (length shr 8))
                buffer.write(length and 0xff)
            }
        }

        private fun writeU16Raw(buffer: java.io.ByteArrayOutputStream, value: Int) {
            buffer.write(value and 0xff)
            buffer.write((value shr 8) and 0xff)
        }

        private fun pad4(bytes: ByteArray): ByteArray {
            val padding = (4 - bytes.size % 4) % 4
            if (padding == 0) {
                return bytes
            }
            return bytes + ByteArray(padding)
        }
    }

    private fun u16At(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32At(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            (bytes[offset + 1].toLong() and 0xff shl 8) or
            (bytes[offset + 2].toLong() and 0xff shl 16) or
            (bytes[offset + 3].toLong() and 0xff shl 24)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) =
        writeU32(bytes, offset, value.toLong())
}
