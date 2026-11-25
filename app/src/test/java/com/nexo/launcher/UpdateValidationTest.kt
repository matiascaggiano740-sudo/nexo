package com.nexo.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class UpdateValidationTest {
    private val update = NexoUpdate(
        applicationId = "com.nexo.launcher",
        versionCode = 1001,
        versionName = "1.0.1",
        publishedAt = "2026-11-25T00:00:00Z",
        changelog = "Correcciones",
        apkUrl = "https://example.invalid/nexo.apk",
        sha256 = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
        signingCertificateSha256 = "058532948C51385E15884CB14DC6A916DEB3BC8D42D170A1C70336467D99A463",
    )

    @Test fun sha256AcceptsExpectedBytesAndRejectsTampering() {
        val file = File.createTempFile("nexo-update-test", ".bin")
        try {
            file.writeText("abc")
            assertEquals(update.sha256, UpdateValidation.sha256(file))
            UpdateValidation.verifyHash(file, update.sha256.lowercase())
            file.appendText("!")
            assertThrows(IllegalArgumentException::class.java) { UpdateValidation.verifyHash(file, update.sha256) }
        } finally { file.delete() }
    }

    @Test fun packageAndBothVersionFieldsMustMatchManifest() {
        UpdateValidation.validatePackageAndVersion("com.nexo.launcher", 1001, "1.0.1", update)
        assertThrows(IllegalArgumentException::class.java) {
            UpdateValidation.validatePackageAndVersion("com.other.launcher", 1001, "1.0.1", update)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateValidation.validatePackageAndVersion("com.nexo.launcher", 1002, "1.0.1", update)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateValidation.validatePackageAndVersion("com.nexo.launcher", 1001, "9.9.9", update)
        }
    }

    @Test fun onlyExpectedMatchingSigningCertificatesAreAccepted() {
        val expected = update.signingCertificateSha256
        UpdateValidation.validateSignatures(expected, expected, expected)
        assertThrows(IllegalArgumentException::class.java) {
            UpdateValidation.validateSignatures("00".repeat(32), expected, expected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateValidation.validateSignatures(expected, "11".repeat(32), expected)
        }
    }
}
