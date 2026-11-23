package com.nexo.launcher

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Rule
import org.junit.Test

class HomeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appDrawerOpensAndClosesFromExplicitControls() {
        composeRule.onNodeWithTag("open-app-drawer").performClick()
        composeRule.onNodeWithTag("close-app-drawer").assertExists()
        composeRule.onNodeWithTag("close-app-drawer").performClick()
        composeRule.onNodeWithTag("open-app-drawer").assertExists()
    }

    @Test
    fun newPageStaysEmptyAndBothPagesCanBeSelected() {
        composeRule.onNodeWithTag("home-clock").performTouchInput { longClick() }
        composeRule.onNodeWithTag("add-home-page").performClick()

        composeRule.onNodeWithContentDescription("Página 2, seleccionada · 0 apps").assertExists()
        composeRule.onNodeWithTag("page-preview-0").performClick()
        composeRule.onNode(hasContentDescription("Página 1, seleccionada", substring = true)).assertExists()
        composeRule.onNodeWithTag("page-preview-1").performClick()
        composeRule.onNodeWithContentDescription("Página 2, seleccionada · 0 apps").assertExists()
        composeRule.onNodeWithTag("finish-home-edit").performClick()

        composeRule.onNodeWithContentDescription("Ir a la página 1").performClick()
        composeRule.onNodeWithContentDescription("Ir a la página 1, seleccionada").assertExists()
        composeRule.onNodeWithContentDescription("Ir a la página 2").performClick()
        composeRule.onNodeWithContentDescription("Ir a la página 2, seleccionada").assertExists()
        composeRule.onAllNodesWithTag("home-page-apps-1").assertCountEquals(0)
    }
}
