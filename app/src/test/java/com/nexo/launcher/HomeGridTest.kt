package com.nexo.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {
    private fun visibleTokens(pages: List<List<String>>) = pages.flatten().filter { it != "-" }

    @Test
    fun removesDuplicateAppsAlreadyPresentOnMultipleLegacyPages() {
        val defaults = listOf("whatsapp", "spotify", "instagram", "maps", "gallery", "chrome")
        val pages = listOf(defaults + List(22) { "-" }, defaults.take(4) + List(24) { "-" })

        val result = HomeGrid.normalize(pages, emptyMap(), defaults)

        assertEquals(defaults, visibleTokens(result))
        assertEquals(List(HomeGrid.CELL_COUNT) { "-" }, result[1])
    }

    @Test
    fun onlySeedsDefaultAppsOnFirstPageAndLeavesNewPageEmpty() {
        val defaults = listOf("whatsapp", "spotify", "maps", "gallery", "chrome")

        val result = HomeGrid.normalize(listOf(emptyList(), emptyList()), emptyMap(), defaults)

        assertEquals(defaults, result[0].drop(HomeGrid.HEADER_CELLS).take(defaults.size))
        assertEquals(List(HomeGrid.CELL_COUNT) { "-" }, result[1])
    }

    @Test
    fun carriesAllAppsForwardWhenFirstPageHasWidgetAndQuoteReservations() {
        val apps = (0 until HomeGrid.CELL_COUNT).map { "package.$it" }

        val result = HomeGrid.normalize(listOf(apps, emptyList()), mapOf(0 to 2), emptyList())

        assertEquals(apps, visibleTokens(result))
        assertEquals(3, result.size)
        assertEquals(apps.take(4), result[0].drop(16).take(4))
        assertEquals(apps.drop(4).take(20), result[1].drop(HomeGrid.HEADER_CELLS))
        assertEquals(apps.takeLast(4), result[2].drop(HomeGrid.HEADER_CELLS).take(4))
    }

    @Test
    fun keepsWidgetRowsAndAppEntriesOnTheirOwnPages() {
        val pages = listOf(
            List(8) { "-" } + listOf("first.a", "first.b") + List(18) { "-" },
            List(8) { "-" } + listOf("second.c") + List(19) { "-" },
        )

        val result = HomeGrid.normalize(pages, mapOf(1 to 1), emptyList())

        assertEquals("first.a", result[0][8])
        assertEquals("first.b", result[0][9])
        assertEquals("second.c", result[1][12])
        assertEquals("-", result[0][12])
    }

    @Test
    fun removesDuplicateFolderTokensWithoutDroppingOtherEntries() {
        val pages = listOf(
            listOf("folder:Media", "app.a"),
            listOf("folder:Media", "app.b"),
        )

        val result = HomeGrid.normalize(pages, emptyMap(), emptyList())

        assertEquals(listOf("folder:Media", "app.a", "app.b"), visibleTokens(result))
    }

    @Test
    fun normalizationIsIdempotentAndKeepsFixedPageDimensions() {
        val pages = listOf(listOf("app.a", "app.b"), emptyList())
        val once = HomeGrid.normalize(pages, emptyMap(), listOf("default.app"))
        val twice = HomeGrid.normalize(once, emptyMap(), listOf("default.app"))

        assertEquals(once, twice)
        assertTrue(once.all { it.size == HomeGrid.CELL_COUNT })
        assertTrue(once.all { it.size == HomeGrid.COLUMN_COUNT * HomeGrid.ROW_COUNT })
    }
}
