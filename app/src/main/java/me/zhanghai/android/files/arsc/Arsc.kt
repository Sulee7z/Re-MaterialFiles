/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.arsc

class ArscParseException(message: String) : Exception(message)

/** An in-memory model of resources.arsc; the writer serializes it back to binary. */
class ArscFile(
    val globalPool: ArscStringPool,
    val resourceMap: IntArray,
    val packages: MutableList<ArscPackage>
)

class ArscStringPool(val strings: MutableList<String>)

/** A Res_value: an 8-byte (size, res0, dataType, data) payload. */
class ArscValue(val dataType: Int, var data: Long) {
    val isString: Boolean get() = dataType == TYPE_STRING

    companion object {
        const val TYPE_NULL = 0x00
        const val TYPE_REFERENCE = 0x01
        const val TYPE_ATTRIBUTE = 0x02
        const val TYPE_STRING = 0x03
        const val TYPE_FLOAT = 0x04
        const val TYPE_DIMENSION = 0x05
        const val TYPE_FRACTION = 0x06
        const val TYPE_INT_DEC = 0x1c
        const val TYPE_INT_HEX = 0x1d
        const val TYPE_INT_BOOLEAN = 0x1e
        const val TYPE_INT_COLOR_ARGB8 = 0x1f
        const val TYPE_INT_COLOR_RGB8 = 0x20
        const val TYPE_INT_COLOR_ARGB4 = 0x21
        const val TYPE_INT_COLOR_RGB4 = 0x22

        fun typeName(dataType: Int): String = when (dataType) {
            TYPE_NULL -> "null"
            TYPE_REFERENCE -> "reference"
            TYPE_ATTRIBUTE -> "attribute"
            TYPE_STRING -> "string"
            TYPE_FLOAT -> "float"
            TYPE_DIMENSION -> "dimension"
            TYPE_FRACTION -> "fraction"
            TYPE_INT_DEC -> "int"
            TYPE_INT_HEX -> "hex"
            TYPE_INT_BOOLEAN -> "boolean"
            TYPE_INT_COLOR_ARGB8, TYPE_INT_COLOR_RGB8,
            TYPE_INT_COLOR_ARGB4, TYPE_INT_COLOR_RGB4 -> "color"
            else -> "0x" + dataType.toString(16)
        }
    }
}

/** One item of a complex entry: a name (usually an attribute resource id) + value. */
class ArscMapItem(val name: Long, val value: ArscValue)

/**
 * A resource entry. For a simple entry [value] is set; for a complex entry [parent] and
 * [mapItems] hold the ResTable_map payload. [flags] keeps FLAG_PUBLIC/FLAG_WEAK.
 */
class ArscEntry(
    val keyIndex: Int,
    var flags: Int,
    val value: ArscValue?,
    val parent: Long?,
    val mapItems: List<ArscMapItem>?
) {
    val isComplex: Boolean get() = flags and FLAG_COMPLEX != 0

    companion object {
        const val FLAG_COMPLEX = 0x0001
        const val FLAG_PUBLIC = 0x0002
        const val FLAG_WEAK = 0x0004
    }
}

/** A ResTable_type chunk: all entries of one resource type for one configuration. */
class ArscType(
    val id: Int,
    val flags: Int,
    /** The raw ResTable_config bytes (28/52/56); kept opaque so editing is lossless. */
    val config: ByteArray,
    /** Parallel to the type's entries; a null element means NO_ENTRY. */
    val entries: MutableList<ArscEntry?>
) {
    companion object {
        const val FLAG_SPARSE = 0x01
    }
}

/** A ResTable_typeSpec chunk: per-entry flags. */
class ArscTypeSpec(val id: Int, val flags: IntArray)

/** A ResTable_package chunk: type string pool, key string pool, typeSpecs and types. */
class ArscPackage(
    val id: Int,
    val name: String,
    val typeStrings: ArscStringPool,
    val keyStrings: ArscStringPool,
    val typeSpecs: MutableList<ArscTypeSpec>,
    val types: MutableList<ArscType>
) {
    /** Type name for [typeId] (1-based), from the type string pool. */
    fun typeName(typeId: Int): String {
        val index = typeId - 1
        return if (index in typeStrings.strings.indices) typeStrings.strings[index] else "type$typeId"
    }

    /** Key name for an entry key index. */
    fun keyName(keyIndex: Int): String =
        if (keyIndex in keyStrings.strings.indices) keyStrings.strings[keyIndex] else "key$keyIndex"
}
