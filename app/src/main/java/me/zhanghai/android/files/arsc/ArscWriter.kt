/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.arsc

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Serializes an [ArscFile] model back into binary resources.arsc. The output is always
 * dense and non-compact (FLAG_SPARSE/FLAG_COMPACT are never emitted), and string pools
 * are re-encoded as UTF-8, which every Android version accepts.
 *
 * Style spans in string pools are intentionally dropped (styleCount = 0); the string
 * values themselves are preserved.
 */
object ArscWriter {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_RESOURCE_MAP_TYPE = 0x0180
    private const val NO_ENTRY = 0xffffffffL

    private const val FLAG_COMPLEX = 0x0001

    fun write(file: ArscFile): ByteArray {
        // Build the payload first so the table header's chunk size can cover the whole
        // file (aapt2 walks chunks from the header and warns about anything trailing it).
        val content = ByteArrayOutputStream()
        // Global string pool (always present; may be empty).
        content.write(writeStringPool(file.globalPool))
        // Global resource map.
        if (file.resourceMap.isNotEmpty()) {
            val mapSize = 8 + file.resourceMap.size * 4
            writeChunkHeader(content, RES_TABLE_RESOURCE_MAP_TYPE, mapSize, 8)
            file.resourceMap.forEach { writeU32(content, it) }
        }
        file.packages.forEach { content.write(writePackage(it)) }

        val contentBytes = content.toByteArray()
        val out = ByteArrayOutputStream()
        // Table header (its chunk size spans the entire table).
        writeChunkHeader(out, RES_TABLE_TYPE, 12 + contentBytes.size, 12)
        writeU32(out, file.packages.size)
        out.write(contentBytes)
        return out.toByteArray()
    }

    private fun writePackage(pkg: ArscPackage): ByteArray {
        // Build the sub-chunks first so the header offsets can point at them.
        val typeStrings = writeStringPool(pkg.typeStrings)
        val keyStrings = writeStringPool(pkg.keyStrings)
        val subChunks = ByteArrayOutputStream()
        subChunks.write(typeStrings)
        subChunks.write(keyStrings)
        pkg.typeSpecs.forEach { subChunks.write(writeTypeSpec(it)) }
        pkg.types.forEach { subChunks.write(writeType(it, pkg)) }

        val out = ByteArrayOutputStream()
        // header (8) + id (4) + name (256) + typeStrings (4) + lastPublicType (4) +
        // keyStrings (4) + lastPublicKey (4) + typeIdOffset (4) = 288
        writeChunkHeader(out, RES_TABLE_PACKAGE_TYPE, 288 + subChunks.size(), 288)
        writeU32(out, pkg.id)
        writeUtf16Fixed(out, pkg.name, 128)
        writeU32(out, 288) // typeStrings offset (relative to package start)
        writeU32(out, 0) // lastPublicType
        writeU32(out, 288 + typeStrings.size) // keyStrings offset
        writeU32(out, 0) // lastPublicKey
        writeU32(out, 0) // typeIdOffset
        out.write(subChunks.toByteArray())
        return out.toByteArray()
    }

    private fun writeTypeSpec(spec: ArscTypeSpec): ByteArray {
        val size = 16 + spec.flags.size * 4
        val out = ByteArrayOutputStream()
        writeChunkHeader(out, RES_TABLE_TYPE_SPEC_TYPE, size, 16)
        out.write(spec.id)
        out.write(0) // res0
        writeU16(out, 0) // res1
        writeU32(out, spec.flags.size)
        spec.flags.forEach { writeU32(out, it) }
        return out.toByteArray()
    }

    private fun writeType(type: ArscType, pkg: ArscPackage): ByteArray {
        // Always write the dense layout with an expanded (or empty) config blob.
        val config = type.config
        val configSize = if (config.size >= 28) config.size else 28
        val effectiveConfig = if (config.size >= 28) config else ByteArray(28)
        val headerSize = 20 + configSize
        val entryCount = type.entries.size
        val offsetsSize = entryCount * 4
        val entriesStart = headerSize + offsetsSize

        // Serialize the entries, padding each to 4 bytes.
        val entryBlob = ByteArrayOutputStream()
        val offsets = IntArray(entryCount) { -1 }
        type.entries.forEachIndexed { index, entry ->
            if (entry == null) {
                return@forEachIndexed
            }
            offsets[index] = entryBlob.size()
            val entryBytes = writeEntry(entry)
            entryBlob.write(entryBytes)
            val padding = (4 - entryBytes.size % 4) % 4
            repeat(padding) { entryBlob.write(0) }
        }

        val out = ByteArrayOutputStream()
        writeChunkHeader(out, RES_TABLE_TYPE_TYPE, entriesStart + entryBlob.size(), headerSize)
        out.write(type.id)
        out.write(0) // flags: dense, non-sparse
        writeU16(out, 0) // reserved
        writeU32(out, entryCount)
        writeU32(out, entriesStart)
        out.write(effectiveConfig, 0, effectiveConfig.size)
        offsets.forEach { writeU32(out, if (it < 0) NO_ENTRY else it.toLong()) }
        out.write(entryBlob.toByteArray())
        return out.toByteArray()
    }

    private fun writeEntry(entry: ArscEntry): ByteArray {
        val out = ByteArrayOutputStream()
        if (entry.isComplex) {
            // ResTable_map_entry: entry (size=16) + parent + count + map items.
            writeU16(out, 16)
            writeU16(out, entry.flags or FLAG_COMPLEX)
            writeU32(out, entry.keyIndex)
            writeU32(out, entry.parent ?: 0L)
            val items = entry.mapItems ?: emptyList()
            writeU32(out, items.size)
            items.forEach { item ->
                writeU32(out, item.name)
                writeValue(out, item.value)
            }
        } else {
            writeU16(out, 8)
            writeU16(out, entry.flags and FLAG_COMPLEX.inv())
            writeU32(out, entry.keyIndex)
            writeValue(out, entry.value ?: ArscValue(ArscValue.TYPE_NULL, 0))
        }
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, value: ArscValue) {
        writeU16(out, 8) // size
        out.write(0) // res0
        out.write(value.dataType)
        writeU32(out, value.data)
    }

    /** Rebuilds a string pool as UTF-8 with 4-byte aligned entries. */
    private fun writeStringPool(pool: ArscStringPool): ByteArray {
        val strings = pool.strings
        val headerSize = 28
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val encoded = strings.map { encodeUtf8String(it) }
        val stringsDataSize = encoded.sumOf { it.size }
        val chunkSize = stringsStart + stringsDataSize

        val out = ByteArrayOutputStream()
        writeChunkHeader(out, RES_STRING_POOL_TYPE, chunkSize, headerSize)
        writeU32(out, strings.size)
        writeU32(out, 0) // styleCount
        writeU32(out, 0x100) // UTF8_FLAG
        writeU32(out, stringsStart)
        writeU32(out, 0) // stylesStart
        var offset = 0
        encoded.forEach { bytes ->
            writeU32(out, offset)
            offset += bytes.size
        }
        encoded.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun encodeUtf8String(string: String): ByteArray {
        val bytes = string.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteArrayOutputStream(bytes.size + 4)
        writeLength(buffer, string.length) // UTF-16 length
        writeLength(buffer, bytes.size) // UTF-8 byte length
        buffer.write(bytes)
        buffer.write(0)
        val padding = (4 - buffer.size() % 4) % 4
        repeat(padding) { buffer.write(0) }
        return buffer.toByteArray()
    }

    private fun writeLength(buffer: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            buffer.write(length)
        } else {
            // AOSP reads a 2-byte length as ((first & 0x7F) << 8) | second.
            buffer.write(0x80 or (length ushr 8))
            buffer.write(length and 0xff)
        }
    }

    private fun writeChunkHeader(out: ByteArrayOutputStream, type: Int, size: Int, headerSize: Int) {
        writeU16(out, type)
        writeU16(out, headerSize)
        writeU32(out, size)
    }

    private fun writeUtf16Fixed(out: ByteArrayOutputStream, string: String, charCount: Int) {
        var index = 0
        while (index < charCount) {
            val char = if (index < string.length) string[index] else '\u0000'
            writeU16(out, char.code)
            index++
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xff).toInt())
        out.write(((value shr 8) and 0xff).toInt())
        out.write(((value shr 16) and 0xff).toInt())
        out.write(((value shr 24) and 0xff).toInt())
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) =
        writeU32(out, value.toLong())
}
