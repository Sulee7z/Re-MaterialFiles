/*
 * Copyright (c) 2024 Material Files (Sora-Editor) contributors
 * All Rights Reserved.
 *
 * APK re-signing support, adding an MT-Manager-like "sign APK" capability.
 */

package me.zhanghai.android.files.filejob

import android.content.Context
import android.os.Parcelable
import com.android.apksig.ApkSigner
import kotlinx.parcelize.Parcelize
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Describes how an APK should be signed. Parcelable so it can be carried from the picker dialog
 * through [FileJobService] into a [SignApkJob].
 */
sealed interface ApkSigningKeySpec : Parcelable {
    /** Sign with the bundled debug/test key. Always available, needs no user input. */
    @Parcelize
    object TestKey : ApkSigningKeySpec

    /** Sign with a user-provided key store file (PKCS12/.p12/.pfx/.keystore or BKS). */
    @Parcelize
    data class UserKeyStore(
        val keyStorePath: String,
        val keyStorePassword: String,
        val keyAlias: String?,
        val keyPassword: String?
    ) : ApkSigningKeySpec
}

/** Resolved private key + certificate chain ready to be handed to apksig. */
class ApkSigningKey(val privateKey: PrivateKey, val certificates: List<X509Certificate>)

object ApkSigning {
    private const val TEST_KEY_CERTIFICATE_ASSET = "sign/testkey.x509.pem"
    private const val TEST_KEY_PRIVATE_KEY_ASSET = "sign/testkey.pk8"

    // Types Android can actually open. JKS is intentionally omitted because the platform ships no
    // JKS provider; users with a JKS key store should convert it to PKCS12 first.
    private val KEY_STORE_TYPES = listOf("PKCS12", "BKS")

    @Throws(Exception::class)
    fun resolveKey(context: Context, spec: ApkSigningKeySpec): ApkSigningKey =
        when (spec) {
            is ApkSigningKeySpec.TestKey -> loadTestKey(context)
            is ApkSigningKeySpec.UserKeyStore -> loadUserKeyStore(spec)
        }

    @Throws(Exception::class)
    private fun loadTestKey(context: Context): ApkSigningKey {
        val certificate = context.assets.open(TEST_KEY_CERTIFICATE_ASSET).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val privateKeyBytes = context.assets.open(TEST_KEY_PRIVATE_KEY_ASSET).use { it.readBytes() }
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        return ApkSigningKey(privateKey, listOf(certificate))
    }

    @Throws(Exception::class)
    private fun loadUserKeyStore(spec: ApkSigningKeySpec.UserKeyStore): ApkSigningKey {
        val keyStoreBytes = File(spec.keyStorePath).readBytes()
        val storePassword = spec.keyStorePassword.toCharArray()
        val keyStore = loadKeyStoreOfAnyType(keyStoreBytes, storePassword)
        val alias = spec.keyAlias?.takeIf { it.isNotEmpty() }
            ?: keyStore.aliases().toList().firstOrNull()
            ?: throw KeyStoreException("Key store contains no aliases")
        val keyPassword =
            (spec.keyPassword?.takeIf { it.isNotEmpty() } ?: spec.keyStorePassword).toCharArray()
        val privateKey = keyStore.getKey(alias, keyPassword) as? PrivateKey
            ?: throw KeyStoreException("Alias \"$alias\" does not contain a private key")
        val chain = keyStore.getCertificateChain(alias)
            ?: throw KeyStoreException("Alias \"$alias\" does not contain a certificate chain")
        val certificates = chain.map { it as X509Certificate }
        return ApkSigningKey(privateKey, certificates)
    }

    @Throws(Exception::class)
    private fun loadKeyStoreOfAnyType(bytes: ByteArray, password: CharArray): KeyStore {
        var lastError: Exception? = null
        for (type in KEY_STORE_TYPES) {
            try {
                val keyStore = KeyStore.getInstance(type)
                ByteArrayInputStream(bytes).use { keyStore.load(it, password) }
                return keyStore
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: KeyStoreException("Unsupported key store format")
    }

    /**
     * Signs [inputApk] into [outputApk] with v1 (JAR), v2 and v3 schemes. apksig also zip-aligns
     * the output. [inputApk] and [outputApk] must be different files.
     */
    @Throws(Exception::class)
    fun sign(inputApk: File, outputApk: File, key: ApkSigningKey) {
        val signerConfig =
            ApkSigner.SignerConfig.Builder("CERT", key.privateKey, key.certificates).build()
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
        signer.sign()
    }
}
