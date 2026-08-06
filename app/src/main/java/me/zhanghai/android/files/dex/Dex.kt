/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

class DexParseException(message: String) : Exception(message)

data class DexCode(
    val registersSize: Int,
    val insSize: Int,
    val outsSize: Int,
    val insns: ShortArray
) {
    override fun equals(other: Any?): Boolean =
        other is DexCode && registersSize == other.registersSize && insSize == other.insSize
            && outsSize == other.outsSize && insns.contentEquals(other.insns)

    override fun hashCode(): Int = insns.contentHashCode()
}

data class DexProto(
    val shorty: String,
    val returnType: String,
    val parameters: List<String>
)

data class DexFieldRef(
    val className: String,
    val name: String,
    val type: String
)

data class DexMethodRef(
    val className: String,
    val name: String,
    val proto: DexProto
) {
    val shortDescriptor: String
        get() = "(${proto.parameters.joinToString("")})${proto.returnType}"

    override fun toString(): String = "$className->$name$shortDescriptor"
}

data class DexFieldDef(
    val field: DexFieldRef,
    val accessFlags: Int
)

data class DexMethodDef(
    val method: DexMethodRef,
    val accessFlags: Int,
    val code: DexCode?
)

/**
 * A reference from one class to a type, field or method, recorded while parsing the DEX.
 */
data class DexReference(
    val ownerClass: String,
    val kind: String,
    val target: String
)

data class DexClass(
    val className: String,
    val accessFlags: Int,
    val superclassName: String?,
    val interfaces: List<String>,
    val sourceFile: String?,
    val fields: List<DexFieldDef>,
    val methods: List<DexMethodDef>,
    val references: List<DexReference> = emptyList()
)

class DexFile(
    val version: String,
    val strings: List<String>,
    val types: List<String>,
    val fields: List<DexFieldRef>,
    val methods: List<DexMethodRef>,
    val classes: List<DexClass>
) {
    /**
     * Finds where the given type (class descriptor like "Landroid/app/Activity;") is
     * referenced. Returns (owner class, reference kind) pairs.
     */
    fun findClassReferences(typeName: String): List<Pair<String, String>> =
        classes.flatMap { cls ->
            cls.references.filter { it.target == typeName }
                .map { it.ownerClass to it.kind }
        }

    /**
     * Finds where the given method (key like "Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V")
     * is referenced.
     */
    fun findMethodReferences(methodKey: String): List<Pair<String, String>> =
        classes.flatMap { cls ->
            cls.references.filter { it.kind.startsWith("invoke") && it.target == methodKey }
                .map { it.ownerClass to it.kind }
        }

    /**
     * Finds where the given field (key like "Landroid/app/Activity;->mField:I") is referenced.
     */
    fun findFieldReferences(fieldKey: String): List<Pair<String, String>> =
        classes.flatMap { cls ->
            cls.references.filter { it.kind.startsWith("field") && it.target == fieldKey }
                .map { it.ownerClass to it.kind }
        }
}

object DexAccessFlags {
    private const val ACC_PUBLIC = 0x1
    private const val ACC_PRIVATE = 0x2
    private const val ACC_PROTECTED = 0x4
    private const val ACC_STATIC = 0x8
    private const val ACC_FINAL = 0x10
    private const val ACC_SYNCHRONIZED = 0x20
    private const val ACC_VOLATILE = 0x40
    private const val ACC_BRIDGE = 0x40
    private const val ACC_TRANSIENT = 0x80
    private const val ACC_VARARGS = 0x80
    private const val ACC_NATIVE = 0x100
    private const val ACC_INTERFACE = 0x200
    private const val ACC_ABSTRACT = 0x400
    private const val ACC_STRICT = 0x800
    private const val ACC_SYNTHETIC = 0x1000
    private const val ACC_ANNOTATION = 0x2000
    private const val ACC_ENUM = 0x4000
    private const val ACC_CONSTRUCTOR = 0x10000
    private const val ACC_DECLARED_SYNCHRONIZED = 0x20000

    fun forClass(accessFlags: Int): String {
        val modifiers = mutableListOf<String>()
        if (accessFlags and ACC_PUBLIC != 0) modifiers += "public"
        if (accessFlags and ACC_PRIVATE != 0) modifiers += "private"
        if (accessFlags and ACC_PROTECTED != 0) modifiers += "protected"
        if (accessFlags and ACC_STATIC != 0) modifiers += "static"
        if (accessFlags and ACC_FINAL != 0) modifiers += "final"
        if (accessFlags and ACC_INTERFACE != 0) modifiers += "interface"
        if (accessFlags and ACC_ABSTRACT != 0) modifiers += "abstract"
        if (accessFlags and ACC_SYNTHETIC != 0) modifiers += "synthetic"
        if (accessFlags and ACC_ANNOTATION != 0) modifiers += "annotation"
        if (accessFlags and ACC_ENUM != 0) modifiers += "enum"
        return modifiers.joinToString(" ")
    }

    fun forField(accessFlags: Int): String {
        val modifiers = mutableListOf<String>()
        if (accessFlags and ACC_PUBLIC != 0) modifiers += "public"
        if (accessFlags and ACC_PRIVATE != 0) modifiers += "private"
        if (accessFlags and ACC_PROTECTED != 0) modifiers += "protected"
        if (accessFlags and ACC_STATIC != 0) modifiers += "static"
        if (accessFlags and ACC_FINAL != 0) modifiers += "final"
        if (accessFlags and ACC_VOLATILE != 0) modifiers += "volatile"
        if (accessFlags and ACC_TRANSIENT != 0) modifiers += "transient"
        if (accessFlags and ACC_SYNTHETIC != 0) modifiers += "synthetic"
        if (accessFlags and ACC_ENUM != 0) modifiers += "enum"
        return modifiers.joinToString(" ")
    }

    fun forMethod(accessFlags: Int): String {
        val modifiers = mutableListOf<String>()
        if (accessFlags and ACC_PUBLIC != 0) modifiers += "public"
        if (accessFlags and ACC_PRIVATE != 0) modifiers += "private"
        if (accessFlags and ACC_PROTECTED != 0) modifiers += "protected"
        if (accessFlags and ACC_STATIC != 0) modifiers += "static"
        if (accessFlags and ACC_FINAL != 0) modifiers += "final"
        if (accessFlags and ACC_SYNCHRONIZED != 0) modifiers += "synchronized"
        if (accessFlags and ACC_BRIDGE != 0) modifiers += "bridge"
        if (accessFlags and ACC_VARARGS != 0) modifiers += "varargs"
        if (accessFlags and ACC_NATIVE != 0) modifiers += "native"
        if (accessFlags and ACC_ABSTRACT != 0) modifiers += "abstract"
        if (accessFlags and ACC_STRICT != 0) modifiers += "strictfp"
        if (accessFlags and ACC_SYNTHETIC != 0) modifiers += "synthetic"
        if (accessFlags and ACC_CONSTRUCTOR != 0) modifiers += "constructor"
        if (accessFlags and ACC_DECLARED_SYNCHRONIZED != 0) modifiers += "synchronized"
        return modifiers.joinToString(" ")
    }
}
