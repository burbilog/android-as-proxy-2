package net.isaeff.android.asproxy

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic beginner-facing navigation. No SSH operation, credentials or
 * enrollment state changes; safe both before and after activation.
 */
@RunWith(AndroidJUnit4::class)
class A1AboutUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun aboutIsReachableAndCloseReturnsToMainScreen() {
        composeRule.onNodeWithTag("aap2-open-about").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("aap2-about-screen").assertExists()
        composeRule.onNodeWithTag("aap2-about-source").assertExists()
        composeRule.onNodeWithTag("aap2-about-issues").assertExists()
        composeRule.onNodeWithTag("aap2-about-close").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("aap2-open-about").assertIsDisplayed()
    }
}
