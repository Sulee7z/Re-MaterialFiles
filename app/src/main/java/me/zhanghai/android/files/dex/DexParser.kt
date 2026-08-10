/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Parser for the Dalvik Executable (DEX) format, see
 * https://source.android.com/docs/core/runtime/dex-format
 */
object DexParser {

    fun parse(bytes: ByteArray): DexFile {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = 0x70
        if (bytes.size < headerSize) {
            throw DexParseException("File too small to be a DEX file")
        }
        val magic = ByteArray(8)
        buffer.get(magic)
        if (magic[0] != 'd'.code.toByte() || magic[1] != 'e'.code.toByte()
            || magic[2] != 'x'.code.toByte() || magic[3] != '\n'.code.toByte()
            || magic[7] != 0.toByte()) {
            throw DexParseException("Invalid DEX magic")
        }
        val version = String(magic, 4, 3, StandardCharsets.US_ASCII)
        buffer.position(0x38)
        val stringIdsSize = buffer.int
        val stringIdsOffset = buffer.int
        val typeIdsSize = buffer.int
        val typeIdsOffset = buffer.int
        val protoIdsSize = buffer.int
        val protoIdsOffset = buffer.int
        val fieldIdsSize = buffer.int
        val fieldIdsOffset = buffer.int
        val methodIdsSize = buffer.int
        val methodIdsOffset = buffer.int
        val classDefsSize = buffer.int
        val classDefsOffset = buffer.int

        checkRange(bytes, stringIdsOffset, stringIdsSize.toLong() * 4)
        checkRange(bytes, typeIdsOffset, typeIdsSize.toLong() * 4)
        checkRange(bytes, protoIdsOffset, protoIdsSize.toLong() * 12)
        checkRange(bytes, fieldIdsOffset, fieldIdsSize.toLong() * 8)
        checkRange(bytes, methodIdsOffset, methodIdsSize.toLong() * 8)
        checkRange(bytes, classDefsOffset, classDefsSize.toLong() * 32)

        val strings = ArrayList<String>(stringIdsSize)
        repeat(stringIdsSize) { index ->
            val stringDataOffset = buffer.getInt(stringIdsOffset + index * 4)
            strings.add(readString(bytes, stringDataOffset))
        }
        val types = ArrayList<String>(typeIdsSize)
        repeat(typeIdsSize) { index ->
            types.add(strings[buffer.getInt(typeIdsOffset + index * 4)])
        }
        val protos = ArrayList<DexProto>(protoIdsSize)
        repeat(protoIdsSize) { index ->
            val protoOffset = protoIdsOffset + index * 12
            val shortyIndex = buffer.getInt(protoOffset)
            val returnTypeIndex = buffer.getInt(protoOffset + 4)
            val parametersOffset = buffer.getInt(protoOffset + 8)
            // A type_list: uint size followed by ushort type indices, padded to 4 bytes.
            val parameters = if (parametersOffset == 0) {
                emptyList<String>()
            } else {
                checkRange(bytes, parametersOffset, 4)
                val parametersSize = readU32(bytes, parametersOffset)
                // Guard against a crafted/corrupt size that would OOM the ArrayList.
                checkRange(bytes, parametersOffset + 4, parametersSize.toLong() * 2)
                val parameterTypeIndices = ArrayList<String>(parametersSize)
                repeat(parametersSize) { parameterIndex ->
                    parameterTypeIndices.add(
                        types[readU16(bytes, parametersOffset + 4 + parameterIndex * 2)]
                    )
                }
                parameterTypeIndices
            }
            protos.add(
                DexProto(
                    strings[shortyIndex], types[returnTypeIndex], parameters
                )
            )
        }
        val fieldRefs = ArrayList<DexFieldRef>(fieldIdsSize)
        repeat(fieldIdsSize) { index ->
            val fieldOffset = fieldIdsOffset + index * 8
            val classIndex = buffer.getShort(fieldOffset).toInt() and 0xffff
            val typeIndex = buffer.getShort(fieldOffset + 2).toInt() and 0xffff
            val nameIndex = buffer.getInt(fieldOffset + 4)
            fieldRefs.add(
                DexFieldRef(types[classIndex], strings[nameIndex], types[typeIndex])
            )
        }
        val methodRefs = ArrayList<DexMethodRef>(methodIdsSize)
        repeat(methodIdsSize) { index ->
            val methodOffset = methodIdsOffset + index * 8
            val classIndex = buffer.getShort(methodOffset).toInt() and 0xffff
            val protoIndex = buffer.getShort(methodOffset + 2).toInt() and 0xffff
            val nameIndex = buffer.getInt(methodOffset + 4)
            methodRefs.add(
                DexMethodRef(types[classIndex], strings[nameIndex], protos[protoIndex])
            )
        }

        val classes = ArrayList<DexClass>(classDefsSize)
        repeat(classDefsSize) { index ->
            val classDefOffset = classDefsOffset + index * 32
            val classIndex = buffer.getInt(classDefOffset)
            val accessFlags = buffer.getInt(classDefOffset + 4)
            val superclassIndex = buffer.getInt(classDefOffset + 8)
            val interfacesOffset = buffer.getInt(classDefOffset + 12)
            val sourceFileIndex = buffer.getInt(classDefOffset + 16)
            val classDataOffset = buffer.getInt(classDefOffset + 24)
            val interfaces = if (interfacesOffset == 0) {
                emptyList()
            } else {
                // interfaces_off points to a type_list: uint size + ushort type_idx[].
                // (Previously parsed as ULEB128 like class_data_item, which produced
                // garbage indices and could throw ArrayIndexOutOfBoundsException.)
                checkRange(bytes, interfacesOffset, 4)
                val interfacesSize = buffer.getInt(interfacesOffset)
                // Guard against a crafted/corrupt size: allocating ArrayList with it would
                // OOM before the per-index reads fail.
                checkRange(bytes, interfacesOffset + 4, interfacesSize.toLong() * 2)
                val interfaceTypes = ArrayList<String>(interfacesSize)
                var typeIndexOffset = interfacesOffset + 4
                repeat(interfacesSize) {
                    interfaceTypes.add(types[buffer.getShort(typeIndexOffset).toInt() and 0xffff])
                    typeIndexOffset += 2
                }
                interfaceTypes
            }
            val (fieldDefs, methodDefs) = if (classDataOffset == 0) {
                emptyList<DexFieldDef>() to emptyList<DexMethodDef>()
            } else {
                readClassData(bytes, classDataOffset, fieldRefs, methodRefs)
            }
            val className = types[classIndex]
            val references = mutableListOf<DexReference>()
            if (superclassIndex >= 0) {
                references += DexReference(className, "superclass", types[superclassIndex])
            }
            interfaces.forEach { references += DexReference(className, "implements", it) }
            fieldDefs.forEach {
                references += DexReference(className, "field type", it.field.type)
            }
            methodDefs.forEach { methodDef ->
                val proto = methodDef.method.proto
                references += DexReference(className, "method return", proto.returnType)
                proto.parameters.forEach {
                    references += DexReference(className, "method param", it)
                }
                methodDef.code?.let { code ->
                    scanCodeReferences(code.insns, types, fieldRefs, methodRefs)
                        .forEach { (kind, target) ->
                            references += DexReference(className, kind, target)
                        }
                }
            }
            classes.add(
                DexClass(
                    className,
                    accessFlags,
                    if (superclassIndex >= 0) types[superclassIndex] else null,
                    interfaces,
                    if (sourceFileIndex >= 0) strings[sourceFileIndex] else null,
                    fieldDefs,
                    methodDefs,
                    references
                )
            )
        }
        return DexFile(version, strings, types, fieldRefs, methodRefs, classes)
    }

    /**
     * Scans method bytecode for references to types, fields and methods.
     */
    private fun scanCodeReferences(
        insns: ShortArray,
        types: List<String>,
        fields: List<DexFieldRef>,
        methods: List<DexMethodRef>
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var position = 0
        while (position < insns.size) {
            val unit = insns[position].toInt() and 0xffff
            val op = unit and 0xff
            val refIndex = if (position + 1 < insns.size) {
                insns[position + 1].toInt() and 0xffff
            } else {
                0
            }
            when (op) {
                // 0x21 (array-length) has no type index; excluding it avoids treating the
                // next instruction's opcode as a type reference.
                in 0x1c..0x1c, in 0x1f..0x20, in 0x22..0x25 -> {
                    if (refIndex < types.size) {
                        result.add(codeOpName(op) to types[refIndex])
                    }
                }
                in 0x52..0x6d -> {
                    if (refIndex < fields.size) {
                        val field = fields[refIndex]
                        result.add(codeOpName(op) to "${field.className}->${field.name}:${field.type}")
                    }
                }
                in 0x6e..0x72, in 0x74..0x78, in 0xfa..0xfd -> {
                    if (refIndex < methods.size) {
                        result.add(codeOpName(op) to methods[refIndex].toString())
                    }
                }
            }
            position += codeOpUnitSize(op)
        }
        return result
    }

    private fun codeOpName(op: Int): String = when (op) {
        0x1c -> "const-class"
        0x1f -> "check-cast"
        0x20 -> "instance-of"
        0x22 -> "new-instance"
        0x23 -> "new-array"
        0x24, 0x25 -> "filled-new-array"
        in 0x52..0x5f -> "field"
        in 0x60..0x6d -> "field"
        in 0x6e..0x72, in 0x74..0x78 -> "invoke"
        0xfa, 0xfb -> "invoke-polymorphic"
        0xfc, 0xfd -> "invoke-custom"
        else -> "reference"
    }

    private fun codeOpUnitSize(op: Int): Int = when (op) {
        0x00, 0x01, 0x04, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        0x10, 0x11, 0x12, 0x1d, 0x1e, 0x21, 0x27, 0x28,
        in 0xb0..0xcf -> 1
        0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f,
        0x20, 0x22, 0x23, 0x29, 0x2d, 0x2e, 0x2f, 0x30, 0x31,
        in 0x32..0x3d, in 0x44..0x6d, in 0x90..0xaa, in 0xd0..0xe3 -> 2
        0x03, 0x06, 0x09, 0x14, 0x17, 0x18, 0x1b, 0x24, 0x25, 0x26,
        0x2a, 0x2b, 0x2c, in 0x6e..0x79, 0xfc, 0xfd, 0xfe, 0xff -> 3
        0xfa, 0xfb -> 4
        else -> 1
    }

    private fun checkRange(bytes: ByteArray, offset: Int, length: Long) {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw DexParseException("DEX table out of range")
        }
    }

    private fun readClassData(
        bytes: ByteArray,
        offset: Int,
        fieldRefs: List<DexFieldRef>,
        methodRefs: List<DexMethodRef>
    ): Pair<List<DexFieldDef>, List<DexMethodDef>> {
        checkRange(bytes, offset, 1)
        val (staticFieldsSize, staticFieldsSizeEnd) = readUleb128(bytes, offset)
        val (instanceFieldsSize, instanceFieldsSizeEnd) = readUleb128(bytes, staticFieldsSizeEnd)
        val (directMethodsSize, directMethodsSizeEnd) = readUleb128(bytes, instanceFieldsSizeEnd)
        val (virtualMethodsSize, virtualMethodsSizeEnd) = readUleb128(bytes, directMethodsSizeEnd)
        var position = virtualMethodsSizeEnd
        val fieldDefs = ArrayList<DexFieldDef>(staticFieldsSize + instanceFieldsSize)
        // Each section (static fields, instance fields, direct methods, virtual methods) has its
        // own index accumulator starting from 0.
        var fieldIndexDiff = 0
        repeat(staticFieldsSize) {
            val (fieldIndexDiffValue, fieldIndexDiffEnd) = readUleb128(bytes, position)
            fieldIndexDiff += fieldIndexDiffValue
            position = fieldIndexDiffEnd
            val (accessFlags, accessFlagsEnd) = readUleb128(bytes, position)
            position = accessFlagsEnd
            fieldDefs.add(DexFieldDef(fieldRefs[fieldIndexDiff], accessFlags))
        }
        fieldIndexDiff = 0
        repeat(instanceFieldsSize) {
            val (fieldIndexDiffValue, fieldIndexDiffEnd) = readUleb128(bytes, position)
            fieldIndexDiff += fieldIndexDiffValue
            position = fieldIndexDiffEnd
            val (accessFlags, accessFlagsEnd) = readUleb128(bytes, position)
            position = accessFlagsEnd
            fieldDefs.add(DexFieldDef(fieldRefs[fieldIndexDiff], accessFlags))
        }
        val methodDefs = ArrayList<DexMethodDef>(directMethodsSize + virtualMethodsSize)
        var methodIndexDiff = 0
        repeat(directMethodsSize) {
            val (methodIndexDiffValue, methodIndexDiffEnd) = readUleb128(bytes, position)
            methodIndexDiff += methodIndexDiffValue
            position = methodIndexDiffEnd
            val (accessFlags, accessFlagsEnd) = readUleb128(bytes, position)
            position = accessFlagsEnd
            val (codeOffset, codeOffsetEnd) = readUleb128(bytes, position)
            position = codeOffsetEnd
            val code = if (codeOffset == 0) null else readCodeItem(bytes, codeOffset)
            methodDefs.add(DexMethodDef(methodRefs[methodIndexDiff], accessFlags, code))
        }
        methodIndexDiff = 0
        repeat(virtualMethodsSize) {
            val (methodIndexDiffValue, methodIndexDiffEnd) = readUleb128(bytes, position)
            methodIndexDiff += methodIndexDiffValue
            position = methodIndexDiffEnd
            val (accessFlags, accessFlagsEnd) = readUleb128(bytes, position)
            position = accessFlagsEnd
            val (codeOffset, codeOffsetEnd) = readUleb128(bytes, position)
            position = codeOffsetEnd
            val code = if (codeOffset == 0) null else readCodeItem(bytes, codeOffset)
            methodDefs.add(DexMethodDef(methodRefs[methodIndexDiff], accessFlags, code))
        }
        return fieldDefs to methodDefs
    }

    private fun readCodeItem(bytes: ByteArray, offset: Int): DexCode {
        // The code item header uses fixed-size fields: ushort registers_size, ushort ins_size,
        // ushort outs_size, ushort tries_size, uint debug_info_off, ushort insns_size, followed
        // by 2 bytes of padding so that insns start on a 4-byte boundary.
        checkRange(bytes, offset, 16)
        var position = offset
        val registersSize = readU16(bytes, position)
        position += 2
        val insSize = readU16(bytes, position)
        position += 2
        val outsSize = readU16(bytes, position)
        position += 2
        position += 2 // tries_size
        position += 4 // debug_info_off
        // insns_size is a full uint; methods with more than 65536 code units (e.g. from
        // obfuscation or generated code) would be truncated if read as ushort.
        val insnsSize = readU32(bytes, position)
        position += 4 // insns_size and padding
        // Guard against a crafted/corrupt size that would OOM the ShortArray allocation.
        checkRange(bytes, position, insnsSize.toLong() * 2)
        val insns = ShortArray(insnsSize)
        repeat(insnsSize) { index ->
            insns[index] = readU16(bytes, position + index * 2).toShort()
        }
        return DexCode(registersSize, insSize, outsSize, insns)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUleb128(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var position = offset
        while (true) {
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) {
                break
            }
            shift += 7
            if (shift > 35) {
                throw DexParseException("Invalid ULEB128")
            }
        }
        return result to position
    }

    private fun readString(bytes: ByteArray, offset: Int): String {
        val (_, sizeEnd) = readUleb128(bytes, offset)
        var end = sizeEnd
        // Guard against truncated/crafted dex without a NUL terminator.
        while (end < bytes.size && bytes[end].toInt() != 0) {
            end++
        }
        return decodeModifiedUtf8(bytes, sizeEnd, end)
    }

    private fun decodeModifiedUtf8(bytes: ByteArray, start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        var position = start
        while (position < end) {
            val first = bytes[position++].toInt() and 0xff
            if (first < 0x80) {
                builder.append(first.toChar())
            } else if (first < 0xe0) {
                if (position >= end) {
                    throw DexParseException("Invalid modified UTF-8")
                }
                val second = bytes[position++].toInt() and 0xff
                builder.append(((first and 0x1f) shl 6 or (second and 0x3f)).toChar())
            } else {
                if (position + 1 >= end) {
                    throw DexParseException("Invalid modified UTF-8")
                }
                val second = bytes[position++].toInt() and 0xff
                val third = bytes[position++].toInt() and 0xff
                builder.append(
                    ((first and 0x0f) shl 12 or ((second and 0x3f) shl 6) or (third and 0x3f))
                        .toChar()
                )
            }
        }
        return builder.toString()
    }
}
