package com.nexo.launcher

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import android.util.Log
import org.junit.Assert.assertTrue
import android.content.pm.PackageManager
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

    private fun logDrawerDiagnosticSnapshot(label: String) {
        val tag = "NEXO_DRAWER_DIAG"
        Log.i(tag, "SNAPSHOT_BEGIN label=$label")
        runCatching {
            composeRule.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                Log.i(tag, "ACTIVITY label=$label class=${activity.javaClass.name} lifecycle=${activity.lifecycle.currentState} finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} windowFocus=${activity.hasWindowFocus()} decorFocus=${decor.hasWindowFocus()} decorFocused=${decor.isFocused} attached=${decor.isAttachedToWindow} shown=${decor.isShown}")
            }
        }.onFailure { Log.e(tag, "ACTIVITY_SNAPSHOT_FAILED label=$label", it) }
        runCatching {
            composeRule.onRoot(useUnmergedTree = true).printToLog(tag)
        }.onFailure { Log.e(tag, "SEMANTICS_TREE_UNAVAILABLE label=$label", it) }
        listOf("open-app-drawer", "close-app-drawer").forEach { nodeTag ->
            runCatching {
                val nodes = composeRule.onAllNodesWithTag(nodeTag, useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootExpected = false)
                Log.i(tag, "TAG_NODES label=$label tag=$nodeTag count=${nodes.size}")
                nodes.forEachIndexed { index, node ->
                    Log.i(tag, "TAG_NODE label=$label tag=$nodeTag index=$index boundsInRoot=${node.boundsInRoot} boundsInWindow=${node.boundsInWindow} config=${node.config}")
                }
            }.onFailure { Log.e(tag, "TAG_NODE_SNAPSHOT_FAILED label=$label tag=$nodeTag", it) }
        }
        Log.i(tag, "SNAPSHOT_END label=$label")
    }

    @Test
    fun appDrawerOpensAndClosesFromExplicitControls() {
        logDrawerDiagnosticSnapshot("explicit-before-open-click")
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        logDrawerDiagnosticSnapshot("explicit-after-open-click")
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(1)
        composeRule.onNodeWithTag("close-app-drawer").performClick()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(0)
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
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("home-page-indicator-selected-0").assertCountEquals(1)
        composeRule.onNodeWithTag("home-surface").performTouchInput { swipeLeft() }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("home-page-indicator-selected-1").assertCountEquals(1)
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
        logDrawerDiagnosticSnapshot("back-before-open-click")
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        logDrawerDiagnosticSnapshot("back-after-open-click")
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(1)
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun androidCanResolveNexoAsAHomeActivityWithoutChangingTheDefault() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val handlers = composeRule.activity.packageManager.queryIntentActivities(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(handlers.any { it.activityInfo.packageName == composeRule.activity.packageName })
    }
}
