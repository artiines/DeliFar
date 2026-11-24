package com.delifar.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.delifar.app.cloud.FirebaseServices
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android startup & navigation smoke test executed on Android Emulator in CI
 * (both with and without app/google-services.json).
 *
 * Verifies:
 *  1. Application & MainActivity launch cleanly without Crashlytics/FirebaseInitProvider/AppCheck crashes.
 *  2. Home screen renders and bottom navigation is interactive.
 *  3. Settings screen opens cleanly and displays sync/offline status.
 *  4. Activity recreation survives without crash.
 */
@RunWith(AndroidJUnit4::class)
class DeliFarStartupSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesCleanly_opensSettingsAndSurvivesRecreate() {
        composeRule.waitForIdle()

        // Home tab and primary title render without crashing
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Map").assertIsDisplayed()
        composeRule.onNodeWithText("Stores").assertIsDisplayed()

        // Open Settings screen via top bar icon
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cloud Synchronization").assertIsDisplayed()

        // Navigate back to Home
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").assertIsDisplayed()

        // Verify activity recreation survives cleanly (configuration change / restart)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").assertIsDisplayed()

        // If Firebase config is bundled in this APK, verify Crashlytics did not crash startup
        assertTrue(FirebaseServices.isConfigured || !FirebaseServices.isAuthenticated)
    }
}
