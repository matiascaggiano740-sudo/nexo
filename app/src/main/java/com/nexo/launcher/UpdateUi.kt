package com.nexo.launcher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

@Composable
internal fun NexoSettingsDialog(
    onDismiss: () -> Unit,
    onDefaultHome: () -> Unit,
    onIcons: () -> Unit,
    onLock: () -> Unit,
    onBackup: () -> Unit,
    onUpdates: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { visible = true }
    fun close(after: () -> Unit = onDismiss) {
        visible = false
        scope.launch { delay(180); after() }
    }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 30 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 35 }
        ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF171411), contentColor = Color(0xFFF3EEE4)) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NEXO", color = Color(0xFFD6AE70), style = MaterialTheme.typography.labelLarge)
                        Text("Ajustes", color = Color(0xFFF3EEE4), fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    TextButton(onClick = { close() }, modifier = Modifier.testTag("close-nexo-settings")) { Text("Listo") }
                }
                Text("Tu inicio, a tu manera.", color = Color(0xFFBDB4A7), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp)) {
                    SettingsSection("LAUNCHER") {
                        SettingsAction("⌂", "Launcher predeterminado", "Elegí qué inicio abre Android", "Abrir", "settings-default-home") { close(onDefaultHome) }
                    }
                    SettingsSection("RESPALDO") {
                        SettingsAction("▤", "Copia de seguridad", "Guardá o restaurá tu inicio", "Abrir", "settings-backup") { close(onBackup) }
                    }
                    SettingsSection("ACTUALIZACIONES") {
                        SettingsAction("↻", "Actualizaciones", "Consultá versión y novedades", "Buscar", "settings-updates") { close(onUpdates) }
                    }
                    SettingsSection("GESTOS") {
                        SettingsAction("⌾", "Doble toque para bloquear", "Usá el bloqueo de Android", "Configurar", "settings-lock") { close(onLock) }
                    }
                    SettingsSection("APARIENCIA") {
                        SettingsAction("◉", "Estilo de iconos", "Elegí el aspecto de tus apps", "Cambiar", "settings-icons") { close(onIcons) }
                    }
                    Text("Los permisos de Android se solicitan solo cuando activás una función que los necesita.",
                        color = Color(0xFF9E9589), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(0xFFD6AE70), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp))
        Surface(color = Color(0xFF25211C), shape = RoundedCornerShape(22.dp), tonalElevation = 0.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsAction(glyph: String, title: String, subtitle: String, action: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 15.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = Color(0xFFD6AE70).copy(alpha = 0.13f), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(42.dp)) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(glyph, color = Color(0xFFD6AE70), fontSize = 22.sp)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color(0xFFF3EEE4), style = MaterialTheme.typography.titleSmall, maxLines = 2, softWrap = true)
            Text(subtitle, color = Color(0xFFBDB4A7), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 3.dp), maxLines = 2, softWrap = true)
        }
        Text(action, color = Color(0xFFD6AE70), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun NexoActionDialog(
    glyph: String,
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF25211C),
            contentColor = Color(0xFFF3EEE4),
            shadowElevation = 14.dp
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Surface(color = Color(0xFFD6AE70).copy(alpha = 0.13f), shape = RoundedCornerShape(16.dp), modifier = Modifier.size(48.dp)) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text(glyph, color = Color(0xFFD6AE70), fontSize = 24.sp)
                    }
                }
                Text(title, color = Color(0xFFF3EEE4), style = MaterialTheme.typography.headlineSmall)
                Text(message, color = Color(0xFFBDB4A7), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    TextButton(onClick = onConfirm, modifier = Modifier.testTag("nexo-action-confirm")) { Text(confirmLabel) }
                }
            }
        }
    }
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
