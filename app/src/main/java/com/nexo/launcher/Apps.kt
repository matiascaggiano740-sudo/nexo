package com.nexo.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap, val intent: Intent, val category: Int = ApplicationInfo.CATEGORY_UNDEFINED, val usesThemeAsset: Boolean = false)

fun loadApps(ctx: Context, theme: IconTheme = savedIconTheme(ctx)): List<AppItem> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != ctx.packageName }
        // Some apps expose several launcher aliases. Keep one entry per package before decoding icons.
        .distinctBy { it.activityInfo.packageName }
        .mapNotNull { ri -> runCatching {
            val pkg = ri.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@runCatching null
            val themeResource = themedIconResource(theme, pkg)
            val themedIcon = themeResource?.let { decodeThemeIcon(ctx, it) }
            val icon = themedIcon ?: ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap()
            AppItem(ri.loadLabel(pm).toString(), pkg, icon, launch, ri.activityInfo.applicationInfo.category, themedIcon != null)
        }.getOrNull() }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/** Teléfono, Mensajes y Cámara: las apps predeterminadas del sistema. */
fun loadSystemDock(ctx: Context, theme: IconTheme = savedIconTheme(ctx)): List<AppItem?> {
    val pm = ctx.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
    )
    // Keep the dialer/messages/camera positions stable even when a device has no handler for one.
    return intents.map { intent -> runCatching {
        val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return@runCatching null
        val info = ri.activityInfo
        val themeResource = themedIconResource(theme, info.packageName)
        val themedIcon = themeResource?.let { decodeThemeIcon(ctx, it) }
        val icon = themedIcon ?: info.loadIcon(pm).toBitmap(128, 128).asImageBitmap()
        AppItem(info.loadLabel(pm).toString(), info.packageName, icon, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), usesThemeAsset = themedIcon != null)
    }.getOrNull() }
}
