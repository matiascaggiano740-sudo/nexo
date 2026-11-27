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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsActions
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
    fun iconSizeSettingsPreviewAndPersistTheSelectedScale() {
        val prefs = composeRule.activity.getSharedPreferences("nexo_home", 0)
        val original = prefs.getFloat("home_icon_scale", DEFAULT_HOME_ICON_SCALE)
        composeRule.onNodeWithTag("open-nexo-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-icon-size-slider").assertExists()
        composeRule.onNodeWithTag("home-icon-size-preview").assertExists()

        composeRule.onNodeWithTag("home-icon-size-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.waitForIdle()
        val smaller = composeRule.onNodeWithTag("home-icon-preview-app-0-image").fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag("home-icon-size-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeRule.waitForIdle()
        val larger = composeRule.onNodeWithTag("home-icon-preview-app-0-image").fetchSemanticsNode().boundsInRoot
        assertTrue("Preview icon should grow as the slider moves right: small=$smaller large=$larger", larger.width > smaller.width)
        assertTrue("Selected icon scale must be saved", prefs.getFloat("home_icon_scale", -1f) > 0.99f)

        composeRule.onNodeWithTag("home-icon-size-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(original) }
        composeRule.onNodeWithTag("close-nexo-settings").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun drawerSearchFiltersAsYouTypeAndKeepsPartialRowsLeftAligned() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val target = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
        assertTrue("The emulator must expose at least one launchable app", target != null)
        val appPackage = target!!.activityInfo.packageName
        val appLabel = target.loadLabel(composeRule.activity.packageManager).toString()
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.waitForIdle()
        val drawer = composeRule.onNodeWithTag("app-drawer-surface").fetchSemanticsNode().boundsInRoot
        val search = composeRule.onNodeWithTag("app-drawer-search-container").fetchSemanticsNode().boundsInRoot
        val listBounds = composeRule.onNodeWithTag("app-drawer-list").fetchSemanticsNode().boundsInRoot
        assertTrue("App drawer search should be fixed at the bottom", search.top >= listBounds.bottom - 2f)
        assertTrue("App drawer search should stay inside the drawer bottom edge", search.bottom <= drawer.bottom)
        assertTrue("App drawer should not advertise its app count", composeRule.onAllNodesWithText("disponibles", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("drawer-search-field").performTextInput(appLabel)
        composeRule.waitForIdle()
        val result = composeRule.onNodeWithTag("drawer-app-$appPackage").fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag("app-drawer-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Filtered app should start at the first (left) grid column: app=$result list=$list", kotlin.math.abs(result.left - list.left) < 3f)
        composeRule.onAllNodesWithTag("drawer-app-$appPackage").assertCountEquals(1)
        composeRule.onAllNodesWithTag("drawer-app-menu-$appPackage").assertCountEquals(0)
    }

    @Test
    fun drawerSearchStaysUsableWithImeAndBackClosesKeyboardBeforeDrawer() {
        val target = composeRule.activity.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != composeRule.activity.packageName }
        assertTrue("The emulator must provide a launchable app for the IME search test", target != null)
        val packageName = target!!.activityInfo.packageName
        val appLabel = target.loadLabel(composeRule.activity.packageManager).toString()
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onNodeWithTag("drawer-search-field").performClick()
        val decor = composeRule.activity.window.decorView
        composeRule.waitUntil(10_000) {
            android.os.Build.VERSION.SDK_INT < 30 || decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        }
        assertTrue("Android 15 emulator must expose visible IME insets", android.os.Build.VERSION.SDK_INT < 30 ||
            decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)

        composeRule.onNodeWithTag("drawer-search-field").performTextInput(appLabel)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("drawer-app-$packageName").fetchSemanticsNodes().isNotEmpty() }
        val searchBounds = composeRule.onNodeWithTag("app-drawer-search-container").fetchSemanticsNode().boundsInRoot
        val listBounds = composeRule.onNodeWithTag("app-drawer-list").fetchSemanticsNode().boundsInRoot
        val resultBounds = composeRule.onNodeWithTag("drawer-app-$packageName").fetchSemanticsNode().boundsInRoot
        val imeBottom = decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
        val imeTopInWindow = decor.height - imeBottom
        assertTrue("Search must remain above the IME: search=$searchBounds imeTop=$imeTopInWindow", searchBounds.bottom <= imeTopInWindow + 2)
        assertTrue("Results must remain visible above the IME: result=$resultBounds imeTop=$imeTopInWindow", resultBounds.bottom <= imeTopInWindow + 2)
        assertTrue("The list must retain usable height with the IME open: $listBounds", listBounds.height > 0f)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.waitUntil(10_000) {
            android.os.Build.VERSION.SDK_INT < 30 || decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false
        }
        composeRule.onNodeWithTag("app-drawer-surface").assertExists()
        composeRule.onNodeWithTag("drawer-app-$packageName").assertExists()

        composeRule.onNodeWithTag("drawer-search-field").performClick()
        composeRule.waitUntil(10_000) {
            android.os.Build.VERSION.SDK_INT < 30 || decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        }
        composeRule.onNodeWithTag("drawer-search-field").performTextClearance()
        composeRule.onNodeWithTag("app-drawer-list").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val scrolledSearchBounds = composeRule.onNodeWithTag("app-drawer-search-container").fetchSemanticsNode().boundsInRoot
        assertTrue("Scrolling results must not move the anchored search capsule", kotlin.math.abs(scrolledSearchBounds.top - searchBounds.top) < 2f)
        composeRule.onNodeWithTag("drawer-search-field").performTextInput("nexo-qa-query-with-no-match")
        composeRule.onNodeWithText("No se encontraron aplicaciones").assertExists()
        composeRule.onNodeWithTag("drawer-search-field").performImeAction()
        composeRule.waitUntil(10_000) {
            android.os.Build.VERSION.SDK_INT < 30 || decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false
        }
        composeRule.onNodeWithText("No se encontraron aplicaciones").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("app-drawer-surface").assertCountEquals(0)
    }

    @Test
    fun homeSearchIsCompactCenteredAboveTheDock() {
        val search = composeRule.onNodeWithTag("open-app-drawer").fetchSemanticsNode().boundsInRoot
        val dock = composeRule.onNodeWithTag("dock-cell-0").fetchSemanticsNode().boundsInRoot
        val home = composeRule.onNodeWithTag("home-surface").fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue("Home search should be a compact POCO-style pill, not a full-width field: $search", search.width <= 160f * density)
        assertTrue("Home search should be centered", kotlin.math.abs(search.center.x - home.center.x) < 2f * density)
        assertTrue("Home search should sit directly above the dock", search.bottom <= dock.top && dock.top - search.bottom < 24f * density)
        composeRule.onNodeWithText("Buscar").assertExists()
    }

    @Test
    fun folderPreviewUsesNineAlignedIconsAndFolderEditingHasNoStaticLabel() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it != composeRule.activity.packageName }.distinct().take(9)
        assertEquals("The emulator should expose nine launchable apps for a 3x3 folder preview", 9, packages.size)
        val prefs = composeRule.activity.getSharedPreferences("nexo_home", 0)
        val originalPages = prefs.getString("pages", null)
        val originalPackages = prefs.getString("packages", null)
        val originalFolders = prefs.getString("user_folders", null)
        val originalCurrentPage = prefs.getInt("current_page", 0)
        val page = MutableList(28) { "-" }.apply { this[8] = "folder:Social" }
        val quotedPackages = packages.joinToString(",") { "\"$it\"" }
        prefs.edit().putString("user_folders", "[{\"name\":\"Social\",\"packages\":[$quotedPackages]}]")
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .putInt("current_page", 0).commit()
        try {
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-folder-Social").fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("folder-preview-Social-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onAllNodesWithTag("folder-preview-Social-0", useUnmergedTree = true).assertCountEquals(1)
            composeRule.onAllNodesWithTag("folder-preview-Social-8", useUnmergedTree = true).assertCountEquals(1)
            val first = composeRule.onNodeWithTag("folder-preview-Social-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val second = composeRule.onNodeWithTag("folder-preview-Social-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val third = composeRule.onNodeWithTag("folder-preview-Social-2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Folder preview apps should remain clearly visible at the home icon size", first.width > 8f * composeRule.activity.resources.displayMetrics.density)
            assertTrue("Folder preview icons should be evenly spaced", kotlin.math.abs((second.center.x - first.center.x) - (third.center.x - second.center.x)) < 2f)
            composeRule.onNodeWithTag("home-folder-Social").performClick()
            composeRule.onAllNodesWithText("Editar").assertCountEquals(0)
            composeRule.onNodeWithTag("folder-panel-title-Social").performTouchInput { longClick() }
            composeRule.onNodeWithText("Editar carpeta").assertExists()
            composeRule.onNodeWithText("Cancelar").performClick()
        } finally {
            val edit = prefs.edit()
            if (originalPages == null) edit.remove("pages") else edit.putString("pages", originalPages)
            if (originalPackages == null) edit.remove("packages") else edit.putString("packages", originalPackages)
            if (originalFolders == null) edit.remove("user_folders") else edit.putString("user_folders", originalFolders)
            edit.putInt("current_page", originalCurrentPage).commit()
            composeRule.activityRule.scenario.recreate()
        }
    }

    @Test
    fun folderCreatedInHomeEditorCanBeDraggedSwappedMovedAcrossPagesAndRestored() {
        val prefs = composeRule.activity.getSharedPreferences("nexo_home", 0)
        val originalPages = prefs.getString("pages", null)
        val originalPackages = prefs.getString("packages", null)
        val originalFolders = prefs.getString("user_folders", null)
        val originalDock = prefs.getString("dock_packages", null)
        val originalPage = prefs.getInt("current_page", 0)
        val candidates = composeRule.activity.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == composeRule.activity.packageName) null
                else packageName to info.loadLabel(composeRule.activity.packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        assertTrue("Need three launchable apps for folder drag/swap test", candidates.size >= 3)
        val (firstPackage, firstLabel) = candidates[0]
        val (secondPackage, secondLabel) = candidates[1]
        val (swapPackage, _) = candidates[2]
        val firstPage = MutableList(28) { "-" }.apply { this[12] = swapPackage }
        val secondPage = MutableList(28) { "-" }
        prefs.edit()
            .putString("user_folders", "[]")
            .putString("pages", listOf(firstPage, secondPage).joinToString("~") { it.joinToString("|") })
            .putString("packages", firstPage.joinToString("|"))
            .putString("dock_packages", "-|-|-|-")
            .putInt("current_page", 0).commit()
        try {
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-clock").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
            composeRule.onNodeWithTag("edit-configure").performClick()
            composeRule.onNodeWithTag("configure-home-folder").performClick()
            composeRule.onNodeWithTag("folder-name-field").performTextInput("Audit")
            composeRule.onNodeWithTag("folder-app-select-$firstPackage").performClick()
            composeRule.onNodeWithTag("folder-app-select-$secondPackage").performClick()
            composeRule.onNodeWithText("Guardar").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("home-folder-Audit").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("home-folder-Audit").performTouchInput { longClick() }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("home-edit-overlay").assertCountEquals(0)
            assertTrue("A long press without movement must not relocate the folder", prefs.getString("pages", "").orEmpty().contains("folder:Audit"))

            fun dragFolderTo(targetTag: String) {
                val sourceNode = composeRule.onNodeWithTag("home-folder-Audit")
                val source = sourceNode.fetchSemanticsNode().boundsInRoot
                val targetNode = composeRule.onNodeWithTag(targetTag, useUnmergedTree = true)
                val target = targetNode.fetchSemanticsNode().boundsInRoot.center
                sourceNode.performTouchInput {
                    val origin = source.center - source.topLeft
                    val destination = target - source.topLeft
                    down(origin)
                    advanceEventTime(700)
                    for (step in 1..12) {
                        val fraction = step / 12f
                        moveTo(origin + (destination - origin) * fraction, 35)
                    }
                    advanceEventTime(80)
                    up()
                }
                composeRule.waitForIdle()
            }

            dragFolderTo("home-cell-11")
            composeRule.waitUntil(5_000) {
                prefs.getString("pages", "").orEmpty().split("~").firstOrNull()?.split("|")?.getOrNull(11) == "folder:Audit"
            }
            var savedPages = prefs.getString("pages", "").orEmpty().split("~").map { it.split("|") }
            assertEquals("Folder should leave its source cell", "-", savedPages[0][8])
            assertEquals("Folder should not be duplicated", 1, savedPages.sumOf { page -> page.count { it == "folder:Audit" } })

            dragFolderTo("home-cell-12")
            composeRule.waitUntil(5_000) {
                val page = prefs.getString("pages", "").orEmpty().split("~").firstOrNull()?.split("|").orEmpty()
                page.getOrNull(12) == "folder:Audit" && page.getOrNull(11) == swapPackage
            }
            savedPages = prefs.getString("pages", "").orEmpty().split("~").map { it.split("|") }
            assertEquals("Dropping a folder on an app must swap grid positions", "folder:Audit", savedPages[0][12])
            assertEquals("The app must move into the folder's former cell, not into its contents", swapPackage, savedPages[0][11])
            val serializedFolder = prefs.getString("user_folders", "").orEmpty()
            assertTrue("Folder members must remain unchanged by a positional swap", serializedFolder.contains(firstPackage) && serializedFolder.contains(secondPackage) && !serializedFolder.contains(swapPackage))

            dragFolderTo("home-page-indicator-1")
            composeRule.waitUntil(5_000) {
                val pages = prefs.getString("pages", "").orEmpty().split("~")
                pages.getOrNull(1)?.split("|")?.getOrNull(8) == "folder:Audit" && prefs.getInt("current_page", -1) == 1
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("home-folder-Audit").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("home-folder-Audit").performClick()
            composeRule.onNodeWithText(firstLabel).assertExists()
            composeRule.onNodeWithText(secondLabel).assertExists()
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()

            dragFolderTo("home-page-indicator-0")
            composeRule.waitUntil(5_000) {
                val pages = prefs.getString("pages", "").orEmpty().split("~")
                pages[0].contains("folder:Audit") && "folder:Audit" !in pages[1]
            }
            assertEquals("Folder position must remain unique after moving back to the previous page", 1,
                prefs.getString("pages", "").orEmpty().split("~").sumOf { it.split("|").count { token -> token == "folder:Audit" } })
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("home-folder-Audit").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("home-folder-Audit").performClick()
            composeRule.onNodeWithText(firstLabel).assertExists()
            composeRule.onNodeWithText(secondLabel).assertExists()
        } finally {
            val edit = prefs.edit()
            if (originalPages == null) edit.remove("pages") else edit.putString("pages", originalPages)
            if (originalPackages == null) edit.remove("packages") else edit.putString("packages", originalPackages)
            if (originalFolders == null) edit.remove("user_folders") else edit.putString("user_folders", originalFolders)
            if (originalDock == null) edit.remove("dock_packages") else edit.putString("dock_packages", originalDock)
            edit.putInt("current_page", originalPage).commit()
            composeRule.activityRule.scenario.recreate()
        }
    }

    @Test
    fun folderCreatedByDraggingAppsCanBeMovedAndPersistsAsOneGridItem() {
        val prefs = composeRule.activity.getSharedPreferences("nexo_home", 0)
        val originalPages = prefs.getString("pages", null)
        val originalPackages = prefs.getString("packages", null)
        val originalFolders = prefs.getString("user_folders", null)
        val originalPage = prefs.getInt("current_page", 0)
        val packages = composeRule.activity.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it != composeRule.activity.packageName }.distinct().take(2)
        assertEquals("Need two real installed launcher activities", 2, packages.size)
        val page = MutableList(28) { "-" }.apply { this[8] = packages[0]; this[9] = packages[1] }
        prefs.edit().putString("user_folders", "[]")
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .putInt("current_page", 0).commit()
        try {
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().size >= 2 }
            val sourceNode = composeRule.onAllNodesWithTag("home-page-app").get(0)
            val source = sourceNode.fetchSemanticsNode().boundsInRoot
            val targetNode = composeRule.onNodeWithTag("home-cell-9", useUnmergedTree = true)
            val target = targetNode.fetchSemanticsNode().boundsInRoot.center
            sourceNode.performTouchInput {
                val origin = source.center - source.topLeft
                val destination = target - source.topLeft
                down(origin)
                advanceEventTime(700)
                for (step in 1..12) moveTo(origin + (destination - origin) * (step / 12f), 35)
                up()
            }
            composeRule.waitUntil(5_000) {
                prefs.getString("pages", "").orEmpty().split("|").getOrNull(9)?.startsWith("folder:") == true
            }
            val folderToken = prefs.getString("pages", "").orEmpty().split("|")[9]
            val folderName = folderToken.removePrefix("folder:")
            assertEquals("Dragged shortcut must leave its old cell", "-", prefs.getString("pages", "").orEmpty().split("|")[8])
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("home-folder-$folderName").fetchSemanticsNodes().isNotEmpty() }

            val folderNode = composeRule.onNodeWithTag("home-folder-$folderName")
            val folderBounds = folderNode.fetchSemanticsNode().boundsInRoot
            val nextCell = composeRule.onNodeWithTag("home-cell-10", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            folderNode.performTouchInput {
                val origin = folderBounds.center - folderBounds.topLeft
                val destination = nextCell - folderBounds.topLeft
                down(origin)
                advanceEventTime(700)
                for (step in 1..12) moveTo(origin + (destination - origin) * (step / 12f), 35)
                up()
            }
            composeRule.waitUntil(5_000) {
                prefs.getString("pages", "").orEmpty().split("|").getOrNull(10) == folderToken
            }
            assertEquals("App-drag folder must be stored exactly once", 1,
                prefs.getString("pages", "").orEmpty().split("|").count { it == folderToken })
            assertTrue("Both applications must remain inside the persisted folder", prefs.getString("user_folders", "").orEmpty().contains(packages[0]) && prefs.getString("user_folders", "").orEmpty().contains(packages[1]))
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-folder-$folderName").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Folder slot must survive Activity recreation", folderToken,
                prefs.getString("pages", "").orEmpty().split("|").getOrNull(10))
        } finally {
            val edit = prefs.edit()
            if (originalPages == null) edit.remove("pages") else edit.putString("pages", originalPages)
            if (originalPackages == null) edit.remove("packages") else edit.putString("packages", originalPackages)
            if (originalFolders == null) edit.remove("user_folders") else edit.putString("user_folders", originalFolders)
            edit.putInt("current_page", originalPage).commit()
            composeRule.activityRule.scenario.recreate()
        }
    }

    @Test
    fun homeEditUsesLivePocoStyleOverlayAndNoDoubleTapLock() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-surface").assertExists()
        composeRule.onNodeWithTag("home-edit-overlay").assertExists()
        composeRule.onNodeWithTag("home-trash-target").assertExists()
        composeRule.onNodeWithTag("poco-edit-toolbar").assertExists()
        composeRule.onNodeWithTag("edit-wallpaper").assertExists()
        composeRule.onNodeWithTag("edit-effects").assertExists()
        composeRule.onNodeWithTag("edit-widgets").assertExists()
        composeRule.onNodeWithTag("edit-configure").assertExists()
        composeRule.onNodeWithTag("add-home-page").assertExists()
        composeRule.onNodeWithTag("edit-effects").performClick()
        composeRule.onNodeWithTag("wallpaper-effect-oscuro").performClick()
        assertEquals(0.22f, composeRule.activity.getSharedPreferences("nexo", 0).getFloat("wallpaper_dim", -1f), 0.001f)
        composeRule.onNodeWithTag("edit-configure").performClick()
        composeRule.onAllNodesWithText("Cerrar").assertCountEquals(0)
        composeRule.onNodeWithTag("configure-home-pages").assertExists()
        composeRule.onNodeWithTag("configure-home-folder").assertExists()
        composeRule.onNodeWithTag("configure-home-icons").assertExists()
        composeRule.onNodeWithTag("configure-home-quote").assertExists()
        composeRule.onNodeWithTag("configure-home-settings").assertExists()
        val pagesBounds = composeRule.onNodeWithTag("configure-home-pages").fetchSemanticsNode().boundsInRoot
        val folderBounds = composeRule.onNodeWithTag("configure-home-folder").fetchSemanticsNode().boundsInRoot
        val iconsBounds = composeRule.onNodeWithTag("configure-home-icons").fetchSemanticsNode().boundsInRoot
        val quoteBounds = composeRule.onNodeWithTag("configure-home-quote").fetchSemanticsNode().boundsInRoot
        val settingsBounds = composeRule.onNodeWithTag("configure-home-settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Panel buttons should use equal grid widths", kotlin.math.abs(pagesBounds.width - folderBounds.width) < 1f)
        assertTrue("Second row should align with the first row", kotlin.math.abs(pagesBounds.left - iconsBounds.left) < 1f)
        assertTrue("Right-side buttons should share a grid column", kotlin.math.abs(folderBounds.left - quoteBounds.left) < 1f)
        assertTrue("Final single button should stay on the left grid column", kotlin.math.abs(pagesBounds.left - settingsBounds.left) < 1f)
        composeRule.onNodeWithTag("configure-home-quote").performClick()
        composeRule.onNodeWithText("Tu tarjeta").assertExists()
        composeRule.onNodeWithText("Cancelar").performClick()
        composeRule.onNodeWithTag("finish-home-edit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open-nexo-settings").performClick()
        composeRule.onAllNodesWithTag("settings-lock").assertCountEquals(0)
        composeRule.onNodeWithTag("settings-default-home").assertExists()
        composeRule.onNodeWithTag("settings-updates").assertExists()
        composeRule.onNodeWithTag("settings-backup").assertExists()
    }

    @Test
    fun activityAlwaysRequestsTheLiveAndroidWallpaper() {
        val flags = composeRule.activity.window.attributes.flags
        assertTrue("NEXO must show the current Android wallpaper even when launched as an app", 
            flags and android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
    }

    @Test
    fun savedHomeQuoteCanBeRemovedWithoutReturningAsDefault() {
        val visual = composeRule.activity.getSharedPreferences("nexo", 0)
        visual.edit().putString("quote", "Mi frase personal").commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-quote-card").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("remove-home-quote").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("remove-home-quote").performClick()
        composeRule.waitForIdle()
        assertEquals("", visual.getString("quote", null))
        composeRule.onAllNodesWithTag("home-quote-card").assertCountEquals(0)
        composeRule.onNodeWithTag("finish-home-edit").performClick()
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
    fun widgetPickerUsesNexoFullScreenLayoutWithSearchAndProviderPreview() {
        composeRule.runOnUiThread { composeRule.activity.launchWidgetPicker(0, 0) }
        composeRule.waitUntil(5_000) { composeRule.activity.availableWidgetProviders.isNotEmpty() }
        val provider = composeRule.activity.availableWidgetProviders.firstOrNull()
        assertTrue("The emulator must expose at least one widget provider to the real picker", provider != null)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("widget-picker-screen").assertExists()
        composeRule.onNodeWithText("Widgets").assertExists()
        composeRule.onNodeWithTag("widget-picker-search").assertExists()
        composeRule.onNodeWithTag("widget-app-filter-all").assertExists()
        val providerTag = "widget-provider-${provider!!.provider.packageName}"
        composeRule.onNodeWithTag("widget-picker-list").performScrollToNode(hasTestTag(providerTag))
        composeRule.onNodeWithTag(providerTag).assertExists()
        composeRule.onNodeWithTag("widget-picker-search").performTextInput("nexo-widget-search-no-match")
        composeRule.onNodeWithText("No encontramos ese widget").assertExists()
        composeRule.onNodeWithTag("widget-picker-close").performClick()
        composeRule.waitForIdle()
        assertTrue("Closing the widget picker should clear its list", composeRule.activity.availableWidgetProviders.isEmpty())
    }

    @Test
    fun appsCanBeRemovedFromCreatedPageWithoutChangingHome() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageNames = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it != composeRule.activity.packageName }.distinct().take(2)
        assertTrue("The emulator must provide two launchable apps for the remove test", packageNames.size == 2)
        val homeApp = packageNames[0]
        val pageApp = packageNames[1]
        val homePage = MutableList(28) { "-" }.apply { this[8] = homeApp }
        val createdPage = MutableList(28) { "-" }.apply { this[8] = pageApp }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", listOf(homePage, createdPage).joinToString("~") { it.joinToString("|") })
            .putString("packages", homePage.joinToString("|"))
            .putInt("current_page", 0).commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("edit-select-page-1").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-remove-app-8-$pageApp", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-remove-app-8-$pageApp", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("~").getOrNull(1)?.split("|")?.getOrNull(8) == "-"
        }
        val pages = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("~").map { it.split("|") }
        assertEquals("The created-page app is removed", "-", pages[1][8])
        assertEquals("Home is unchanged", homeApp, pages[0][8])
    }

    @Test
    fun appCanBeDraggedToTrashWhileEditingWithoutUninstallingIt() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageName = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.first { it != composeRule.activity.packageName }
        val page = MutableList(28) { "-" }.apply { this[8] = packageName }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .putInt("current_page", 0).commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("home-trash-target").fetchSemanticsNodes().isNotEmpty() }
        val node = composeRule.onNodeWithTag("home-page-app")
        val source = node.fetchSemanticsNode().boundsInRoot
        val target = composeRule.onNodeWithTag("home-trash-target").fetchSemanticsNode().boundsInRoot.center
        node.performTouchInput {
            val origin = source.center - source.topLeft
            val destination = target - source.topLeft
            down(origin)
            advanceEventTime(700)
            for (step in 1..14) moveTo(origin + (destination - origin) * (step / 14f), 35)
            up()
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("|").getOrNull(8) == "-"
        }
        assertTrue("Dragging the shortcut to the trash must leave its app installed", runCatching {
            composeRule.activity.packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess)
    }

    @Test
    fun appCanBeRemovedFromHomeWithoutUninstallingIt() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageName = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.first { it != composeRule.activity.packageName }
        val home = MutableList(28) { "-" }.apply { this[8] = packageName }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", home.joinToString("|"))
            .putString("packages", home.joinToString("|"))
            .putInt("current_page", 0).commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-remove-app-8-$packageName", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-remove-app-8-$packageName", useUnmergedTree = true).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("|").getOrNull(8) == "-"
        }
        assertEquals("The shortcut is removed without uninstalling the app", "-",
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("|")[8])
        assertTrue("The application remains installed", runCatching {
            composeRule.activity.packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess)
    }

    @Test
    fun appsCanBeDraggedToTrashFromHomeWithoutUninstallingThem() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageName = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.first { it != composeRule.activity.packageName }
        val page = MutableList(28) { "-" }.apply { this[8] = packageName }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", page.joinToString("|"))
            .putString("packages", page.joinToString("|"))
            .putInt("current_page", 0).commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty() }
        val appNode = composeRule.onNodeWithTag("home-page-app")
        val bounds = appNode.fetchSemanticsNode().boundsInRoot
        appNode.performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("home-trash-target").fetchSemanticsNodes().isNotEmpty() }
        val target = composeRule.onNodeWithTag("home-trash-target").fetchSemanticsNode().boundsInRoot.center
        appNode.performTouchInput {
            val origin = bounds.center - bounds.topLeft
            val destination = target - bounds.topLeft
            down(origin)
            advanceEventTime(700)
            for (step in 1..14) moveTo(origin + (destination - origin) * (step / 14f), 35)
            up()
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().split("|").getOrNull(8) == "-"
        }
        assertTrue("Dragging the shortcut to the trash must not uninstall the app", runCatching {
            composeRule.activity.packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess)
    }



    @Test
    fun longPressingHomeAppEntersPocoEditModeWithoutLaunchingIt() {
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
        composeRule.onNodeWithTag("finish-home-edit").assertExists()
        composeRule.onNodeWithTag("home-trash-target").assertExists()
        assertTrue("The home app should remain visible", composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("finish-home-edit").performClick()
    }

    @Test
    fun swipeUpOnHomeOpensTheAppDrawer() {
        composeRule.onNodeWithTag("home-surface").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(0)
    }

    @Test
    fun swipeDownAtTopOfDrawerClosesIt() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onNodeWithTag("app-drawer-surface").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun emptyPageCanBeDeletedFromLiveEditMode() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("add-home-page").performClick()
        composeRule.onAllNodesWithTag("edit-select-page-1").assertCountEquals(1)
        composeRule.onNodeWithTag("edit-delete-page").performClick()
        composeRule.waitUntil(5_000) {
            !composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty().contains("~")
        }
        composeRule.onAllNodesWithTag("confirm-delete-home-page").assertCountEquals(0)
        val savedPages = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty()
        assertTrue("Deleting the empty page must persist only the remaining page; saved pages were: $savedPages", !savedPages.contains("~"))
        composeRule.onNodeWithTag("finish-home-edit").performClick()
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
        val launchablePackages = composeRule.activity.packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it != composeRule.activity.packageName }.distinct()
        assertTrue("The emulator must provide two launchable apps for page preservation test", launchablePackages.size >= 2)
        val firstPage = MutableList(28) { "-" }.apply { this[8] = launchablePackages[0] }
        val secondPage = MutableList(28) { "-" }.apply { this[8] = launchablePackages[1] }
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", listOf(firstPage, secondPage).joinToString("~") { it.joinToString("|") })
            .putString("packages", firstPage.joinToString("|"))
            .putInt("current_page", 0).commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-page-app").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("edit-delete-page").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("confirm-delete-home-page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("cancel-delete-home-page").performClick()
        composeRule.onNodeWithTag("edit-delete-page").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("confirm-delete-home-page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("confirm-delete-home-page").performClick()
        composeRule.waitForIdle()
        val saved = composeRule.activity.getSharedPreferences("nexo_home", 0).getString("pages", "").orEmpty()
        assertTrue("Deleting the current populated page must leave the other page", !saved.contains("~"))
        assertTrue("The app on the other page must survive", saved.contains(launchablePackages[1]))
    }

    @Test
    fun widgetCanBeRemovedFromLiveEditWithoutRemovingItsProviderApp() {
        val allocatedId = java.util.concurrent.atomic.AtomicInteger(-1)
        val providerPackage = java.util.concurrent.atomic.AtomicReference<String>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            composeRule.activityRule.scenario.onActivity { activity ->
                val manager = android.appwidget.AppWidgetManager.getInstance(activity)
                val provider = manager.installedProviders.firstOrNull { it.provider.className == TestWidgetProvider::class.java.name }
                assertTrue("The instrumentation APK must expose its real widget provider", provider != null)
                val host = android.appwidget.AppWidgetHost(activity, 0x4E45584F)
                val id = host.allocateAppWidgetId()
                allocatedId.set(id)
                providerPackage.set(provider!!.provider.packageName)
                assertTrue("Android must bind the test widget instance", manager.bindAppWidgetIdIfAllowed(id, provider.provider))
                activity.hostedWidgetIds.add(id)
                activity.hostedWidgetPages[id] = 0
                activity.hostedWidgetSpans[id] = activity.widgetColumnSpan(id)
                activity.createWidgetHostView(id)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-widget-${allocatedId.get()}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("remove-widget-${allocatedId.get()}").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("remove-widget-${allocatedId.get()}").performClick()
        composeRule.waitUntil(5_000) { allocatedId.get() !in composeRule.activity.hostedWidgetIds }
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals("Removing a widget must release its Android widget ID", null,
                android.appwidget.AppWidgetManager.getInstance(activity).getAppWidgetInfo(allocatedId.get()))
            assertTrue("Removing a widget must not uninstall its provider app", activity.packageManager.getPackageInfo(
                providerPackage.get(), 0
            ) != null)
        }
        composeRule.onNodeWithTag("finish-home-edit").performClick()

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
                activity.hostedWidgetSpans[survivorId] = activity.widgetColumnSpan(survivorId)
                activity.hostedWidgetSpans[id] = activity.widgetColumnSpan(id)
                assertEquals("Test widget must be assigned to page 2", 1, activity.hostedWidgetPages[id])
                assertTrue("The test provider declares horizontal resizing", activity.canResizeWidgetWidth(id))
                val oldSpan = activity.widgetColumnSpan(id)
                activity.resizeWidgetWidth(id, -1, 8)
                val narrowerSpan = activity.widgetColumnSpan(id)
                assertTrue("Width resize stays within Android provider bounds and no wider than before", narrowerSpan in 1..oldSpan)
                assertTrue("Width resize must persist its grid span", activity.getSharedPreferences("nexo_widgets", 0).getInt("span_$id", -1) == narrowerSpan)
                assertTrue("NEXO must create an AppWidgetHostView for each bound widget", activity.createWidgetHostView(id) != null && activity.createWidgetHostView(survivorId) != null)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        composeRule.waitForIdle()
        composeRule.activity.getSharedPreferences("nexo_home", 0).edit().putInt("current_page", 1).commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("home-widget-${allocatedWidgetId.get()}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("edit-delete-page").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("confirm-delete-home-page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("confirm-delete-home-page").performClick()
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertTrue("Removed page widget must no longer be hosted", allocatedWidgetId.get() !in activity.hostedWidgetIds)
            assertTrue("Removed page widget must no longer have a page mapping", allocatedWidgetId.get() !in activity.hostedWidgetPages)
            assertTrue("Android must release the deleted widget ID", android.appwidget.AppWidgetManager.getInstance(activity).getAppWidgetInfo(allocatedWidgetId.get()) == null)
            assertTrue("A widget on another page must survive deletion", preservedWidgetId.get() in activity.hostedWidgetIds)
            assertEquals("Widgets on later pages must be reindexed after deletion", 0, activity.hostedWidgetPages[preservedWidgetId.get()])
        }
        } finally {
            val ids = listOf(allocatedWidgetId.get(), preservedWidgetId.get()).filter { it >= 0 }
            if (ids.isNotEmpty()) composeRule.activityRule.scenario.onActivity { activity ->
                val host = android.appwidget.AppWidgetHost(activity, 0x4E45584F)
                ids.forEach(host::deleteAppWidgetId)
            }
        }
    }

    @Test
    fun appDrawerHasNoRedundantCloseButton() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(0)
        composeRule.onAllNodesWithText("Cerrar").assertCountEquals(0)
        composeRule.onNodeWithTag("app-drawer-surface").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("open-app-drawer").assertCountEquals(1)
    }

    @Test
    fun newPageStaysEmptyAndPagesCanBeSelectedIndependently() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("add-home-page").performClick()
        composeRule.onAllNodesWithTag("edit-select-page-1").assertCountEquals(1)
        composeRule.onNodeWithTag("edit-select-page-0").performClick()
        composeRule.onNodeWithTag("edit-select-page-1").performClick()
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
        composeRule.onNodeWithTag("edit-configure").performClick()
        composeRule.onNodeWithText("Iconos").performClick()
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
        composeRule.onAllNodesWithTag("close-app-drawer").assertCountEquals(0)
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
