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

/** Every user-provided artwork file bundled for a pack. */
internal fun bundledIconAssets(theme: IconTheme): IntArray = when (theme) {
    IconTheme.ORIGINAL -> intArrayOf()
    IconTheme.CRAYON -> intArrayOf(
        R.drawable.icon_crayon_calendar,
        R.drawable.icon_crayon_camera,
        R.drawable.icon_crayon_contacts,
        R.drawable.icon_crayon_facebook,
        R.drawable.icon_crayon_gallery,
        R.drawable.icon_crayon_gmail,
        R.drawable.icon_crayon_google,
        R.drawable.icon_crayon_keep,
        R.drawable.icon_crayon_meet,
        R.drawable.icon_crayon_message_bubble,
        R.drawable.icon_crayon_messenger,
        R.drawable.icon_crayon_phone,
        R.drawable.icon_crayon_settings,
        R.drawable.icon_crayon_spotify,
        R.drawable.icon_crayon_threads,
        R.drawable.icon_crayon_tiktok,
        R.drawable.icon_crayon_weather,
        R.drawable.icon_crayon_whatsapp
    )
    IconTheme.LIQUID_GLASS -> intArrayOf(
        R.drawable.icon_liquid_bible,
        R.drawable.icon_liquid_calculator,
        R.drawable.icon_liquid_calendar,
        R.drawable.icon_liquid_canva,
        R.drawable.icon_liquid_capcut,
        R.drawable.icon_liquid_chatgpt,
        R.drawable.icon_liquid_chrome,
        R.drawable.icon_liquid_contacts,
        R.drawable.icon_liquid_docs,
        R.drawable.icon_liquid_files,
        R.drawable.icon_liquid_gmail,
        R.drawable.icon_liquid_google,
        R.drawable.icon_liquid_instagram,
        R.drawable.icon_liquid_keep,
        R.drawable.icon_liquid_mail,
        R.drawable.icon_liquid_maps,
        R.drawable.icon_liquid_netflix,
        R.drawable.icon_liquid_phone,
        R.drawable.icon_liquid_photos,
        R.drawable.icon_liquid_pinterest,
        R.drawable.icon_liquid_pinwheel,
        R.drawable.icon_liquid_play_store,
        R.drawable.icon_liquid_settings,
        R.drawable.icon_liquid_threads,
        R.drawable.icon_liquid_x,
        R.drawable.icon_liquid_youtube
    )
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
        "com.android.settings", "com.miui.securitycenter" -> R.drawable.icon_crayon_settings
        "com.google.android.keep" -> R.drawable.icon_crayon_keep
        "com.miui.weather2", "com.google.android.apps.weather" -> R.drawable.icon_crayon_weather
        "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging", "com.samsung.android.messaging" -> R.drawable.icon_crayon_message_bubble
        "com.whatsapp", "com.whatsapp.w4b" -> R.drawable.icon_crayon_whatsapp
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> R.drawable.icon_crayon_tiktok
        "com.google.android.googlequicksearchbox" -> R.drawable.icon_crayon_google
        "com.google.android.dialer", "com.android.dialer", "com.miui.dialer" -> R.drawable.icon_crayon_phone
        "com.spotify.music" -> R.drawable.icon_crayon_spotify
        "com.google.android.contacts", "com.android.contacts" -> R.drawable.icon_crayon_contacts
        "com.google.android.calendar", "com.android.calendar" -> R.drawable.icon_crayon_calendar
        "com.facebook.katana" -> R.drawable.icon_crayon_facebook
        "com.android.camera", "com.miui.camera", "com.android.camera2" -> R.drawable.icon_crayon_camera
        "com.facebook.orca" -> R.drawable.icon_crayon_messenger
        "com.google.android.apps.tachyon" -> R.drawable.icon_crayon_meet
        "com.miui.gallery" -> R.drawable.icon_crayon_gallery
        "com.google.android.gm" -> R.drawable.icon_crayon_gmail
        "com.instagram.barcelona" -> R.drawable.icon_crayon_threads
        else -> null
    }
    IconTheme.LIQUID_GLASS -> when (packageName) {
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
        "com.google.android.apps.maps" -> R.drawable.icon_liquid_maps
        else -> null
    }
}
