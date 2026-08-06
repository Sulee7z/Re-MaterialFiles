/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs an APK with all supported signature schemes: v1 (JAR signing), v2 and v3
 * (APK Signature Scheme v2/v3).
 */
object ApkSigner {

    fun sign(
        input: File,
        output: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val v1File = File(output.parentFile, output.name + ".v1.tmp")
        val v2File = File(output.parentFile, output.name + ".v2.tmp")
        try {
            ApkV1Signer.sign(input, v1File, privateKey, certificate)
            ApkV2V3Signer.sign(v1File, v2File, privateKey, certificate)
            if (!v2File.renameTo(output)) {
                v2File.copyTo(output, overwrite = true)
            }
        } finally {
            v1File.delete()
            v2File.delete()
        }
    }
}
