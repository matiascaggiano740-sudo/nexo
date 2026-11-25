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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.doubleClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawerSearchFiltersAsYouTypeAndKeepsPartialRowsLeftAligned() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val target = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
        assertTrue("The emulator must expose at least one launchable app", target != null)
        val appPackage = target!!.activityInfo.packageName
        val appLabel = target.loadLabel(composeRule.activity.packageManager).toString()
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onNodeWithText("Buscar aplicaciones").performTextInput(appLabel)
        composeRule.waitForIdle()
        val result = composeRule.onNodeWithTag("drawer-app-$appPackage").fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag("app-drawer-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Filtered app should start at the first (left) grid column: app=$result list=$list", kotlin.math.abs(result.left - list.left) < 3f)
        composeRule.onAllNodesWithTag("drawer-app-$appPackage").assertCountEquals(1)
        composeRule.onAllNodesWithTag("drawer-app-menu-$appPackage").assertCountEquals(0)
    }

    @Test
    fun homeEditSettingsNoLongerOfferDoubleTapLock() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("edit-widgets").assertExists()
        composeRule.onNodeWithTag("edit-wallpaper").assertExists()
        composeRule.onNodeWithTag("add-home-page").assertExists()
        composeRule.onNodeWithTag("edit-folder").assertExists()
        composeRule.onNodeWithTag("edit-pages").assertExists()
        composeRule.onNodeWithTag("icon-theme-open").assertExists()
        composeRule.onAllNodesWithTag("edit-settings").assertCountEquals(0)
        composeRule.onNodeWithTag("finish-home-edit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open-nexo-settings").performClick()
        composeRule.onAllNodesWithTag("settings-lock").assertCountEquals(0)
        composeRule.onNodeWithTag("settings-default-home").assertExists()
        composeRule.onNodeWithTag("settings-updates").assertExists()
        composeRule.onNodeWithTag("settings-backup").assertExists()
    }

    @Test
    fun doubleTapEmptyHomeCellDoesNotTriggerLockFeature() {
        val blankPage = List(28) { "-" }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", blankPage.joinToString("|"))
            .putString("packages", blankPage.joinToString("|"))
            .putInt("current_page", 0)
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-cell-8", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home-cell-8", useUnmergedTree = true).performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Doble toque para bloquear").assertCountEquals(0)
        composeRule.onAllNodesWithText("Abrir Accesibilidad").assertCountEquals(0)
        val packageInfo = composeRule.activity.packageManager.getPackageInfo(
            composeRule.activity.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        )
        assertTrue(packageInfo.services.orEmpty().none { it.name.endsWith("NexoLockAccessibilityService") })
        assertTrue(packageInfo.receivers.orEmpty().none { it.name.endsWith("NexoDeviceAdminReceiver") })
    }

    @Test
    fun widgetPickerShowsVisualPreviewCards() {
        composeRule.runOnUiThread { composeRule.activity.launchWidgetPicker(0, 0) }
        composeRule.waitUntil(5_000) { composeRule.activity.availableWidgetProviders.isNotEmpty() }
        val provider = composeRule.activity.availableWidgetProviders.firstOrNull()
        assertTrue("The emulator must expose at least one widget provider to the real picker", provider != null)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("widget-provider-${provider!!.provider.packageName}").assertExists()
    }

    @Test
    fun appsCanBeRemovedFromHomeAndCreatedPagesWithoutLosingOtherPages() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val appPackage = composeRule.activity.packageManager
            .queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
            ?.activityInfo?.packageName
        assertTrue("The emulator must provide a launchable app for the remove test", appPackage != null)
        val packageName = appPackage ?: error("No launchable test app available")

        val homePage = MutableList(28) { "-" }.apply { this[8] = packageName }
        val createdPage = MutableList(28) { "-" }.apply { this[8] = packageName }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", listOf(homePage, createdPage).joinToString("~") { it.joinToString("|") })
            .putString("packages", homePage.joinToString("|"))
            .putInt("current_page", 0)
            .commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("remove-home-app-page-1-$packageName").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("remove-home-app-page-1-$packageName").performClick()
        composeRule.waitForIdle()
        var pages = composeRule.activity.getSharedPreferences("nexo_home", 0)
            .getString("pages", "").orEmpty().split("~").map { it.split("|") }
        assertEquals("Removing an app from a created page must clear only that slot", "-", pages[1][8])
        assertEquals("Removing from another page must preserve the Home app", packageName, pages[0][8])

        composeRule.onNodeWithTag("remove-home-app-page-0-$packageName").performClick()
        composeRule.waitForIdle()
        pages = composeRule.activity.getSharedPreferences("nexo_home", 0)
            .getString("pages", "").orEmpty().split("~").map { it.split("|") }
        assertEquals("The Home app must be removed and saved", "-", pages[0][8])
        assertEquals("The other page must stay intact", "-", pages[1][8])
        assertEquals("Removing an app must not switch pages", 0,
            composeRule.activity.getSharedPreferences("nexo_home", 0).getInt("current_page", -1))
    }

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
        composeRule.onNodeWithTag("home-edit-pages-row", useUnmergedTree = true).performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("delete-home-page-1", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("page-preview-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
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
            val origin = source.center - source.topLeft
            val destination = target - source.topLeft
            for (step in 1..12) {
                val fraction = step / 12f
                moveTo(origin + (destination - origin) * fraction, 35)
            }
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
    fun longPressDragMovesAnAppAcrossPagesAndPersistsSelection() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val appPackage = composeRule.activity.packageManager
            .queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
            ?.activityInfo?.packageName
        assertTrue("The emulator must provide a launchable app for page drag test", appPackage != null)
        val firstPage = MutableList(28) { "-" }.apply { this[8] = appPackage!! }
        val secondPage = MutableList(28) { "-" }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", listOf(firstPage, secondPage).joinToString("~") { it.joinToString("|") })
            .putString("packages", firstPage.joinToString("|"))
            .putInt("current_page", 0)
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty() }
        val source = composeRule.onNodeWithTag("home-page-app").fetchSemanticsNode().boundsInRoot
        val targetNode = composeRule.onNodeWithTag("home-page-indicator-1", useUnmergedTree = true)
        val target = targetNode.fetchSemanticsNode().boundsInRoot.center
        targetNode.assertExists()
        composeRule.onNodeWithTag("home-page-app").performTouchInput {
            val origin = source.center - source.topLeft
            val destination = target - source.topLeft
            down(origin)
            advanceEventTime(700)
            for (step in 1..12) {
                val fraction = step / 12f
                moveTo(origin + (destination - origin) * fraction, 35)
            }
            up()
        }
        composeRule.waitUntil(5_000) {
            val saved = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("~")
            saved.getOrNull(1)?.contains(appPackage!!) == true && composeRule.activity.getSharedPreferences("nexo_home", 0).getInt("current_page", -1) == 1
        }
        val pages = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("~")
        val movedPackage = appPackage!!
        assertTrue("Moved app must no longer be on page 1", movedPackage !in pages[0])
        assertTrue("Moved app must be on page 2", movedPackage in pages[1])
    }

    @Test
    fun longPressDragMovesAnAppFromHomeToDockAndPersistsBothLocations() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val appPackage = composeRule.activity.packageManager
            .queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
            ?.activityInfo?.packageName
        assertTrue("The emulator must provide a launchable app for dock drag test", appPackage != null)
        val page = MutableList(28) { "-" }.apply { this[8] = appPackage!! }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .putString("dock_packages", "-|-|-|-")
            .putInt("current_page", 0)
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty() }
        val source = composeRule.onNodeWithTag("home-page-app").fetchSemanticsNode().boundsInRoot
        val targetNode = composeRule.onNodeWithTag("dock-cell-0", useUnmergedTree = true)
        val target = targetNode.fetchSemanticsNode().boundsInRoot.center
        targetNode.assertExists()
        composeRule.onNodeWithTag("home-page-app").performTouchInput {
            val origin = source.center - source.topLeft
            val destination = target - source.topLeft
            down(origin)
            advanceEventTime(700)
            for (step in 1..12) {
                val fraction = step / 12f
                moveTo(origin + (destination - origin) * fraction, 35)
            }
            up()
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("dock_packages", "")
                ?.split("|")?.getOrNull(0) == appPackage
        }
        val prefs = composeRule.activity.getSharedPreferences("nexo_home", 0)
        assertEquals("Dragged app must occupy dock slot 1", appPackage, prefs.getString("dock_packages", "")?.split("|")?.getOrNull(0))
        assertEquals("Moving an app to the dock must clear its home cell", "-", prefs.getString("pages", "")?.split("|")?.getOrNull(8))
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
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals("The page to delete contains one genuine provider-bound widget", 1,
                activity.hostedWidgetIds.count { id -> activity.hostedWidgetPages[id] == 1 && android.appwidget.AppWidgetManager.getInstance(activity).getAppWidgetInfo(id) != null })
        }
        composeRule.onNodeWithTag("home-edit-pages-row", useUnmergedTree = true).performTouchInput { swipeLeft() }
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
        composeRule.onNodeWithTag("open-nexo-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-updates").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Actualizaciones de NEXO").fetchSemanticsNodes().size == 1
        }
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
