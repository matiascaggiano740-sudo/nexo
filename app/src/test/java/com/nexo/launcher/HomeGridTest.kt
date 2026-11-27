package com.nexo.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {
    private fun visibleTokens(pages: List<List<String>>) = pages.flatten().filter { it != "-" }

    @Test
    fun launcherGridRetainsTheApprovedFourBySevenGeometry() {
        assertEquals(4, HomeGrid.COLUMN_COUNT)
        assertEquals(7, HomeGrid.ROW_COUNT)
        assertEquals(28, HomeGrid.CELL_COUNT)
        assertEquals(HomeGrid.COLUMN_COUNT * HomeGrid.ROW_COUNT, HomeGrid.CELL_COUNT)
        val oneEmptyPage = HomeGrid.normalize(listOf(emptyList()), emptyMap(), emptyList())
        assertEquals(1, oneEmptyPage.size)
        assertEquals(List(28) { "-" }, oneEmptyPage.single())
    }

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
    fun onlyRemovesDuplicatesAcrossHomePagesAndKeepsDistinctApps() {
        val pages = listOf(
            List(8) { "-" } + listOf("app.chrome", "app.maps"),
            List(8) { "-" } + listOf("app.chrome", "app.photos", "app.maps"),
        )
        val result = HomeGrid.normalize(pages, emptyMap(), emptyList())
        assertEquals(listOf("app.chrome", "app.maps", "app.photos"), visibleTokens(result))
        assertTrue(result.all { it.size == HomeGrid.CELL_COUNT })
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

    @Test
    fun homeIconsAndLabelsFitTheirMeasuredCellsWithoutOverlappingNeighbors() {
        val compact = homeIconSizing(60f)
        assertTrue(!compact.showLabel)
        assertTrue(compact.iconSizeDp + 8f <= 60f)

        val regular = homeIconSizing(88f)
        val compactRegular = homeIconSizing(88f, 0f)
        assertTrue(regular.showLabel)
        assertTrue(regular.iconSizeDp > compactRegular.iconSizeDp)
        assertTrue(regular.labelFontSizeSp > compactRegular.labelFontSizeSp)
        assertTrue(regular.iconSizeDp + regular.labelTopGapDp + regular.labelFontSizeSp * 1.25f <= 88.01f)

        val tall = homeIconSizing(110f, 1f)
        assertTrue(tall.showLabel)
        assertEquals(68f, tall.iconSizeDp, 0.01f)
        assertTrue(tall.iconSizeDp + tall.labelTopGapDp + tall.labelFontSizeSp * 1.25f <= 110f)
    }

    @Test
    fun randomizedLayoutsPreserveEveryUniqueShortcutExactlyOnce() {
        val random = java.util.Random(20261123L)
        repeat(600) {
            val tokenPool = (0 until 45).map { "app.$it" } + (0 until 8).map { "folder:Folder$it" }
            val pages = List(random.nextInt(6)) {
                List(random.nextInt(38)) { index ->
                    when (random.nextInt(8)) {
                        0, 1 -> "-"
                        else -> tokenPool[random.nextInt(tokenPool.size)]
                    }
                }
            }
            val widgets = (0 until 8).mapNotNull { page ->
                if (random.nextBoolean()) page to random.nextInt(5) else null
            }.toMap()
            val defaults = listOf("default.a", "default.b")
            val source = pages.ifEmpty { listOf(emptyList()) }
            val savedTokens = source.flatMap { it.filter { token -> token.isNotBlank() && token != "-" } }
            val expected = (savedTokens + if (source.first().isEmpty()) defaults else emptyList()).toSet()
            val result = HomeGrid.normalize(pages, widgets, defaults)
            val actual = visibleTokens(result)
            assertEquals(expected, actual.toSet())
            assertEquals(actual.size, actual.toSet().size)
            assertTrue(result.all { it.size == HomeGrid.CELL_COUNT })
        }
    }

    @Test
    fun widgetRowsFollowMeasuredCellHeightInsteadOfFixedDpAssumptions() {
        assertEquals(1, widgetRowsForHeight(64, 64f))
        assertEquals(2, widgetRowsForHeight(65, 64f))
        assertEquals(2, widgetRowsForHeight(100, 64f))
        assertEquals(1, widgetRowsForHeight(80, 88f))
        assertEquals(3, widgetRowsForHeight(200, 72f))
        assertEquals(136f, widgetSpanHeightDp(2, 64f), 0.01f)
        assertEquals(316f, widgetSpanHeightDp(3, 100f, 8f), 0.01f)
    }

    @Test
    fun measuredGridRowsAndCollapsedFullSpanGapsFitTheViewport() {
        val pageWithQuote = homeGridCellHeightDp(500f, firstPage = true, gapDp = 8f)
        val pageWithoutQuote = homeGridCellHeightDp(500f, firstPage = false, gapDp = 8f)
        assertEquals(66.85714f, pageWithQuote, 0.01f)
        assertEquals(65.71429f, pageWithoutQuote, 0.01f)
        assertEquals(500f, pageWithQuote * 7 + 8f * 4, 0.01f)
        assertEquals(500f, pageWithoutQuote * 7 + 8f * 5, 0.01f)
    }
}
