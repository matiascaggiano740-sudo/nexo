package com.nexo.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconThemesTest {
    @Test
    fun crayonThemeUsesOnlyCrayonArtwork() {
        assertEquals(R.drawable.icon_crayon_whatsapp, themedIconResource(IconTheme.CRAYON, "com.whatsapp"))
        assertEquals(R.drawable.icon_crayon_spotify, themedIconResource(IconTheme.CRAYON, "com.spotify.music"))
        assertEquals(R.drawable.icon_crayon_settings, themedIconResource(IconTheme.CRAYON, "com.android.settings"))
        assertEquals(R.drawable.icon_crayon_weather, themedIconResource(IconTheme.CRAYON, "com.miui.weather2"))
        assertEquals(R.drawable.icon_crayon_message_bubble, themedIconResource(IconTheme.CRAYON, "com.google.android.apps.messaging"))
        assertNull(themedIconResource(IconTheme.CRAYON, "com.android.chrome"))
    }

    @Test
    fun liquidGlassThemeUsesLiquidArtworkAndKeepsOriginalFallbacks() {
        assertEquals(R.drawable.icon_liquid_chrome, themedIconResource(IconTheme.LIQUID_GLASS, "com.android.chrome"))
        assertEquals(R.drawable.icon_liquid_instagram, themedIconResource(IconTheme.LIQUID_GLASS, "com.instagram.android"))
        assertEquals(R.drawable.icon_liquid_maps, themedIconResource(IconTheme.LIQUID_GLASS, "com.google.android.apps.maps"))
        assertNull(themedIconResource(IconTheme.LIQUID_GLASS, "com.unknown.app"))
        assertNull(themedIconResource(IconTheme.LIQUID_GLASS, "com.twitter.android"))
        assertNull(themedIconResource(IconTheme.LIQUID_GLASS, "com.twitter.android"))
        assertNull(themedIconResource(IconTheme.ORIGINAL, "com.android.chrome"))
    }

    @Test
    fun everyDrivePackImageIsBundled() {
        // Crayon has a duplicate copy of the same phone image in Drive; Liquid has one copied asset
        // named for both CapCut and X. These totals count the packaged resource files, not distinct pixels.
        assertEquals(18, bundledIconAssets(IconTheme.CRAYON).size)
        assertEquals(25, bundledIconAssets(IconTheme.LIQUID_GLASS).size)
        assertEquals(0, bundledIconAssets(IconTheme.ORIGINAL).size)
    }

    @Test
    fun mappedArtworkBelongsToTheSelectedPack() {
        val crayon = bundledIconAssets(IconTheme.CRAYON).toSet()
        val liquid = bundledIconAssets(IconTheme.LIQUID_GLASS).toSet()
        listOf("com.whatsapp", "com.spotify.music", "com.google.android.calendar", "com.android.settings").forEach { pkg ->
            assertEquals(true, themedIconResource(IconTheme.CRAYON, pkg)?.let(crayon::contains) == true)
        }
        listOf("com.android.chrome", "com.google.android.apps.maps", "com.google.android.gm", "com.netflix.mediaclient").forEach { pkg ->
            assertEquals(true, themedIconResource(IconTheme.LIQUID_GLASS, pkg)?.let(liquid::contains) == true)
        }
    }
}
