package com.nexo.launcher

import java.io.File
import java.security.MessageDigest

internal object UpdateValidation {
    fun normalizeHex(value: String): String = value.uppercase().filter { it.isLetterOrDigit() }

    fun validatePackageAndVersion(packageName: String?, versionCode: Long, versionName: String?, update: NexoUpdate) {
        require(packageName == UpdateManager.PACKAGE_ID && packageName == update.applicationId) { "El APK pertenece a otro paquete" }
        require(versionCode == update.versionCode) { "El versionCode del APK no coincide con el manifiesto" }
        require(versionName == update.versionName) { "El versionName del APK no coincide con el manifiesto" }
    }

    fun validateSignatures(apkFingerprint: String, installedFingerprint: String, updateFingerprint: String) {
        val expected = UpdateManager.EXPECTED_CERT_SHA256
        require(normalizeHex(apkFingerprint) == expected && normalizeHex(installedFingerprint) == expected && normalizeHex(updateFingerprint) == expected) {
            "La firma del APK no coincide con NEXO instalado; actualización rechazada"
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    fun verifyHash(file: File, expected: String) {
        require(normalizeHex(expected).matches(Regex("[A-F0-9]{64}"))) { "SHA-256 publicado inválido" }
        require(sha256(file) == normalizeHex(expected)) { "El SHA-256 del APK no coincide; actualización rechazada" }
    }
}
