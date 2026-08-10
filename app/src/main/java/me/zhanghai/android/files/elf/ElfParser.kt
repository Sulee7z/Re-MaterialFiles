/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.elf

import java.nio.charset.StandardCharsets

class ElfParseException(message: String) : Exception(message)

data class ElfSection(
    val name: String,
    val type: String,
    val address: Long,
    val offset: Long,
    val size: Long
)

data class ElfProgramHeader(
    val type: String,
    val offset: Long,
    val virtualAddress: Long,
    val fileSize: Long,
    val memorySize: Long,
    val flags: String
)

class ElfFile(
    val className: String,
    val endianness: String,
    val osAbi: String,
    val type: String,
    val machine: String,
    val entryPoint: Long,
    val sections: List<ElfSection>,
    val programHeaders: List<ElfProgramHeader>,
    val strings: List<String>
)

object ElfParser {

    private const val EI_CLASS = 4
    private const val EI_DATA = 5
    private const val EI_OSABI = 7

    private val osAbiNames = mapOf(
        0 to "System V", 3 to "Linux", 9 to "FreeBSD", 17 to "Android"
    )
    private val machineNames = mapOf(
        3 to "Intel 80386", 8 to "MIPS", 20 to "PowerPC", 40 to "ARM",
        62 to "x86-64", 183 to "AArch64", 243 to "RISC-V"
    )
    private val typeNames = mapOf(
        0 to "NONE", 1 to "REL", 2 to "EXEC", 3 to "DYN", 4 to "CORE"
    )
    private val sectionTypeNames = mapOf(
        0 to "NULL", 1 to "PROGBITS", 2 to "SYMTAB", 3 to "STRTAB", 4 to "RELA",
        5 to "HASH", 6 to "DYNAMIC", 7 to "NOTE", 8 to "NOBITS", 9 to "REL",
        10 to "SHLIB", 11 to "DYNSYM", 14 to "INIT_ARRAY", 15 to "FINI_ARRAY",
        16 to "PREINIT_ARRAY", 17 to "GROUP", 18 to "SYMTAB_SHNDX"
    )
    private val programTypeNames = mapOf(
        0 to "NULL", 1 to "LOAD", 2 to "DYNAMIC", 3 to "INTERP", 4 to "NOTE",
        5 to "SHLIB", 6 to "PHDR", 7 to "TLS", 0x6474e550 to "GNU_EH_FRAME",
        0x6474e551 to "GNU_STACK", 0x6474e552 to "GNU_RELRO"
    )

    private class Reader(private val bytes: ByteArray, private val littleEndian: Boolean) {
        fun u8(offset: Int): Int = bytes[offset].toInt() and 0xff

        fun u16(offset: Int): Int {
            return if (littleEndian) {
                u8(offset) or (u8(offset + 1) shl 8)
            } else {
                (u8(offset) shl 8) or u8(offset + 1)
            }
        }

        fun u32(offset: Int): Long {
            return if (littleEndian) {
                (u8(offset).toLong()) or (u8(offset + 1).toLong() shl 8) or
                    (u8(offset + 2).toLong() shl 16) or (u8(offset + 3).toLong() shl 24)
            } else {
                (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
                    (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()
            }
        }

        fun u64(offset: Int): Long {
            return if (littleEndian) {
                u32(offset) or (u32(offset + 4) shl 32)
            } else {
                (u32(offset) shl 32) or u32(offset + 4)
            }
        }
    }

    fun parse(bytes: ByteArray): ElfFile {
        val is64Bit = if (bytes.size >= 65) bytes[EI_CLASS] == 2.toByte() else false
        // A 64-bit ELF header is at least 64 bytes; use that before reading any 64-bit
        // fields past offset 52.
        val minimumHeaderSize = if (is64Bit) 64 else 52
        if (bytes.size < minimumHeaderSize) {
            throw ElfParseException("File too small to be an ELF file")
        }
        val reader = Reader(bytes, littleEndian = true)
        val magic = ByteArray(4)
        bytes.copyInto(magic, 0, 0, 4)
        if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() ||
            magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()) {
            throw ElfParseException("Not a valid ELF file")
        }
        val isLittleEndian = reader.u8(EI_DATA) == 1
        val endianReader = Reader(bytes, isLittleEndian)
        val osAbi = osAbiNames[reader.u8(EI_OSABI)] ?: "0x%02x".format(reader.u8(EI_OSABI))
        val type = typeNames[endianReader.u16(16)] ?: "0x%04x".format(endianReader.u16(16))
        val machine = machineNames[endianReader.u16(18)]
            ?: "0x%04x".format(endianReader.u16(18))
        val entryPoint = if (is64Bit) endianReader.u64(24) else endianReader.u32(24)
        val programHeaderOffset = if (is64Bit) endianReader.u64(32) else endianReader.u32(28)
        val sectionHeaderOffset = if (is64Bit) endianReader.u64(40) else endianReader.u32(32)
        val programHeaderEntrySize = if (is64Bit) endianReader.u16(54) else endianReader.u16(42)
        val programHeaderCount = if (is64Bit) endianReader.u16(56) else endianReader.u16(44)
        val sectionHeaderEntrySize = if (is64Bit) endianReader.u16(58) else endianReader.u16(46)
        val sectionHeaderCount = if (is64Bit) endianReader.u16(60) else endianReader.u16(48)
        val sectionNameStringIndex = if (is64Bit) endianReader.u16(62) else endianReader.u16(50)

        // Section name string table: sh_name is a byte offset into this table, so keep the
        // table offset and read each name at tableOffset + sh_name (not as a list index).
        val sectionNameTableOffset = if (sectionNameStringIndex in 0 until sectionHeaderCount) {
            val entryOffsetLong = sectionHeaderOffset + sectionNameStringIndex.toLong() *
                sectionHeaderEntrySize
            if (entryOffsetLong >= 0 && entryOffsetLong + sectionHeaderEntrySize <= bytes.size) {
                val entryOffset = entryOffsetLong.toInt()
                if (is64Bit) {
                    endianReader.u64(entryOffset + 24)
                } else {
                    endianReader.u32(entryOffset + 16)
                }
            } else {
                -1L
            }
        } else {
            -1L
        }

        val sections = ArrayList<ElfSection>(
            minOf(sectionHeaderCount, bytes.size / sectionHeaderEntrySize.coerceAtLeast(1))
        )
        repeat(sectionHeaderCount) { index ->
            // Compute in Long: a 64-bit ELF may place the table past 2 GiB, where toInt()
            // would overflow negative and then index into the byte array out of bounds.
            val entryOffsetLong = sectionHeaderOffset + index.toLong() * sectionHeaderEntrySize
            if (entryOffsetLong < 0 || entryOffsetLong + sectionHeaderEntrySize > bytes.size) {
                return@repeat
            }
            val entryOffset = entryOffsetLong.toInt()
            val nameIndex = endianReader.u32(entryOffset)
            val sectionType = sectionTypeNames[endianReader.u32(entryOffset + 4).toInt()]
                ?: "0x%x".format(endianReader.u32(entryOffset + 4))
            val address = if (is64Bit) {
                endianReader.u64(entryOffset + 16)
            } else {
                endianReader.u32(entryOffset + 12)
            }
            val offset = if (is64Bit) {
                endianReader.u64(entryOffset + 24)
            } else {
                endianReader.u32(entryOffset + 16)
            }
            val size = if (is64Bit) {
                endianReader.u64(entryOffset + 32)
            } else {
                endianReader.u32(entryOffset + 20)
            }
            val name = if (sectionNameTableOffset >= 0) {
                readCStringAt(bytes, sectionNameTableOffset + nameIndex)
            } else {
                ""
            }
            sections.add(ElfSection(name, sectionType, address, offset, size))
        }

        val programHeaders = ArrayList<ElfProgramHeader>(
            minOf(programHeaderCount, bytes.size / programHeaderEntrySize.coerceAtLeast(1))
        )
        repeat(programHeaderCount) { index ->
            // Long arithmetic, see the section loop above.
            val entryOffsetLong = programHeaderOffset + index.toLong() * programHeaderEntrySize
            if (entryOffsetLong < 0 || entryOffsetLong + programHeaderEntrySize > bytes.size) {
                return@repeat
            }
            val entryOffset = entryOffsetLong.toInt()
            val programType = programTypeNames[endianReader.u32(entryOffset).toInt()]
                ?: "0x%x".format(endianReader.u32(entryOffset))
            val offset = if (is64Bit) {
                endianReader.u64(entryOffset + 8)
            } else {
                endianReader.u32(entryOffset + 4)
            }
            val virtualAddress = if (is64Bit) {
                endianReader.u64(entryOffset + 16)
            } else {
                endianReader.u32(entryOffset + 8)
            }
            val fileSize = if (is64Bit) {
                endianReader.u64(entryOffset + 32)
            } else {
                endianReader.u32(entryOffset + 16)
            }
            val memorySize = if (is64Bit) {
                endianReader.u64(entryOffset + 40)
            } else {
                endianReader.u32(entryOffset + 20)
            }
            val flags = if (is64Bit) {
                val value = endianReader.u32(entryOffset + 4).toInt()
                listOf(
                    ((value and 1) != 0) to 'X', ((value and 2) != 0) to 'W', ((value and 4) != 0) to 'R'
                ).filter { it.first }.joinToString("") { it.second.toString() }
            } else {
                val value = endianReader.u32(entryOffset + 24).toInt()
                listOf(
                    ((value and 1) != 0) to 'X', ((value and 2) != 0) to 'W', ((value and 4) != 0) to 'R'
                ).filter { it.first }.joinToString("") { it.second.toString() }
            }
            programHeaders.add(
                ElfProgramHeader(
                    programType, offset, virtualAddress, fileSize, memorySize,
                    flags.ifEmpty { "-" }
                )
            )
        }

        val strings = extractStrings(bytes)
        return ElfFile(
            if (is64Bit) "64-bit" else "32-bit",
            if (isLittleEndian) "Little endian" else "Big endian",
            osAbi, type, machine, entryPoint, sections, programHeaders, strings
        )
    }

    private fun readCStringAt(bytes: ByteArray, offset: Long): String {
        if (offset < 0 || offset >= bytes.size) {
            return ""
        }
        val builder = StringBuilder()
        var position = offset
        while (position < bytes.size && bytes[position.toInt()].toInt() != 0) {
            builder.append(bytes[position.toInt()].toInt().toChar())
            position++
        }
        return builder.toString()
    }

    private fun extractStrings(bytes: ByteArray): List<String> {
        val strings = LinkedHashSet<String>()
        val builder = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xff
            if (byte in 0x20..0x7e) {
                builder.append(byte.toChar())
            } else {
                if (builder.length >= 4) {
                    strings.add(builder.toString())
                }
                builder.setLength(0)
            }
            index++
        }
        if (builder.length >= 4) {
            strings.add(builder.toString())
        }
        return strings.toList()
    }
}
