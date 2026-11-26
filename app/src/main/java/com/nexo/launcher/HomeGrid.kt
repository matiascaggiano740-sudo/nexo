package com.nexo.launcher

/**
 * Stable 4x7 home-page coordinates used by the launcher.
 * Cells 0..7 are the clock/weather header. Page 1 cells 20..27 hold the quote card.
 * Native widgets reserve complete rows immediately below the header.
 */
internal data class HomeIconSizing(val iconSizeDp: Float, val showLabel: Boolean)

/** Fit the icon and optional label within a measured grid cell without drawing into its neighbor. */
internal fun homeIconSizing(cellHeightDp: Float): HomeIconSizing {
    val height = cellHeightDp.coerceAtLeast(0f)
    val showLabel = height >= 72f
    val textAndGap = if (showLabel) 24f else 8f
    val maximumIcon = (height - textAndGap).coerceAtLeast(0f)
    val icon = (height * 0.54f).coerceAtMost(maximumIcon).coerceAtMost(56f)
        .coerceAtLeast(minOf(24f, maximumIcon))
    return HomeIconSizing(icon, showLabel)
}

internal const val HOME_GRID_GAP_DP = 8f

/** Derive widget row occupancy from the live measured launcher grid, never an assumed 100dp cell. */
internal fun homeGridCellHeightDp(availableHeightDp: Float, firstPage: Boolean, gapDp: Float = HOME_GRID_GAP_DP): Float {
    val lineGaps = if (firstPage) 4 else 5
    return ((availableHeightDp.coerceAtLeast(0f) - gapDp.coerceAtLeast(0f) * lineGaps) / HomeGrid.ROW_COUNT)
        .coerceAtLeast(1f)
}

internal fun widgetRowsForHeight(heightDp: Int, cellHeightDp: Float, gapDp: Float = HOME_GRID_GAP_DP): Int {
    val cell = cellHeightDp.coerceAtLeast(1f)
    val gap = gapDp.coerceAtLeast(0f)
    val wanted = heightDp.coerceAtLeast(1).toFloat()
    return kotlin.math.ceil((wanted + gap) / (cell + gap)).toInt().coerceAtLeast(1)
}

internal fun widgetSpanHeightDp(rows: Int, cellHeightDp: Float, gapDp: Float = HOME_GRID_GAP_DP): Float {
    val count = rows.coerceAtLeast(1)
    return cellHeightDp.coerceAtLeast(1f) * count + gapDp.coerceAtLeast(0f) * (count - 1)
}

internal object HomeGrid {
    const val COLUMN_COUNT = 4
    const val ROW_COUNT = 7
    const val CELL_COUNT = COLUMN_COUNT * ROW_COUNT
    const val HEADER_ROWS = 2
    const val HEADER_CELLS = HEADER_ROWS * COLUMN_COUNT
    const val QUOTE_ROWS = 2
    const val QUOTE_CELLS = QUOTE_ROWS * COLUMN_COUNT

    /**
     * Normalize legacy pages into the current cell map, preserving order, removing duplicate
     * app/folder entries across pages, and carrying overflow forward without dropping content.
     */
    fun normalize(
        pages: List<List<String>>,
        widgetRowsByPage: Map<Int, Int>,
        defaultPackages: List<String>,
        quoteVisible: Boolean = true,
    ): List<List<String>> {
        val sourcePages = pages.ifEmpty { listOf(emptyList()) }
        val normalizedPages = mutableListOf<List<String>>()
        val seenTokens = mutableSetOf<String>()
        var carry = emptyList<String>()
        var pageIndex = 0

        while (pageIndex < sourcePages.size || carry.isNotEmpty()) {
            val savedPage = sourcePages.getOrNull(pageIndex).orEmpty()
            val source = if (pageIndex == 0 && savedPage.isEmpty()) defaultPackages else savedPage
            val widgetRows = (widgetRowsByPage[pageIndex] ?: 0).coerceAtLeast(0)
            val firstAvailable = (HEADER_CELLS + widgetRows * COLUMN_COUNT).coerceIn(0, CELL_COUNT)
            val reservedQuoteCells = if (pageIndex == 0 && quoteVisible) QUOTE_CELLS else 0
            val endAvailable = (CELL_COUNT - reservedQuoteCells).coerceAtLeast(firstAvailable)
            val slots = MutableList(CELL_COUNT) { "-" }

            val uniqueSource = source.mapIndexed { slot, token ->
                if (token.isNotBlank() && token != "-" && !seenTokens.add(token)) slot to "-"
                else slot to token
            }
            val tokens = uniqueSource.map { it.second }.filter { it.isNotBlank() && it != "-" }
            val hasReservedCellConflict = uniqueSource.any { (slot, token) ->
                token.isNotBlank() && token != "-" && slot !in firstAvailable until endAvailable
            }
            val remaining = mutableListOf<String>()

            if (carry.isNotEmpty() || hasReservedCellConflict) {
                (carry + tokens).forEach { token ->
                    val free = (firstAvailable until endAvailable).firstOrNull { slots[it] == "-" }
                    if (free == null) remaining += token else slots[free] = token
                }
            } else {
                uniqueSource.forEach { (slot, token) ->
                    if (slot in 0 until CELL_COUNT && token.isNotBlank() && token != "-") slots[slot] = token
                }
            }

            carry = remaining
            normalizedPages += slots
            pageIndex++
        }
        return normalizedPages
    }
}
