package com.nexo.launcher

import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.fetchSemanticsNodes
import android.view.WindowManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appDrawerOpensAndClosesFromExplicitControls() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(1)
        composeRule.onNodeWithTag("close-app-drawer").performClick()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun newPageStaysEmptyAndPagesCanBeSelectedIndependently() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("add-home-page").performClick()
        composeRule.onAllNodesWithTag("page-preview-1").assertCountEquals(1)
        composeRule.onNodeWithTag("page-preview-0").performClick()
        composeRule.onNodeWithTag("page-preview-1").performClick()
        composeRule.onNodeWithTag("finish-home-edit").performClick()

        composeRule.onAllNodesWithTag("home-page-indicator-selected-1").assertCountEquals(1)
        composeRule.onAllNodesWithTag("home-page-app").assertCountEquals(0)
        composeRule.onNodeWithTag("home-surface").performTouchInput { swipeRight() }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithTag("home-page-indicator-selected-0").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("home-surface").performTouchInput { swipeLeft() }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithTag("home-page-indicator-selected-1").fetchSemanticsNodes().size == 1
        }
        composeRule.onAllNodesWithTag("home-page-app").assertCountEquals(0)
    }

    @Test
    fun iconPackSelectionIsReachableFromHomeEdit() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        val previousTheme = savedIconTheme(composeRule.activity)
        composeRule.onNodeWithTag("icon-theme-open").performClick()
        composeRule.onNodeWithTag("icon-theme-option-liquid").performClick()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals(IconTheme.LIQUID_GLASS, savedIconTheme(activity))
            activity.getSharedPreferences("nexo_icon_settings", 0).edit()
                .putString("theme", previousTheme.key).apply()
        }
    }

    @Test
    fun systemBackClosesTheAppDrawer() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(1)
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithTag("close-app-drawer").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun homeAppsUseNonOverlappingMeasuredCells() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().size >= 2
        }
        val nodes = composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes()
        val rects = nodes.map { it.boundsInRoot }
        rects.forEachIndexed { i, a ->
            rects.drop(i + 1).forEach { b ->
                val horizontalOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val verticalOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertFalse("Home app cells overlap: $a and $b", horizontalOverlap > 0f && verticalOverlap > 0f)
            }
        }
    }

    @Test
    fun systemWallpaperWindowFlagIsOnlyEnabledForHomeEntry() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            activity.onNewIntent(homeIntent)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            activity.onNewIntent(launcherIntent)
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
        }
    }
}
