/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkkiller

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

class ApkKillException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Extracts the certificate chain of an APK so it can be embedded in the killer
 * application. Relies on the platform's own package parser (works for v1 JAR signing as
 * well as v2/v3 APK Signature Scheme), which is exactly what the killer must fake at
 * runtime. The input must be a local file (copy it to the cache first for remote paths).
 */
object ApkSignatureExtractor {

    /**
     * Returns the DER-encoded certificates of [apkFile], newest/primary first as the
     * package manager reports them.
     */
    @Suppress("DEPRECATION")
    fun extractCertificates(context: Context, apkFile: File): List<ByteArray> {
        val flags = PackageManager.GET_SIGNATURES
        val packageInfo = try {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } catch (e: Throwable) {
            throw ApkKillException("Failed to read APK package info: ${e.message}", e)
        } ?: throw ApkKillException("Unable to read APK package info")
        val signatures = packageInfo.signatures
            ?: throw ApkKillException("No signatures found in APK")
        if (signatures.isEmpty()) {
            throw ApkKillException("No signatures found in APK")
        }
        return signatures.map { it.toByteArray() }
    }

    /**
     * Encodes the certificates into the payload the killer decodes at runtime:
     * [1 byte count][4-byte big-endian length][DER bytes]... repeated per certificate.
     */
    fun buildPayload(certificates: List<ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(certificates.size and 0xff)
        certificates.forEach { certificate ->
            buffer.write((certificate.size ushr 24) and 0xff)
            buffer.write((certificate.size ushr 16) and 0xff)
            buffer.write((certificate.size ushr 8) and 0xff)
            buffer.write(certificate.size and 0xff)
            buffer.write(certificate)
        }
        return buffer.toByteArray()
    }
}
