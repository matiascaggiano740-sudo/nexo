package com.nexo.launcher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun NexoSettingsDialog(onDismiss: () -> Unit, onUpdates: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de NEXO") },
        text = {
            Column {
                Text("Launcher", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                }) { Text("Elegir NEXO como inicio predeterminado") }
                TextButton(onClick = onUpdates, modifier = Modifier.fillMaxWidth().testTag("settings-updates")) {
                    Text("Actualizaciones")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
internal fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val manager = remember(context) { UpdateManager(context) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<NexoUpdate?>(null) }
    var downloaded by remember {
        mutableStateOf(context.getSharedPreferences("nexo_updates", 0).getString("downloaded_apk", null)?.let(::File)?.takeIf(File::exists))
    }
    var message by remember { mutableStateOf("Consultando versiones publicadas…") }

    fun checkForUpdates() {
        if (busy) return
        busy = true; error = null; message = "Consultando versiones publicadas…"
        scope.launch {
            try {
                result = manager.check()
                update = (result as? UpdateCheckResult.Available)?.update
                message = if (update == null) "NEXO ya está actualizado." else "Hay una actualización disponible."
            } catch (t: Throwable) { error = t.message ?: "No se pudo consultar el servidor." }
            finally { busy = false }
        }
    }

    fun install(file: File, targetVersion: Long) {
        val app = activity ?: run { error = "No se pudo abrir el instalador desde esta pantalla."; return }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            app.getSharedPreferences("nexo_updates", 0).edit().putLong("pending_version", targetVersion).putBoolean("waiting_permission", true).apply()
            runCatching {
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                message = "Permití instalar aplicaciones para NEXO y volvé aquí; Android te pedirá confirmar la instalación."
            }.onFailure { error = "No se pudieron abrir los permisos de instalación: ${it.message}" }
            return
        }
        runCatching {
            app.getSharedPreferences("nexo_updates", 0).edit().putLong("pending_version", targetVersion).putBoolean("waiting_permission", false).apply()
            val uri = manager.installerUri(file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(intent)
            message = "Se abrió el instalador oficial de Android. Confirmá allí la actualización."
        }.onFailure { error = "No se pudo iniciar el instalador: ${it.message}" }
    }

    LaunchedEffect(Unit) { checkForUpdates() }
    DisposableEffect(lifecycleOwner, downloaded, update) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val prefs = context.getSharedPreferences("nexo_updates", 0)
                val pending = prefs.getLong("pending_version", -1L)
                if (pending > 0L && prefs.getBoolean("waiting_permission", false)) {
                    prefs.edit().putBoolean("waiting_permission", false).apply()
                    if (Build.VERSION.SDK_INT >= 26 && context.packageManager.canRequestPackageInstalls() && downloaded != null) {
                        update?.let { install(downloaded!!, it.versionCode) }
                    } else message = "El permiso de instalación no está habilitado. La descarga se conserva; tocá Actualizar ahora para reintentar."
                } else if (pending > 0L) {
                    val installed = runCatching { manager.installedVersionCode() }.getOrDefault(-1L)
                    prefs.edit().remove("pending_version").apply()
                    if (installed >= pending) {
                        prefs.edit().remove("downloaded_apk").apply()
                        downloaded = null
                        message = "Actualización instalada. NEXO está en versionCode $installed."
                        result = UpdateCheckResult.Current(installed)
                    } else message = "La instalación se canceló o no se completó; la versión actual se conserva."
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val installedCode = (result as? UpdateCheckResult.Available)?.installedVersionCode
        ?: (result as? UpdateCheckResult.Current)?.installedVersionCode
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Actualizaciones de NEXO") },
        text = {
            Column {
                installedCode?.let { Text("Versión instalada: $it", style = MaterialTheme.typography.bodySmall) }
                update?.let {
                    Text("Versión ${it.versionName} · código ${it.versionCode}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text("Publicada: ${it.publishedAt}", style = MaterialTheme.typography.bodySmall)
                    Text(it.changelog, modifier = Modifier.padding(top = 8.dp))
                }
                Text(error ?: message, modifier = Modifier.padding(top = 8.dp), color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (busy && update != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("update-download-progress"))
            }
        },
        confirmButton = {
            if (update != null && !busy) TextButton(onClick = {
                val selected = update ?: return@TextButton
                busy = true; error = null; progress = 0
                scope.launch {
                    try {
                        val readyFile = downloaded ?: manager.downloadAndVerify(selected) { progress = it }.also {
                            context.getSharedPreferences("nexo_updates", 0).edit().putString("downloaded_apk", it.absolutePath).apply()
                        }
                        downloaded = readyFile
                        install(readyFile, selected.versionCode)
                    } catch (t: Throwable) { error = t.message ?: "No se pudo preparar la actualización." }
                    finally { busy = false }
                }
            }) { Text(if (downloaded == null) "Actualizar ahora" else "Continuar instalación") }
            else TextButton(enabled = !busy, onClick = ::checkForUpdates) { Text("Buscar actualizaciones") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cerrar") } }
    )
}
