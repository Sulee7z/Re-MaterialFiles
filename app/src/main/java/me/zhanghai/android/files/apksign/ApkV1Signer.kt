/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

/**
 * Signs an APK with the JAR (v1) signature scheme.
 *
 * The ZIP is rewritten manually so that entries keep their original compression method and
 * STORED entries (notably resources.arsc) stay 4-byte aligned, which is required for
 * installation on Android 11+ when targeting SDK 30+.
 */
object ApkV1Signer {

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val (manifest, sections) = buildManifest(input)
        val signatureFile = buildSignatureFile(manifest, sections)
        val pkcs7 = buildPkcs7(signatureFile, privateKey, certificate)
        output.outputStream().use { fileStream ->
            writeZip(input, fileStream, manifest, signatureFile, pkcs7)
        }
    }

    private class OutEntry(
        val name: String,
        val data: ByteArray,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val csize: Int,
        val usize: Int,
        val localOffset: Long
    )

    private fun writeZip(
        input: File,
        output: OutputStream,
        manifest: ByteArray,
        signatureFile: ByteArray,
        pkcs7: ByteArray
    ) {
        val entries = ArrayList<OutEntry>()
        var offset = 0L
        fun addEntry(name: String, data: ByteArray, method: Int, timeMillis: Long) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val stored = if (method == ZipEntry.DEFLATED) deflate(data) else data
            // Align STORED entries (e.g. resources.arsc) on a 4-byte boundary.
            val extraLen = if (method == ZipEntry.STORED) {
                ((4 - ((offset + 30 + nameBytes.size) % 4)) % 4).toInt()
            } else {
                0
            }
            entries.add(
                OutEntry(
                    name, stored, method, dosTime(timeMillis), dosDate(timeMillis),
                    CRC32().apply { update(data) }.value, stored.size, data.size, offset
                )
            )
            offset += 30 + nameBytes.size + extraLen + stored.size
        }
        ZipFile(input).use { zipFile ->
            val iterator = zipFile.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) {
                    continue
                }
                val data = zipFile.getInputStream(entry).use { it.readBytes() }
                addEntry(entry.name, data, entry.method, entry.time)
            }
        }
        addEntry("META-INF/MANIFEST.MF", manifest, ZipEntry.STORED, System.currentTimeMillis())
        addEntry("META-INF/CERT.SF", signatureFile, ZipEntry.STORED, System.currentTimeMillis())
        addEntry("META-INF/CERT.RSA", pkcs7, ZipEntry.STORED, System.currentTimeMillis())

        // Write local file entries.
        val centralDirectory = ArrayList<ByteArray>()
        val centralDirectoryOffset = offset
        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val extraLen = if (entry.method == ZipEntry.STORED) {
                ((4 - ((offset + 30 + nameBytes.size) % 4)) % 4).toInt()
            } else {
                0
            }
            // Local file header.
            val localHeader = ByteArray(30 + nameBytes.size + extraLen)
            putU32(localHeader, 0, 0x04034b50L)
            putU16(localHeader, 4, 20) // version needed to extract
            putU16(localHeader, 6, 0x0800) // UTF-8 names
            putU16(localHeader, 8, entry.method)
            putU16(localHeader, 10, entry.time)
            putU16(localHeader, 12, entry.date)
            putU32(localHeader, 14, entry.crc)
            putU32(localHeader, 18, entry.csize.toLong())
            putU32(localHeader, 22, entry.usize.toLong())
            putU16(localHeader, 26, nameBytes.size)
            putU16(localHeader, 28, extraLen)
            System.arraycopy(nameBytes, 0, localHeader, 30, nameBytes.size)
            output.write(localHeader)
            output.write(entry.data)
            offset += 30 + nameBytes.size + extraLen + entry.data.size

            // Central directory entry.
            val central = ByteArray(46 + nameBytes.size)
            putU32(central, 0, 0x02014b50L)
            putU16(central, 4, 20) // version made by
            putU16(central, 6, 20) // version needed to extract
            putU16(central, 8, 0x0800) // UTF-8 names
            putU16(central, 10, entry.method)
            putU16(central, 12, entry.time)
            putU16(central, 14, entry.date)
            putU32(central, 16, entry.crc)
            putU32(central, 20, entry.csize.toLong())
            putU32(central, 24, entry.usize.toLong())
            putU16(central, 28, nameBytes.size)
            putU16(central, 30, 0) // extra length
            putU16(central, 32, 0) // comment length
            putU16(central, 34, 0) // disk number
            putU16(central, 36, 0) // internal attributes
            putU32(central, 38, 0) // external attributes
            putU32(central, 42, entry.localOffset)
            System.arraycopy(nameBytes, 0, central, 46, nameBytes.size)
            centralDirectory.add(central)
        }

        // Write central directory.
        val centralDirectorySize = centralDirectory.fold(0) { acc, it -> acc + it.size }
        for (central in centralDirectory) {
            output.write(central)
        }
        // Write EOCD.
        val eocd = ByteArray(22)
        putU32(eocd, 0, 0x06054b50L)
        putU16(eocd, 4, 0) // disk number
        putU16(eocd, 6, 0) // disk with central directory
        putU16(eocd, 8, entries.size)
        putU16(eocd, 10, entries.size)
        putU32(eocd, 12, centralDirectorySize.toLong())
        putU32(eocd, 16, centralDirectoryOffset.toLong())
        putU16(eocd, 20, 0) // comment length
        output.write(eocd)
    }

    private fun deflate(data: ByteArray): ByteArray {
        // ZIP entries store raw deflate streams (no zlib header).
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val output = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun dosTime(timeMillis: Long): Int {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
        }
        return (calendar.get(Calendar.HOUR_OF_DAY) shl 11) or
            (calendar.get(Calendar.MINUTE) shl 5) or
            (calendar.get(Calendar.SECOND) shr 1)
    }

    private fun dosDate(timeMillis: Long): Int {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
        }
        return ((calendar.get(Calendar.YEAR) - 1980) shl 9) or
            ((calendar.get(Calendar.MONTH) + 1) shl 5) or
            calendar.get(Calendar.DAY_OF_MONTH)
    }

    private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun putU32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun sha1(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(bytes))

    private fun buildManifest(input: File): Pair<ByteArray, List<ByteArray>> {
        val main = StringBuilder()
        main.append("Manifest-Version: 1.0\r\n")
        main.append("Created-By: MaterialFiles.Sora-Editor\r\n\r\n")
        val sectionBlocks = ArrayList<ByteArray>()
        val sections = StringBuilder()
        ZipFile(input).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) {
                    continue
                }
                val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                val block =
                    "Name: ${entry.name}\r\nSHA1-Digest: ${sha1(bytes)}\r\n".toByteArray(
                        Charsets.UTF_8
                    )
                sectionBlocks.add(block)
                sections.append(String(block, Charsets.UTF_8)).append("\r\n")
            }
        }
        return (main.toString() + sections).toByteArray(Charsets.UTF_8) to sectionBlocks
    }

    private fun buildSignatureFile(
        manifest: ByteArray, sections: List<ByteArray>
    ): ByteArray {
        val main = StringBuilder()
        main.append("Signature-Version: 1.0\r\n")
        main.append("SHA1-Digest-Manifest: ").append(sha1(manifest)).append("\r\n\r\n")
        val builder = StringBuilder()
        sections.forEach { section ->
            val text = String(section, Charsets.UTF_8)
            val name = text.substringAfter("Name: ").substringBefore("\r\n")
            builder.append("Name: ").append(name).append("\r\n")
            builder.append("SHA1-Digest: ").append(sha1(section)).append("\r\n\r\n")
        }
        return (main.toString() + builder).toByteArray(Charsets.UTF_8)
    }

    private fun buildPkcs7(
        signatureFile: ByteArray, privateKey: PrivateKey, certificate: X509Certificate
    ): ByteArray {
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(signer, certificate)
        )
        val certificateStore = org.bouncycastle.cert.jcajce.JcaCertStore(
            listOf(certificate)
        )
        generator.addCertificates(certificateStore)
        val signedData = generator.generate(CMSProcessableByteArray(signatureFile), false)
        return signedData.toASN1Structure().encoded
    }
}




