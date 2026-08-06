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
            val parameters = if (parametersOffset == 0) {
                emptyList()
            } else {
                val (parametersSize, firstSizeEnd) = readUleb128(bytes, parametersOffset)
                val parameterTypeIndices = ArrayList<String>(parametersSize)
                var typeIndexOffset = firstSizeEnd
                repeat(parametersSize) {
                    val (typeIndex, end) = readUleb128(bytes, typeIndexOffset)
                    parameterTypeIndices.add(types[typeIndex])
                    typeIndexOffset = end
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
                val (interfacesSize, firstSizeEnd) = readUleb128(bytes, interfacesOffset)
                val interfaceTypes = ArrayList<String>(interfacesSize)
                var typeIndexOffset = firstSizeEnd
                repeat(interfacesSize) {
                    val (typeIndex, end) = readUleb128(bytes, typeIndexOffset)
                    interfaceTypes.add(types[typeIndex])
                    typeIndexOffset = end
                }
                interfaceTypes
            }
            val (fieldDefs, methodDefs) = if (classDataOffset == 0) {
                emptyList<DexFieldDef>() to emptyList<DexMethodDef>()
            } else {
                readClassData(bytes, classDataOffset, fieldRefs, methodRefs)
            }
            classes.add(
                DexClass(
                    types[classIndex],
                    accessFlags,
                    if (superclassIndex >= 0) types[superclassIndex] else null,
                    interfaces,
                    if (sourceFileIndex >= 0) strings[sourceFileIndex] else null,
                    fieldDefs,
                    methodDefs
                )
            )
        }
        return DexFile(version, strings, types, fieldRefs, methodRefs, classes)
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
        val (staticFieldsSize, staticFieldsSizeEnd) = readUleb128(bytes, offset)
        val (instanceFieldsSize, instanceFieldsSizeEnd) = readUleb128(bytes, staticFieldsSizeEnd)
        val (directMethodsSize, directMethodsSizeEnd) = readUleb128(bytes, instanceFieldsSizeEnd)
        val (virtualMethodsSize, virtualMethodsSizeEnd) = readUleb128(bytes, directMethodsSizeEnd)
        var position = virtualMethodsSizeEnd
        val fieldDefs = ArrayList<DexFieldDef>(staticFieldsSize + instanceFieldsSize)
        var fieldIndexDiff = 0
        repeat(staticFieldsSize + instanceFieldsSize) {
            val (fieldIndexDiffValue, fieldIndexDiffEnd) = readUleb128(bytes, position)
            fieldIndexDiff += fieldIndexDiffValue
            position = fieldIndexDiffEnd
            val (accessFlags, accessFlagsEnd) = readUleb128(bytes, position)
            position = accessFlagsEnd
            fieldDefs.add(DexFieldDef(fieldRefs[fieldIndexDiff], accessFlags))
        }
        val methodDefs = ArrayList<DexMethodDef>(directMethodsSize + virtualMethodsSize)
        var methodIndexDiff = 0
        repeat(directMethodsSize + virtualMethodsSize) {
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
        val (registersSize, registersSizeEnd) = readUleb128(bytes, offset)
        val (insSize, insSizeEnd) = readUleb128(bytes, registersSizeEnd)
        val (outsSize, outsSizeEnd) = readUleb128(bytes, insSizeEnd)
        val (_, triesSizeEnd) = readUleb128(bytes, outsSizeEnd)
        val (_, debugInfoOffsetEnd) = readUleb128(bytes, triesSizeEnd)
        val insnsSize = (bytes[debugInfoOffsetEnd].toInt() and 0xff) or
            ((bytes[debugInfoOffsetEnd + 1].toInt() and 0xff) shl 8)
        val insnsOffset = debugInfoOffsetEnd + 2
        val insns = ShortArray(insnsSize)
        repeat(insnsSize) { index ->
            val byteOffset = insnsOffset + index * 2
            insns[index] = ((bytes[byteOffset].toInt() and 0xff) or
                ((bytes[byteOffset + 1].toInt() and 0xff) shl 8)).toShort()
        }
        return DexCode(registersSize, insSize, outsSize, insns)
    }

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
        while (bytes[end].toInt() != 0) {
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
