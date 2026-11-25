package com.nexo.launcher

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class NexoUpdate(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val changelog: String,
    val apkUrl: String,
    val sha256: String,
    val signingCertificateSha256: String,
)

internal sealed interface UpdateCheckResult {
    data class Available(val update: NexoUpdate, val installedVersionCode: Long) : UpdateCheckResult
    data class Current(val installedVersionCode: Long) : UpdateCheckResult
}

internal class UpdateManager(private val context: Context) {
    companion object {
        const val PACKAGE_ID = "com.nexo.launcher"
        const val EXPECTED_CERT_SHA256 = "058532948C51385E15884CB14DC6A916DEB3BC8D42D170A1C70336467D99A463"
        private const val MANIFEST_ASSET = "nexo-update.json"
    }

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val release = readJson(BuildConfig.NEXO_RELEASES_API_URL, "application/vnd.github+json")
        val assets = release.optJSONArray("assets") ?: error("El release no tiene archivos publicados")
        val manifestUrl = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name") == MANIFEST_ASSET }
            ?.optString("browser_download_url")?.takeIf(String::isNotBlank)
            ?: error("El release no incluye $MANIFEST_ASSET")
        val json = readJson(manifestUrl, "application/json")
        val update = NexoUpdate(
            applicationId = json.getString("applicationId"),
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            publishedAt = json.getString("publishedAt"),
            changelog = json.getString("changelog"),
            apkUrl = json.getString("apkUrl"),
            sha256 = UpdateValidation.normalizeHex(json.getString("sha256")),
            signingCertificateSha256 = UpdateValidation.normalizeHex(json.getString("signingCertificateSha256")),
        )
        require(update.applicationId == PACKAGE_ID) { "El paquete publicado no es NEXO" }
        require(update.apkUrl.startsWith("https://")) { "La URL del APK no es HTTPS" }
        require(update.sha256.matches(Regex("[A-F0-9]{64}"))) { "SHA-256 publicado inválido" }
        require(update.signingCertificateSha256 == EXPECTED_CERT_SHA256) { "El certificado publicado no coincide con la firma esperada" }
        val installed = installedVersionCode()
        require(installedSignerFingerprint() == EXPECTED_CERT_SHA256) { "La firma instalada no coincide con el certificado esperado de NEXO" }
        require(update.versionCode >= installed) { "La versión publicada es anterior a la instalada" }
        if (update.versionCode == installed) UpdateCheckResult.Current(installed)
        else UpdateCheckResult.Available(update, installed)
    }

    suspend fun downloadAndVerify(update: NexoUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Actualización de NEXO ${update.versionName}")
            .setDescription("Descargando actualización")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "nexo-update-${update.versionCode}.apk")
        val manager = context.getSystemService(DownloadManager::class.java)
        val prefs = context.getSharedPreferences("nexo_updates", Context.MODE_PRIVATE)
        var id = prefs.getLong("download_id", -1L)
        if (id >= 0L) {
            val reusable = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                cursor.moveToFirst() && cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) == update.apkUrl &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_FAILED
            }
            if (!reusable) { manager.remove(id); id = -1L }
        }
        if (id < 0L) {
            id = manager.enqueue(request)
            prefs.edit().putLong("download_id", id).apply()
        }
        var finished = false
        while (!finished) {
            val query = manager.query(DownloadManager.Query().setFilterById(id))
            query.use { cursor ->
                if (!cursor.moveToFirst()) error("Android no encontró la descarga")
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> { withContext(Dispatchers.Main) { onProgress(100) }; finished = true }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        prefs.edit().remove("download_id").apply()
                        manager.remove(id)
                        error("Falló la descarga (código $reason)")
                    }
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) withContext(Dispatchers.Main) { onProgress(((current * 100) / total).toInt().coerceIn(0, 99)) }
                    }
                    else -> error("Estado de descarga desconocido")
                }
            }
            if (!finished) delay(350)
        }
        val sourceUri = manager.getUriForDownloadedFile(id) ?: error("Android no generó una URI para el APK")
        val destination = File(context.filesDir, "updates/nexo-${update.versionCode}.apk")
        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo leer el APK descargado")
        UpdateValidation.verifyHash(destination, update.sha256)
        verifyApk(destination, update)
        prefs.edit().remove("download_id").apply()
        manager.remove(id)
        destination
    }

    fun installerUri(apk: File): Uri = FileProvider.getUriForFile(context, "$PACKAGE_ID.fileprovider", apk)

    @Suppress("DEPRECATION")
    fun installedVersionCode(): Long {
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(PACKAGE_ID, PackageManager.PackageInfoFlags.of(0))
        else context.packageManager.getPackageInfo(PACKAGE_ID, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, update: NexoUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = (if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else context.packageManager.getPackageArchiveInfo(file.absolutePath, flags))
            ?: error("El archivo descargado no es un APK válido")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        UpdateValidation.validatePackageAndVersion(info.packageName, versionCode, info.versionName, update)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        else info.signatures?.map { it.toByteArray() }
        val archiveCert = signerFingerprint(signatures.orEmpty())
        val installed = installedSignerFingerprint()
        UpdateValidation.validateSignatures(archiveCert, installed, update.signingCertificateSha256)
    }

    @Suppress("DEPRECATION")
    fun installedSignerFingerprint(): String {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(PACKAGE_ID, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else context.packageManager.getPackageInfo(PACKAGE_ID, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        else info.signatures?.map { it.toByteArray() }
        return signerFingerprint(signatures.orEmpty())
    }

    private fun signerFingerprint(certificates: List<ByteArray>): String {
        require(certificates.size == 1) { "Se esperaba un único certificado de firma" }
        return MessageDigest.getInstance("SHA-256").digest(certificates.single()).toHex()
    }

    private fun readJson(url: String, accept: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "NEXO-Launcher-Updater")
        try {
            if (connection.responseCode !in 200..299) error("El servidor respondió HTTP ${connection.responseCode}")
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1_048_576) { "La respuesta de actualización supera el tamaño permitido" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

} 

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
