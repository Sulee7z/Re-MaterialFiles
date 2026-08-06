/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

/**
 * Signs an APK with the JAR (v1) signature scheme.
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
            ZipOutputStream(fileStream).use { zip ->
                ZipFile(input).use { zipFile ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || entry.name.startsWith("META-INF/")) {
                            continue
                        }
                        zip.putNextEntry(ZipEntry(entry.name))
                        zipFile.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                addStoredEntry(zip, "META-INF/MANIFEST.MF", manifest)
                addStoredEntry(zip, "META-INF/CERT.SF", signatureFile)
                addStoredEntry(zip, "META-INF/CERT.RSA", pkcs7)
            }
        }
    }

    private fun addStoredEntry(zip: ZipOutputStream, name: String, content: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = content.size.toLong()
        entry.compressedSize = content.size.toLong()
        entry.crc = CRC32().apply { update(content) }.value
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
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
                    "Name: ${entry.name}\r\nSHA-1-Digest: ${sha1(bytes)}\r\n".toByteArray(
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
        main.append("SHA-1-Digest-Manifest: ").append(sha1(manifest)).append("\r\n\r\n")
        val builder = StringBuilder()
        sections.forEach { section ->
            val text = String(section, Charsets.UTF_8)
            val name = text.substringAfter("Name: ").substringBefore("\r\n")
            builder.append("Name: ").append(name).append("\r\n")
            builder.append("SHA-1-Digest: ").append(sha1(section)).append("\r\n\r\n")
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
