/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

/**
 * Disassembles DEX method bytecode into smali-style text. See
 * https://source.android.com/docs/core/runtime/dalvik-bytecode
 */
class DexDisassembler(private val dex: DexFile) {

    private class Opcode(val name: String, val format: String)

    private val opcodes = Array(0x100) { index ->
        when (index) {
            0x00 -> Opcode("nop", "10x")
            0x01 -> Opcode("move", "12x")
            0x02 -> Opcode("move/from16", "22x")
            0x03 -> Opcode("move/16", "32x")
            0x04 -> Opcode("move-wide", "12x")
            0x05 -> Opcode("move-wide/from16", "22x")
            0x06 -> Opcode("move-wide/16", "32x")
            0x07 -> Opcode("move-object", "12x")
            0x08 -> Opcode("move-object/from16", "22x")
            0x09 -> Opcode("move-object/16", "32x")
            0x0a -> Opcode("move-result", "11x")
            0x0b -> Opcode("move-result-wide", "11x")
            0x0c -> Opcode("move-result-object", "11x")
            0x0d -> Opcode("move-exception", "11x")
            0x0e -> Opcode("return-void", "10x")
            0x0f -> Opcode("return", "11x")
            0x10 -> Opcode("return-wide", "11x")
            0x11 -> Opcode("return-object", "11x")
            0x12 -> Opcode("const/4", "11n")
            0x13 -> Opcode("const/16", "21s")
            0x14 -> Opcode("const", "31i")
            0x15 -> Opcode("const/high16", "21h")
            0x16 -> Opcode("const-wide/16", "21s")
            0x17 -> Opcode("const-wide/32", "31i")
            0x18 -> Opcode("const-wide", "51l")
            0x19 -> Opcode("const-wide/high16", "21h")
            0x1a -> Opcode("const-string", "21c")
            0x1b -> Opcode("const-string/jumbo", "31c")
            0x1c -> Opcode("const-class", "21c")
            0x1d -> Opcode("monitor-enter", "11x")
            0x1e -> Opcode("monitor-exit", "11x")
            0x1f -> Opcode("check-cast", "21c")
            0x20 -> Opcode("instance-of", "22c")
            0x21 -> Opcode("array-length", "12x")
            0x22 -> Opcode("new-instance", "21c")
            0x23 -> Opcode("new-array", "22c")
            0x24 -> Opcode("filled-new-array", "35c")
            0x25 -> Opcode("filled-new-array/range", "3rc")
            0x26 -> Opcode("fill-array-data", "31t")
            0x27 -> Opcode("throw", "11x")
            0x28 -> Opcode("goto", "10t")
            0x29 -> Opcode("goto/16", "20t")
            0x2a -> Opcode("goto/32", "30t")
            0x2b -> Opcode("packed-switch", "31t")
            0x2c -> Opcode("sparse-switch", "31t")
            0x2d -> Opcode("cmpl-float", "23x")
            0x2e -> Opcode("cmpg-float", "23x")
            0x2f -> Opcode("cmpl-double", "23x")
            0x30 -> Opcode("cmpg-double", "23x")
            0x31 -> Opcode("cmp-long", "23x")
            in 0x32..0x37 -> Opcode(
                arrayOf("if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le")[index - 0x32], "22t"
            )
            in 0x38..0x3d -> Opcode(
                arrayOf("if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez"
                )[index - 0x38], "21t"
            )
            in 0x3e..0x43 -> Opcode("unused", "10x")
            in 0x44..0x51 -> Opcode(
                arrayOf(
                    "aget", "aget-wide", "aget-object", "aget-boolean", "aget-byte", "aget-char",
                    "aget-short", "aput", "aput-wide", "aput-object", "aput-boolean", "aput-byte",
                    "aput-char", "aput-short"
                )[index - 0x44], "23x"
            )
            in 0x52..0x5f -> Opcode(
                arrayOf(
                    "iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char",
                    "iget-short", "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte",
                    "iput-char", "iput-short"
                )[index - 0x52], "22c"
            )
            in 0x60..0x6d -> Opcode(
                arrayOf(
                    "sget", "sget-wide", "sget-object", "sget-boolean", "sget-byte", "sget-char",
                    "sget-short", "sput", "sput-wide", "sput-object", "sput-boolean", "sput-byte",
                    "sput-char", "sput-short"
                )[index - 0x60], "21c"
            )
            in 0x6e..0x72 -> Opcode(
                arrayOf("invoke-virtual", "invoke-super", "invoke-direct", "invoke-static",
                    "invoke-interface")[index - 0x6e], "35c"
            )
            0x73 -> Opcode("unused", "35c")
            in 0x74..0x78 -> Opcode(
                arrayOf("invoke-virtual/range", "invoke-super/range", "invoke-direct/range",
                    "invoke-static/range", "invoke-interface/range")[index - 0x74], "3rc"
            )
            0x79 -> Opcode("unused", "3rc")
            in 0x7a..0x7f -> Opcode("unused", "10x")
            in 0x80..0x8f -> Opcode("unused", "22c")
            in 0x90..0x9a -> Opcode(
                arrayOf("add-int", "sub-int", "mul-int", "div-int", "rem-int", "and-int",
                    "or-int", "xor-int", "shl-int", "shr-int", "ushr-int")[index - 0x90], "23x"
            )
            in 0x9b..0xa5 -> Opcode(
                arrayOf("add-long", "sub-long", "mul-long", "div-long", "rem-long", "and-long",
                    "or-long", "xor-long", "shl-long", "shr-long", "ushr-long")[index - 0x9b], "23x"
            )
            in 0xa6..0xaa -> Opcode(
                arrayOf("add-float", "sub-float", "mul-float", "div-float",
                    "rem-float")[index - 0xa6], "23x"
            )
            in 0xab..0xaf -> Opcode(
                arrayOf("add-double", "sub-double", "mul-double", "div-double",
                    "rem-double")[index - 0xab], "23x"
            )
            in 0xb0..0xba -> Opcode(
                arrayOf("add-int/2addr", "sub-int/2addr", "mul-int/2addr", "div-int/2addr",
                    "rem-int/2addr", "and-int/2addr", "or-int/2addr", "xor-int/2addr",
                    "shl-int/2addr", "shr-int/2addr", "ushr-int/2addr")[index - 0xb0], "12x"
            )
            in 0xbb..0xc5 -> Opcode(
                arrayOf("add-long/2addr", "sub-long/2addr", "mul-long/2addr", "div-long/2addr",
                    "rem-long/2addr", "and-long/2addr", "or-long/2addr", "xor-long/2addr",
                    "shl-long/2addr", "shr-long/2addr", "ushr-long/2addr")[index - 0xbb], "12x"
            )
            in 0xc6..0xca -> Opcode(
                arrayOf("add-float/2addr", "sub-float/2addr", "mul-float/2addr",
                    "div-float/2addr", "rem-float/2addr")[index - 0xc6], "12x"
            )
            in 0xcb..0xcf -> Opcode(
                arrayOf("add-double/2addr", "sub-double/2addr", "mul-double/2addr",
                    "div-double/2addr", "rem-double/2addr")[index - 0xcb], "12x"
            )
            in 0xd0..0xd7 -> Opcode(
                arrayOf("add-int/lit16", "rsub-int", "mul-int/lit16", "div-int/lit16",
                    "rem-int/lit16", "and-int/lit16", "or-int/lit16", "xor-int/lit16"
                )[index - 0xd0], "22s"
            )
            in 0xd8..0xe2 -> Opcode(
                arrayOf("add-int/lit8", "rsub-int/lit8", "mul-int/lit8", "div-int/lit8",
                    "rem-int/lit8", "and-int/lit8", "or-int/lit8", "xor-int/lit8",
                    "shl-int/lit8", "shr-int/lit8", "ushr-int/lit8")[index - 0xd8], "22b"
            )
            0xe3 -> Opcode("execute-inline", "22x")
            in 0xe4..0xf9 -> Opcode("unused", "10x")
            0xfa -> Opcode("invoke-polymorphic", "45cc")
            0xfb -> Opcode("invoke-polymorphic/range", "4rcc")
            0xfc -> Opcode("invoke-custom", "35c")
            0xfd -> Opcode("invoke-custom/range", "3rc")
            0xfe -> Opcode("const-method-handle", "21c")
            0xff -> Opcode("const-method-type", "21c")
            else -> Opcode("unknown", "10x")
        }
    }

    fun disassemble(method: DexMethodDef): String {
        val code = method.code ?: return ""
        val builder = StringBuilder()
        val accessFlags = DexAccessFlags.forMethod(method.accessFlags)
        builder.append(".method ").append(accessFlags).append(' ')
        if (method.accessFlags and 0x10000 != 0) {
            builder.append(method.method.className).append(".")
        }
        builder.append(method.method.name).append(method.method.shortDescriptor).append('\n')
        builder.append("    .registers ").append(code.registersSize).append('\n')
        if (code.insSize > 0) {
            builder.append("    .params (")
                .append(method.method.proto.parameters.joinToString(", "))
                .append(")\n")
        }
        var position = 0
        while (position < code.insns.size) {
            val (opcode, operands, size) = decode(code.insns, position)
            builder.append("    ").append(position.toHex())
                .append(": ").append(opcode)
            if (operands.isNotEmpty()) {
                builder.append(' ').append(operands)
            }
            builder.append('\n')
            position += size
        }
        builder.append(".end method")
        return builder.toString()
    }

    private fun Int.toHex(): String = "0x%04x".format(this)

    private fun Long.toHex(): String = "0x%016x".format(this)

    private data class Decoded(
        val opcode: String,
        val operands: String,
        val size: Int
    )

    private fun decode(insns: ShortArray, position: Int): Decoded {
        val unit = insns[position].toInt() and 0xffff
        val op = unit and 0xff
        val opcode = opcodes[op]
        val format = opcode.format
        if (opcode.name == "unused" || opcode.name == "unknown") {
            return Decoded("unused 0x" + op.toString(16), "", 1)
        }
        try {
            return when (format) {
                "10x" -> Decoded(opcode.name, "", 1)
                "12x" -> {
                    val a = (unit shr 8) and 0xf
                    val b = (unit shr 12) and 0xf
                    Decoded(opcode.name, "v$a, v$b", 1)
                }
                "11n" -> {
                    val a = (unit shr 8) and 0xf
                    val b = signExtend((unit shr 12) and 0xf, 4)
                    Decoded(opcode.name, "v$a, ${b.toHex()}", 1)
                }
                "11x" -> Decoded(opcode.name, "v${(unit shr 8) and 0xff}", 1)
                "10t" -> {
                    val target = position + signExtend((unit shr 8) and 0xff, 8)
                    Decoded(opcode.name, ":${target.toHex()}", 1)
                }
                "20t" -> Decoded(opcode.name, ":${(position + signExtend16(insns, position + 1)).toHex()}", 2)
                "30t" -> Decoded(
                    opcode.name, ":${(position + readInt32(insns, position + 1)).toHex()}", 3
                )
                "22x" -> Decoded(
                    opcode.name,
                    "v${(unit shr 8) and 0xff}, v${insns[position + 1].toInt() and 0xffff}", 2
                )
                "32x" -> Decoded(
                    opcode.name,
                    "v${insns[position + 1].toInt() and 0xffff}, " +
                        "v${insns[position + 2].toInt() and 0xffff}", 3
                )
                "21t" -> {
                    val target = position + signExtend16(insns, position + 1)
                    Decoded(opcode.name, "v${(unit shr 8) and 0xff}, :${target.toHex()}", 2)
                }
                "21s" -> Decoded(
                    opcode.name,
                    "v${(unit shr 8) and 0xff}, ${signExtend16(insns, position + 1).toHex()}", 2
                )
                "21h" -> {
                    // const/high16 (0x15): 16-bit literal shifted left by 16 (Int).
                    // const-wide/high16 (0x19): 16-bit literal shifted left by 48 — must be
                    // computed in Long, as Int.shl(48) wraps to shl(16).
                    val raw = insns[position + 1].toInt() and 0xffff
                    val text = if (op == 0x15) {
                        (raw shl 16).toHex()
                    } else {
                        (raw.toLong() shl 48).toHex()
                    }
                    Decoded(opcode.name, "v${(unit shr 8) and 0xff}, $text", 2)
                }
                "21c" -> Decoded(
                    opcode.name,
                    "v${(unit shr 8) and 0xff}, ${renderReference(op, insns[position + 1].toInt() and 0xffff)}",
                    2
                )
                "23x" -> Decoded(
                    opcode.name,
                    "v${(unit shr 8) and 0xff}, v${insns[position + 1].toInt() and 0xff}, " +
                        "v${(insns[position + 1].toInt() shr 8) and 0xff}", 2
                )
                "22b" -> Decoded(
                    opcode.name,
                    // Format: vAA, vBB (low byte), #+CC (high byte, signed literal).
                    "v${(unit shr 8) and 0xff}, v${insns[position + 1].toInt() and 0xff}, " +
                        "${signExtend((insns[position + 1].toInt() shr 8) and 0xff, 8).toHex()}", 2
                )
                "22t" -> {
                    val target = position + signExtend16(insns, position + 1)
                    Decoded(
                        opcode.name,
                        "v${(unit shr 8) and 0xf}, v${(unit shr 12) and 0xf}, :${target.toHex()}", 2
                    )
                }
                "22s" -> Decoded(
                    opcode.name,
                    "v${(unit shr 8) and 0xf}, v${(unit shr 12) and 0xf}, " +
                        "${signExtend16(insns, position + 1).toHex()}", 2
                )
                "22c" -> Decoded(
                    opcode.name,
                    "v${(unit shr 8) and 0xf}, v${(unit shr 12) and 0xf}, " +
                        "${renderReference(op, insns[position + 1].toInt() and 0xffff)}", 2
                )
                "31i" -> Decoded(
                    opcode.name, "v${(unit shr 8) and 0xff}, ${readInt32(insns, position + 1).toHex()}", 3
                )
                "31t" -> {
                    val target = position + readInt32(insns, position + 1)
                    Decoded(opcode.name, "v${(unit shr 8) and 0xff}, :${target.toHex()}", 3)
                }
                "31c" -> {
                    val index = readInt32(insns, position + 1)
                    Decoded(
                        opcode.name,
                        "v${(unit shr 8) and 0xff}, ${renderReference(op, index)}", 3
                    )
                }
                "51l" -> {
                    val literal = readInt64(insns, position + 1)
                    Decoded(opcode.name, "v${(unit shr 8) and 0xff}, ${literal.toHex()}", 3)
                }
                "35c" -> {
                    val argCount = (unit shr 12) and 0xf
                    val index = insns[position + 1].toInt() and 0xffff
                    val registers = readInvokeRegisters(insns, position, argCount)
                    Decoded(opcode.name, "$registers, ${renderReference(op, index)}", 3)
                }
                "3rc" -> {
                    val argCount = (unit shr 12) and 0xf
                    val firstRegister = insns[position + 2].toInt() and 0xffff
                    val index = insns[position + 1].toInt() and 0xffff
                    val registers = if (argCount == 0) {
                        "{}"
                    } else {
                        "{v$firstRegister .. v${firstRegister + argCount - 1}}"
                    }
                    Decoded(opcode.name, "$registers, ${renderReference(op, index)}", 3)
                }
                "45cc" -> {
                    val argCount = (unit shr 12) and 0xf
                    val index = insns[position + 1].toInt() and 0xffff
                    val registers = readInvokeRegisters(insns, position, argCount)
                    val protoIndex = insns[position + 3].toInt() and 0xffff
                    Decoded(
                        opcode.name,
                        "$registers, ${renderReference(op, index)}, proto@$protoIndex", 4
                    )
                }
                "4rcc" -> {
                    val argCount = (unit shr 12) and 0xf
                    val firstRegister = insns[position + 2].toInt() and 0xffff
                    val index = insns[position + 1].toInt() and 0xffff
                    val protoIndex = insns[position + 3].toInt() and 0xffff
                    val registers = if (argCount == 0) {
                        "{}"
                    } else {
                        "{v$firstRegister .. v${firstRegister + argCount - 1}}"
                    }
                    Decoded(
                        opcode.name,
                        "$registers, ${renderReference(op, index)}, proto@$protoIndex", 4
                    )
                }
                else -> Decoded(opcode.name, "", 1)
            }
        } catch (e: Exception) {
            return Decoded("unused 0x" + op.toString(16), "", 1)
        }
    }

    private fun readInvokeRegisters(insns: ShortArray, position: Int, argCount: Int): String {
        // 35c: word0 = A(15-12) | G(11-8) | op(7-0); word1 = index; word2 = C(3-0) D(7-4)
        // E(11-8) F(15-12); G is the 5th register when argCount == 5.
        val word0 = insns[position].toInt() and 0xffff
        val word2 = insns[position + 2].toInt() and 0xffff
        val registers = if (argCount <= 4) {
            (0 until argCount).map { index ->
                (word2 shr (index * 4)) and 0xf
            }
        } else {
            val g = (word0 shr 8) and 0xf
            listOf(
                word2 and 0xf, (word2 shr 4) and 0xf, (word2 shr 8) and 0xf,
                (word2 shr 12) and 0xf, g
            ).take(argCount)
        }
        return "{" + registers.joinToString(", ") { "v$it" } + "}"
    }

    private fun renderReference(opcode: Int, index: Int): String {
        return when (opcode) {
            in 0x1a..0x1b -> if (index in dex.strings.indices) {
                quoteString(dex.strings[index])
            } else {
                "string@$index"
            }
            in 0x1c..0x1c, in 0x1f..0x20, in 0x22..0x25 -> if (index < dex.types.size) {
                dex.types[index]
            } else {
                "type@$index"
            }
            0xfe -> "method-handle@$index"
            0xff -> "proto@$index"
            in 0x52..0x6d -> if (index in dex.fields.indices) {
                val field = dex.fields[index]
                "${field.className}->${field.name}:${field.type}"
            } else {
                "field@$index"
            }
            in 0x6e..0x73, in 0x74..0x79, in 0xfa..0xfa, in 0xfc..0xfc -> if (index in dex.methods.indices) {
                dex.methods[index].toString()
            } else {
                "method@$index"
            }
            in 0xfb..0xfb, in 0xfd..0xfd -> if (index in dex.methods.indices) {
                dex.methods[index].toString()
            } else {
                "method@$index"
            }
            else -> "index@$index"
        }
    }

    private fun quoteString(string: String): String {
        val builder = StringBuilder(string.length + 2)
        builder.append('"')
        string.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char.code < 0x20 || char.code == 0x7f) {
                    builder.append("\\u").append("%04x".format(char.code))
                } else {
                    builder.append(char)
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private fun signExtend16(insns: ShortArray, position: Int): Int =
        signExtend(insns[position].toInt() and 0xffff, 16)

    private fun readInt32(insns: ShortArray, position: Int): Int =
        (insns[position].toInt() and 0xffff) or (insns[position + 1].toInt() shl 16)

    private fun readInt64(insns: ShortArray, position: Int): Long =
        (insns[position].toInt().toLong() and 0xffff) or
            (insns[position + 1].toInt().toLong() shl 16) or
            (insns[position + 2].toInt().toLong() shl 32) or
            (insns[position + 3].toInt().toLong() shl 48)

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }
}
