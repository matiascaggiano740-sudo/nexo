package com.nexo.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

const val DEFAULT_QUOTE = "Lo simple también es hermoso."

private fun prefs(ctx: Context) = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE)

fun savedCity(ctx: Context): String = prefs(ctx).getString("city", "") ?: ""
fun saveCity(ctx: Context, v: String) = prefs(ctx).edit().putString("city", v).apply()

fun savedQuote(ctx: Context): String = prefs(ctx).getString("quote", DEFAULT_QUOTE) ?: DEFAULT_QUOTE
fun saveQuote(ctx: Context, v: String) = prefs(ctx).edit().putString("quote", v).apply()

fun savePhoto(ctx: Context, uri: String) = prefs(ctx).edit().putString("photo", uri).remove("photo_backup_b64").apply()

fun loadCardPhoto(ctx: Context): ImageBitmap? {
    val p = prefs(ctx)
    val s = p.getString("photo", null)
    val fromUri = s?.let { uriText -> runCatching {
        val uri = Uri.parse(uriText)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    }.getOrNull() }
    fromUri ?: p.getString("photo_backup_b64", null)?.let { encoded -> runCatching {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull() }
}
