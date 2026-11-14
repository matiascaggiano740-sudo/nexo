package com.nexo.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap, val intent: Intent, val category: Int = ApplicationInfo.CATEGORY_UNDEFINED)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            AppItem(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(), launch, ri.activityInfo.applicationInfo.category)
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/** Teléfono, Mensajes y Cámara: las apps predeterminadas del sistema. */
fun loadSystemDock(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
    )
    return intents.mapNotNull { i ->
        val ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: return@mapNotNull null
        val info = ri.activityInfo
        AppItem(info.loadLabel(pm).toString(), info.packageName, info.loadIcon(pm).toBitmap(128, 128).asImageBitmap(), i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
