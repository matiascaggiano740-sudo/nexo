package com.nexo.launcher

import android.util.Log
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class OtaEndToEndTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun realUpdaterRejectsBadHashThenOpensAndroidInstallerForVerifiedRelease() {
        val target = composeRule.activity
        target.getSharedPreferences("nexo_upgrade_e2e", 0).edit()
            .putString("user_layout_marker", "preserve-across-ota-e2e")
            .putInt("user_setting_marker", 73)
            .commit()
        FixtureServer.mode.set(Mode.HTTP_ERROR)
        openUpdater()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("El servidor respondió HTTP 503", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(TAG, "E2E: UI surfaced HTTP 503 from controlled manifest endpoint")
        composeRule.onNodeWithText("Cerrar").performClick()

        FixtureServer.mode.set(Mode.HTTP_NOT_FOUND)
        openUpdater()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Todavía no hay una actualización pública de NEXO", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Buscar actualizaciones").assertCountEquals(1)
        Log.i(TAG, "E2E: missing public release is shown as an empty update state, not a generic error")
        composeRule.onNodeWithText("Cerrar").performClick()

        FixtureServer.mode.set(Mode.BAD_HASH)
        openUpdater()
        assertAvailableRelease()
        composeRule.onNodeWithText("Actualizar ahora").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("update-download-progress").fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(TAG, "E2E: actual UI displayed DownloadManager progress for bad-hash fixture")
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodesWithText("El SHA-256 del APK no coincide", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(TAG, "E2E: tampered manifest hash rejected by real download-and-verify path")
        composeRule.onNodeWithText("Cerrar").performClick()

        FixtureServer.mode.set(Mode.VALID)
        openUpdater()
        assertAvailableRelease()
        composeRule.onNodeWithText("Actualizar ahora").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("update-download-progress").fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(TAG, "E2E: actual UI displayed DownloadManager progress for valid candidate")
        composeRule.waitUntil(120_000) {
            val top = topActivityDump()
            hasAndroidInstallerOnTop(top)
        }
        val top = topActivityDump()
        val resumedLine = top.lineSequence().firstOrNull { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
        Log.i(TAG, "E2E: verified APK launched Android package installer; top activity: $resumedLine")
        assertTrue(
            "Expected Android system package installer to be foreground after hash, package, version and certificate validation. Resumed activity: $resumedLine",
            hasAndroidInstallerOnTop(top)
        )
        Log.i(TAG, "E2E: installer handoff completed; CI driver will accept Android confirmation and verify installed data/version")
    }

    private fun openUpdater() {
        composeRule.onNodeWithTag("open-nexo-settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings-updates").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings-updates").performClick()
    }

    private fun assertAvailableRelease() {
        val args = InstrumentationRegistry.getArguments()
        val versionCode = args.getString("targetVersionCode")?.toLongOrNull()
            ?: error("OTA test must receive targetVersionCode instrumentation argument")
        val versionName = args.getString("targetVersionName")
            ?: error("OTA test must receive targetVersionName instrumentation argument")
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Versión $versionName · código $versionCode").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Publicada: 2026-11-25T09:00:00Z").assertCountEquals(1)
        composeRule.onAllNodesWithText("OTA E2E controlled release; no public release").assertCountEquals(1)
        Log.i(TAG, "E2E: UI showed version $versionName/code $versionCode, publication date, and controlled changelog")
    }

    private fun topActivityDump(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity activities")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun hasAndroidInstallerOnTop(dump: String): Boolean {
        val resumed = dump.lineSequence().firstOrNull { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
            ?: return false
        return listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        ).any(resumed::contains)
    }

    companion object {
        private const val TAG = "NexoOtaE2E"
        private val baseUrl = "http://127.0.0.1:8765"

        @JvmStatic @BeforeClass fun startControlledEndpoint() = FixtureServer.start()
        @JvmStatic @AfterClass fun stopControlledEndpoint() = FixtureServer.stop()

        private enum class Mode { HTTP_ERROR, HTTP_NOT_FOUND, BAD_HASH, VALID }

        private object FixtureServer {
            val mode = AtomicReference(Mode.HTTP_ERROR)
            private var server: ServerSocket? = null
            private var thread: Thread? = null
            private lateinit var apk: ByteArray
            private lateinit var goodHash: String

            @Synchronized fun start() {
                apk = InstrumentationRegistry.getInstrumentation().context.assets.open("ota-candidate.apk").use { it.readBytes() }
                goodHash = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02X".format(it) }
                server = ServerSocket(8765)
                thread = Thread({ serve() }, "nexo-ota-fixture-server").apply { isDaemon = true; start() }
                Log.i(TAG, "E2E endpoint started on loopback; APK bytes=${apk.size}; sha256=$goodHash")
            }

            @Synchronized fun stop() {
                runCatching { server?.close() }
                thread?.interrupt()
                server = null
                Log.i(TAG, "E2E endpoint stopped")
            }

            private fun serve() {
                while (!Thread.currentThread().isInterrupted) {
                    val socket = try { server?.accept() ?: return } catch (_: Exception) { return }
                    Thread({ handle(socket) }, "nexo-ota-fixture-request").apply { isDaemon = true; start() }
                }
            }

            private fun handle(socket: Socket) {
                socket.use { client ->
                    client.soTimeout = 15_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                    val request = reader.readLine().orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val path = request.split(' ').getOrNull(1).orEmpty()
                    Log.i(TAG, "E2E endpoint request: $request; mode=${mode.get()}")
                    when {
                        path.startsWith("/repos/matiascaggiano740-sudo/nexo/releases/latest") -> {
                            when (mode.get()) {
                                Mode.HTTP_ERROR -> respond(client, 503, "text/plain", "controlled outage".toByteArray())
                                Mode.HTTP_NOT_FOUND -> respond(client, 404, "application/json", "{\"message\":\"Not Found\"}".toByteArray())
                                else -> {
                                    val json = """{"assets":[{"name":"nexo-update.json","browser_download_url":"$baseUrl/assets/nexo-update.json"}]}"""
                                    respond(client, 200, "application/vnd.github+json", json.toByteArray())
                                }
                            }
                        }
                        path.startsWith("/assets/nexo-update.json") -> {
                            val hash = if (mode.get() == Mode.BAD_HASH) "00".repeat(32) else goodHash
                            val candidatePath = if (mode.get() == Mode.BAD_HASH) "/apk/candidate-bad-hash.apk" else "/apk/candidate-valid.apk"
                            val args = InstrumentationRegistry.getArguments()
                            val targetCode = args.getString("targetVersionCode")?.toLongOrNull()
                                ?: error("Missing targetVersionCode instrumentation argument")
                            val targetName = args.getString("targetVersionName")
                                ?: error("Missing targetVersionName instrumentation argument")
                            val json = """{"applicationId":"com.nexo.launcher","versionCode":$targetCode,"versionName":"$targetName","publishedAt":"2026-11-25T09:00:00Z","changelog":"OTA E2E controlled release; no public release","apkUrl":"$baseUrl$candidatePath","sha256":"$hash","signingCertificateSha256":"${UpdateManager.EXPECTED_CERT_SHA256}"}"""
                            respond(client, 200, "application/json", json.toByteArray())
                        }
                        path.startsWith("/apk/candidate-") -> {
                            client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${apk.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                                flush()
                                var offset = 0
                                while (offset < apk.size) {
                                    val end = minOf(offset + 65_536, apk.size)
                                    write(apk, offset, end - offset)
                                    flush()
                                    offset = end
                                    Thread.sleep(90)
                                }
                            }
                        }
                        else -> respond(client, 404, "text/plain", "not found".toByteArray())
                    }
                }
            }

            private fun respond(socket: Socket, status: Int, type: String, body: ByteArray) {
                val reason = if (status == 200) "OK" else if (status == 503) "Service Unavailable" else "Not Found"
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(body)
                output.flush()
            }
        }
    }
}

class OtaPostInstallVerificationTest {
    @Test fun installedCandidateHasVersionAndUserPreferencesSurvived() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("nexo_upgrade_e2e", 0)
        assertEquals("preserve-across-ota-e2e", prefs.getString("user_layout_marker", null))
        assertEquals(73, prefs.getInt("user_setting_marker", -1))
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val version = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val targetCode = InstrumentationRegistry.getArguments().getString("targetVersionCode")?.toLongOrNull()
            ?: error("OTA test must receive targetVersionCode instrumentation argument")
        assertEquals(targetCode, version)
        Log.i("NexoOtaE2E", "POST-OTA: installed versionCode=$version and both user preference markers survived")
    }
}
