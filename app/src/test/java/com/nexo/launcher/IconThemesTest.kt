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
        assertNull(themedIconResource(IconTheme.CRAYON, "com.android.chrome"))
    }

    @Test
    fun liquidGlassThemeUsesLiquidArtworkAndKeepsOriginalFallbacks() {
        assertEquals(R.drawable.icon_liquid_chrome, themedIconResource(IconTheme.LIQUID_GLASS, "com.android.chrome"))
        assertEquals(R.drawable.icon_liquid_instagram, themedIconResource(IconTheme.LIQUID_GLASS, "com.instagram.android"))
        assertEquals(R.drawable.icon_liquid_maps, themedIconResource(IconTheme.LIQUID_GLASS, "com.google.android.apps.maps"))
        assertNull(themedIconResource(IconTheme.LIQUID_GLASS, "com.unknown.app"))
        assertNull(themedIconResource(IconTheme.ORIGINAL, "com.android.chrome"))
    }
}
