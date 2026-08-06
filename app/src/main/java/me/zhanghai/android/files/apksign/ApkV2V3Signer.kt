/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

/**
 * Signs an APK with the APK Signature Scheme v2 and v3 (see
 * https://source.android.com/docs/security/features/apksigning/v2).
 */
object ApkV2V3Signer {

    private const val V2_BLOCK_ID = 0x7109871a
    private const val V3_BLOCK_ID = -262969152 // 0xf05368c0
    private const val ALGORITHM_RSA_SHA256 = 0x0103 // RSA PKCS1v1.5 with SHA-256 (apksig 36 id)
    private const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID = V2_BLOCK_ID
    private const val APK_SIGNATURE_SCHEME_V3_BLOCK_ID = V3_BLOCK_ID
    private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private const val CHUNK_SIZE = 1024 * 1024
    private const val MIN_SDK = 26

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val apk = input.readBytes()
        val eocdOffset = findEocd(apk)
        if (eocdOffset < 0) {
            throw IllegalArgumentException("Not a valid ZIP file")
        }
        val cdOffset = readU32(apk, eocdOffset + 16)
        val blockStart = cdOffset.toLong()

        // Content digest: a single chunked SHA-256 over the contents, central directory and
        // EOCD (with the central directory offset pointing at the signing block). The APK
        // Signing Block itself is not part of the digest.
        val eocdDigestInput = ByteArray(apk.size - eocdOffset)
        System.arraycopy(apk, eocdOffset, eocdDigestInput, 0, eocdDigestInput.size)
        writeU32(eocdDigestInput, 16, blockStart.toInt())
        val contentDigest = computeChunkedDigest(
            apk.copyOfRange(0, cdOffset),
            apk.copyOfRange(cdOffset, eocdOffset),
            eocdDigestInput
        )

        // Build the signer blocks for v2 and v3.
        val digests = listOf(contentDigest)
        val v2SignerFinal = buildSigner(privateKey, certificate, v3 = false, digests = digests)
        val v3SignerFinal = buildSigner(privateKey, certificate, v3 = true, digests = digests)
        val signingBlock = buildSigningBlock(v2SignerFinal, v3SignerFinal)

        // Assemble the output APK: contents + signing block + central directory + EOCD,
        // with the EOCD central directory offset updated to point past the signing block.
        val newCdOffset = cdOffset + signingBlock.size
        val eocdBytes = apk.copyOfRange(eocdOffset, apk.size)
        writeU32(eocdBytes, 16, newCdOffset)
        output.outputStream().use { out ->
            out.write(apk, 0, cdOffset)
            out.write(signingBlock)
            out.write(apk, cdOffset, eocdOffset - cdOffset)
            out.write(eocdBytes)
        }
    }

    private fun buildSigner(
        privateKey: PrivateKey,
        certificate: X509Certificate,
        v3: Boolean,
        digests: List<ByteArray> = listOf(ByteArray(32))
    ): ByteArray {
        // CMS SignedData over the digest concatenation.
        val content = digests.fold(ByteArray(0)) { acc, digest -> acc + digest }
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(contentSigner, certificate)
        )
        generator.addCertificates(
            org.bouncycastle.cert.jcajce.JcaCertStore(listOf(certificate))
        )
        val signedData = generator.generate(CMSProcessableByteArray(content), false)
            .toASN1Structure().encoded

        // Signed data structure: length-prefixed digests (each a length-prefixed pair of
        // algorithm and digest), certificates, and additional attributes. There is one digest
        // entry per content digest algorithm, containing the concatenated section digests.
        val digestsSection = ByteBuffer.allocate(4 + 4 + 4 + content.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4 + 4 + content.size)
            .putInt(ALGORITHM_RSA_SHA256)
            .putInt(content.size)
            .put(content)
            .array()
        val certBytes = certificate.encoded
        val certificates = ByteBuffer.allocate(4 + certBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        certificates.putInt(certBytes.size)
        certificates.put(certBytes)
        val additionalAttributes = ByteArray(0)
        val empty = ByteArray(0)

        val signedDataParts = listOf(
            digestsSection,
            certificates.array(),
            additionalAttributes,
            empty
        )
        val signedDataEncoded = encodeSequenceOfLengthPrefixed(signedDataParts)

        // The v3 signed data: [len(digests)][digests][len(certs)][certs][minSdkVersion]
        // [maxSdkVersion][len(attrs)][attrs].
        val minMax = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MIN_SDK).putInt(0x7fffffff).array()
        val v3SignedData = encodeSequenceOfLengthPrefixed(
            listOf(digestsSection, certificates.array())
        ) + minMax + intToBytes(0)
        val signedDataToSign = if (v3) v3SignedData else signedDataEncoded

        // Signatures: RSA PKCS1v1.5 SHA-256 over the signed data.
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signedDataToSign)
        val signatureBytes = signature.sign()

        val signaturesSection = ByteBuffer.allocate(4 + 4 + 4 + signatureBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        signaturesSection.putInt(4 + 4 + signatureBytes.size)
        signaturesSection.putInt(ALGORITHM_RSA_SHA256)
        signaturesSection.putInt(signatureBytes.size)
        signaturesSection.put(signatureBytes)

        val publicKey = certificate.publicKey.encoded

        // The signer = length-prefixed sequence of its parts, the signers list =
        // length-prefixed sequence of signers.
        val signer = if (v3) {
            // v3 signer records minSdkVersion and maxSdkVersion again after the signed data:
            // [len(sd)][sd][minSdk][maxSdk][len(sigs)][sigs][len(pk)][pk].
            ByteBuffer.allocate(4 + v3SignedData.size + 8 + 4 + signaturesSection.array().size + 4 + publicKey.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(v3SignedData.size).put(v3SignedData)
                .put(minMax)
                .putInt(signaturesSection.array().size).put(signaturesSection.array())
                .putInt(publicKey.size).put(publicKey)
                .array()
        } else {
            encodeSequenceOfLengthPrefixed(
                listOf(signedDataEncoded, signaturesSection.array(), publicKey)
            )
        }
        return encodeSequenceOfLengthPrefixed(listOf(encodeSequenceOfLengthPrefixed(listOf(signer))))
    }

    private fun buildSigningBlock(v2Signer: ByteArray, v3Signer: ByteArray): ByteArray {
        // Entries: v2 and v3, each with its own signer block. The size field covers the
        // value plus the 4-byte ID (excluding the 8-byte size field itself).
        val v2EntrySize = v2Signer.size + 4
        val v3EntrySize = v3Signer.size + 4
        val entriesSize = 8 + v2EntrySize + 8 + v3EntrySize
        val entries = ByteBuffer.allocate(entriesSize).order(ByteOrder.LITTLE_ENDIAN)
        entries.putLong(v2EntrySize.toLong())
        entries.putInt(APK_SIGNATURE_SCHEME_V2_BLOCK_ID)
        entries.put(v2Signer)
        entries.putLong(v3EntrySize.toLong())
        entries.putInt(APK_SIGNATURE_SCHEME_V3_BLOCK_ID)
        entries.put(v3Signer)
        val entriesBytes = entries.array()
        // The size field covers the block excluding the first 8-byte size field, i.e. the
        // entries plus the trailing size field and magic.
        val blockSize = entriesBytes.size + 8 + APK_SIG_BLOCK_MAGIC.length
        return ByteBuffer.allocate(8 + blockSize).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(blockSize.toLong())
            .put(entriesBytes)
            .putLong(blockSize.toLong())
            .put(APK_SIG_BLOCK_MAGIC.toByteArray(Charsets.US_ASCII))
            .array()
    }

    private fun computeChunkedDigest(vararg segments: ByteArray): ByteArray {
        // Per-chunk digest: SHA-256 of 0xA5 + uint32(chunk length) + chunk contents.
        // Output digest: SHA-256 of 0x5A + uint32(chunk count) + concatenated chunk digests.
        // Each segment is chunked independently (1 MB chunks within the segment).
        val chunkDigests = java.io.ByteArrayOutputStream()
        var chunkCount = 0
        for (bytes in segments) {
            var position = 0
            var remaining = bytes.size
            while (remaining > 0) {
                val chunkLength = minOf(remaining, CHUNK_SIZE.toInt())
                val chunkDigest = MessageDigest.getInstance("SHA-256")
                chunkDigest.update(0xa5.toByte())
                chunkDigest.update(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkLength).array()
                )
                chunkDigest.update(bytes, position, chunkLength)
                chunkDigests.write(chunkDigest.digest())
                position += chunkLength
                remaining -= chunkLength
                chunkCount++
            }
        }
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(0x5a.toByte())
        messageDigest.update(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkCount).array()
        )
        messageDigest.update(chunkDigests.toByteArray())
        return messageDigest.digest()
    }

    private fun encodeSequenceOfLengthPrefixed(elements: List<ByteArray>): ByteArray {
        val total = elements.fold(0) { acc, element -> acc + 4 + element.size }
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        elements.forEach { element ->
            buffer.putInt(element.size)
            buffer.put(element)
        }
        return buffer.array()
    }

    private fun intToBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun findEocd(bytes: ByteArray): Int {
        // Scan backwards for the EOCD magic 0x06054b50.
        for (offset in bytes.size - 22 downTo 0) {
            if (bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
                bytes[offset + 2] == 0x05.toByte() && bytes[offset + 3] == 0x06.toByte()
            ) {
                return offset
            }
        }
        return -1
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
}




