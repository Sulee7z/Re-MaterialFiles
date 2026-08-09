/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import com.android.apksig.ApkSigner
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs an APK with all supported signature schemes (v1 JAR signing, v2 and v3 APK
 * Signature Scheme) using the official AOSP [ApkSigner] library — the same code the
 * platform verifier is built against, so the output is always installable.
 */
object ApkSigner {

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "sora-editor", privateKey, listOf(certificate)
        ).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
