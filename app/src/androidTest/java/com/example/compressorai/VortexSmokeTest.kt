package com.example.compressorai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class VortexSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appShowsDashboardTitle() {
        composeRule.onNodeWithText("Vortex Compressor").assertIsDisplayed()
    }
}

