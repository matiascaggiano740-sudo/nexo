package com.nexo.launcher

import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertTrue
import android.content.pm.PackageManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun longPressingHomeAppDoesNotEnterPageEdit() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val appPackage = composeRule.activity.packageManager
            .queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
            ?.activityInfo?.packageName
        assertTrue("The emulator must provide a launchable app for this gesture test", appPackage != null)

        val page = MutableList(28) { "-" }
        page[8] = appPackage!!
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithTag("home-page-app").get(0).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("finish-home-edit").assertCountEquals(0)
        assertTrue("The home app should remain visible", composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun swipeUpOnHomeOpensTheAppDrawer() {
        composeRule.onNodeWithTag("home-surface").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(1)
    }

    @Test
    fun swipeDownAtTopOfDrawerClosesIt() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onNodeWithTag("app-drawer-surface").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun emptyPageCanBeDeletedFromItsPreview() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("add-home-page").performClick()
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("delete-home-page-1", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("confirm-delete-home-page").assertCountEquals(0)
        val savedPages = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty()
        assertTrue("Deleting the empty page must persist only the remaining page; saved pages were: $savedPages", !savedPages.contains("~"))
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun longPressDragMovesAnAppIntoAnEmptyHomeCellAndPersistsIt() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val appPackage = composeRule.activity.packageManager
            .queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
            ?.activityInfo?.packageName
        assertTrue("The emulator must provide a launchable app for drag test", appPackage != null)
        val page = MutableList(28) { "-" }.apply { this[8] = appPackage!! }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .putInt("current_page", 0)
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty()
        }
        val source = composeRule.onNodeWithTag("home-page-app").fetchSemanticsNode().boundsInRoot
        val targetNode = composeRule.onNodeWithTag("home-cell-9", useUnmergedTree = true)
        val target = targetNode.fetchSemanticsNode().boundsInRoot.center
        targetNode.assertExists()
        composeRule.onNodeWithTag("home-page-app").performTouchInput {
            down(source.center - source.topLeft)
            advanceEventTime(700)
            moveTo(target - source.topLeft, 500)
            advanceEventTime(100)
            up()
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "")
                ?.split("|")?.getOrNull(9) == appPackage
        }
        val saved = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("|")
        assertEquals("The destination cell must contain the moved app", appPackage, saved[9])
        assertEquals("The source cell must become empty", "-", saved[8])
    }

    @Test
    fun populatedPageDeletionRequiresConfirmation() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchablePackages = composeRule.activity.packageManager
            .queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != composeRule.activity.packageName }
            .distinct()
        assertTrue("The emulator must provide two launchable apps for page preservation test", launchablePackages.size >= 2)
        val firstPage = MutableList(28) { "-" }.apply { this[8] = launchablePackages[0] }
        val preservedPage = MutableList(28) { "-" }.apply { this[8] = launchablePackages[1] }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", listOf(firstPage, preservedPage).joinToString("~") { it.joinToString("|") })
            .putString("packages", firstPage.joinToString("|"))
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("delete-home-page-0", useUnmergedTree = true).performClick()
        composeRule.onAllNodesWithTag("confirm-delete-home-page").assertCountEquals(1)
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("cancel-delete-home-page").performClick()
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("delete-home-page-0", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("confirm-delete-home-page").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag("home-page-app").assertCountEquals(1)
        val savedPages = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty()
        assertTrue("Deleting a populated page should persist the remaining page", !savedPages.contains("~"))
        assertTrue("Apps on pages not deleted must be preserved", savedPages.contains(launchablePackages[1]))
    }

    @Test
    fun deletingPageWithWidgetConfirmsAndRemovesItsPlacement() {
        val first = MutableList(28) { "-" }
        val second = MutableList(28) { "-" }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", listOf(first, second).joinToString("~") { it.joinToString("|") })
            .putString("packages", first.joinToString("|"))
            .apply()
        composeRule.activityRule.scenario.recreate()
        val allocatedWidgetId = java.util.concurrent.atomic.AtomicInteger(-1)
        val preservedWidgetId = java.util.concurrent.atomic.AtomicInteger(-1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try {
            composeRule.activityRule.scenario.onActivity { activity ->
                val widgetManager = android.appwidget.AppWidgetManager.getInstance(activity)
                val provider = widgetManager.installedProviders.firstOrNull {
                    it.provider.className == TestWidgetProvider::class.java.name
                }
                assertTrue("The instrumentation APK must install the test widget provider", provider != null)
                val host = android.appwidget.AppWidgetHost(activity, 0x4E45584F)
                val survivorId = host.allocateAppWidgetId()
                val id = host.allocateAppWidgetId()
                preservedWidgetId.set(survivorId)
                allocatedWidgetId.set(id)
                assertTrue("Android must bind the first test ID to a real widget provider", widgetManager.bindAppWidgetIdIfAllowed(survivorId, provider!!.provider))
                assertTrue("Android must bind the page-2 test ID to a real widget provider", widgetManager.bindAppWidgetIdIfAllowed(id, provider.provider))
                assertEquals("Android must report the bound provider", provider.provider, widgetManager.getAppWidgetInfo(id)?.provider)
                activity.hostedWidgetIds.add(survivorId)
                activity.hostedWidgetPages[survivorId] = 0
                activity.hostedWidgetIds.add(id)
                activity.hostedWidgetPages[id] = 1
                assertEquals("Test widget must be assigned to page 2", 1, activity.hostedWidgetPages[id])
                assertTrue("NEXO must create an AppWidgetHostView for each bound widget", activity.createWidgetHostView(id) != null && activity.createWidgetHostView(survivorId) != null)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("page-preview-1-widget-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("delete-home-page-1", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("delete-home-page-1", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("confirm-delete-home-page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithTag("confirm-delete-home-page").assertCountEquals(1)
        composeRule.onNodeWithTag("confirm-delete-home-page").performClick()
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertTrue("Removed page widget must no longer be hosted", allocatedWidgetId.get() !in activity.hostedWidgetIds)
            assertTrue("Removed page widget must no longer have a page mapping", allocatedWidgetId.get() !in activity.hostedWidgetPages)
            assertTrue("Android must release the deleted widget ID", android.appwidget.AppWidgetManager.getInstance(activity).getAppWidgetInfo(allocatedWidgetId.get()) == null)
            assertTrue("A widget on another page must survive deletion", preservedWidgetId.get() in activity.hostedWidgetIds)
            assertEquals("Widgets on later pages must be reindexed after deletion", 0, activity.hostedWidgetPages[preservedWidgetId.get()])
        }
            composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(0)
        } finally {
            val ids = listOf(allocatedWidgetId.get(), preservedWidgetId.get()).filter { it >= 0 }
            if (ids.isNotEmpty()) composeRule.activityRule.scenario.onActivity { activity ->
                val host = android.appwidget.AppWidgetHost(activity, 0x4E45584F)
                ids.forEach(host::deleteAppWidgetId)
            }
        }
    }

    @Test
    fun appDrawerOpensAndClosesFromExplicitControls() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(1)
        composeRule.onNodeWithTag("close-app-drawer").performClick()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(0)
    }

    @Test
    fun newPageStaysEmptyAndPagesCanBeSelectedIndependently() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("add-home-page").performClick()
        composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("page-preview-0", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("page-preview-1", useUnmergedTree = true).performClick()
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
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getInt("current_page", -1) == 1
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-page-indicator-selected-1").fetchSemanticsNodes().isNotEmpty()
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
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun updateCheckerIsReachableFromNexoSettings() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("home-more-actions").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("menu-open-nexo-settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("menu-open-nexo-settings").performClick()
        composeRule.onNodeWithTag("settings-updates").performClick()
        composeRule.onAllNodesWithText("Actualizaciones de NEXO").assertCountEquals(1)
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
