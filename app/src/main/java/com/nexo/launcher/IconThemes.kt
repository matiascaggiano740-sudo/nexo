package com.nexo.launcher

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

enum class IconTheme(val key: String, val title: String) {
    ORIGINAL("original", "Original POCO"),
    CRAYON("crayon", "Crayon"),
    LIQUID_GLASS("liquid", "Liquid Glass");

    companion object {
        fun fromKey(key: String?): IconTheme = entries.firstOrNull { it.key == key } ?: ORIGINAL
    }
}

fun savedIconTheme(context: Context): IconTheme = IconTheme.fromKey(
    context.getSharedPreferences("nexo_icon_settings", Context.MODE_PRIVATE).getString("theme", "original")
)


/** Decode the bundled original JPEG near launcher icon resolution, avoiding full-size bitmap allocations. */
fun decodeThemeIcon(context: Context, resourceId: Int): ImageBitmap? = runCatching {
    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
    BitmapFactory.decodeResource(context.resources, resourceId, options)?.asImageBitmap()
}.getOrNull()

/** Only package/icon pairs that can be identified confidently are themed. All others use the device icon. */
fun themedIconResource(theme: IconTheme, packageName: String): Int? = when (theme) {
    IconTheme.ORIGINAL -> null
    IconTheme.CRAYON -> when (packageName) {
        "com.android.chrome" -> R.drawable.icon_liquid_chrome
        "com.openai.chatgpt" -> R.drawable.icon_liquid_chatgpt
        "com.google.android.googlequicksearchbox" -> R.drawable.icon_liquid_google
        "com.netflix.mediaclient" -> R.drawable.icon_liquid_netflix
        "com.android.settings" -> R.drawable.icon_liquid_settings
        "com.instagram.barcelona" -> R.drawable.icon_liquid_threads
        "com.google.android.apps.photos" -> R.drawable.icon_liquid_photos
        "com.google.android.apps.docs" -> R.drawable.icon_liquid_docs
        "com.instagram.android" -> R.drawable.icon_liquid_instagram
        "com.pinterest" -> R.drawable.icon_liquid_pinterest
        "com.android.vending" -> R.drawable.icon_liquid_play_store
        "com.google.android.contacts", "com.android.contacts" -> R.drawable.icon_liquid_contacts
        "com.google.android.dialer", "com.android.dialer", "com.miui.dialer" -> R.drawable.icon_liquid_phone
        "com.canva.editor" -> R.drawable.icon_liquid_canva
        "com.google.android.youtube" -> R.drawable.icon_liquid_youtube
        "com.lemon.lvoverseas" -> R.drawable.icon_liquid_capcut
        "com.google.android.gm" -> R.drawable.icon_liquid_gmail
        "com.google.android.calendar" -> R.drawable.icon_liquid_calendar
        "com.google.android.calculator", "com.android.calculator2", "com.miui.calculator" -> R.drawable.icon_liquid_calculator
        "com.google.android.apps.nbu.files" -> R.drawable.icon_liquid_files
        "com.google.android.keep" -> R.drawable.icon_liquid_keep
        "com.twitter.android" -> R.drawable.icon_liquid_x
        else -> null
    }
    IconTheme.LIQUID_GLASS -> when (packageName) {
        "com.instagram.barcelona" -> R.drawable.icon_crayon_threads
        "com.zhiliaoapp.musically" -> R.drawable.icon_crayon_tiktok
        "com.spotify.music" -> R.drawable.icon_crayon_spotify
        "com.facebook.katana" -> R.drawable.icon_crayon_facebook
        "com.facebook.orca" -> R.drawable.icon_crayon_messenger
        "com.whatsapp" -> R.drawable.icon_crayon_whatsapp
        "com.android.settings" -> R.drawable.icon_crayon_settings
        "com.google.android.googlequicksearchbox" -> R.drawable.icon_crayon_google
        "com.google.android.dialer", "com.android.dialer", "com.miui.dialer" -> R.drawable.icon_crayon_phone
        "com.android.camera", "com.miui.camera", "com.android.camera2" -> R.drawable.icon_crayon_camera
        "com.google.android.calendar", "com.android.calendar" -> R.drawable.icon_crayon_calendar
        "com.google.android.gm" -> R.drawable.icon_crayon_gmail
        "com.google.android.apps.tachyon" -> R.drawable.icon_crayon_meet
        "com.google.android.contacts", "com.android.contacts" -> R.drawable.icon_crayon_contacts
        "com.miui.gallery" -> R.drawable.icon_crayon_gallery
        else -> null
    }
}
