/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * One-click APK signing with an auto-generated keystore stored in the app's private storage.
 * All APKs signed this way share the same key.
 */
object AutoSigner {

    private const val ALIAS = "auto"
    private const val PASSWORD = "sora-editor"

    fun getOrCreateKey(context: Context): Pair<PrivateKey, X509Certificate> {
        val directory = File(context.filesDir, "auto-sign")
        directory.mkdirs()
        val file = File(directory, "auto.p12")
        val keyStore = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
        } else {
            keyStore.load(null, null)
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()
            val now = System.currentTimeMillis()
            val certificateBuilder = JcaX509v3CertificateBuilder(
                X500Name("CN=Sora-Editor Auto Signer"),
                BigInteger.valueOf(now),
                Date(now),
                Date(now + 100L * 365 * 24 * 3600 * 1000),
                X500Name("CN=Sora-Editor Auto Signer"),
                keyPair.public
            )
            val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter()
                .getCertificate(certificateBuilder.build(contentSigner))
            keyStore.setKeyEntry(ALIAS, keyPair.private, PASSWORD.toCharArray(), arrayOf(certificate))
            file.outputStream().use { keyStore.store(it, PASSWORD.toCharArray()) }
        }
        val privateKey = keyStore.getKey(ALIAS, PASSWORD.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        return privateKey to certificate
    }
}
