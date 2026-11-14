package net.isaeff.android.asproxy

import android.security.keystore.KeyProperties
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * Uses the real Activity and real SSH host KEX against disposable CI OpenSSH.
 * A fake setup username/PIN intentionally cannot enroll: the test stops at
 * the trust boundary. The independent trusted fingerprint comes from the
 * ephemeral runner's ssh-keygen, not from the Android probe.
 */
@RunWith(AndroidJUnit4::class)
class A1EnrollmentUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<EnrollmentActivity>()

    @Test fun fingerprintMustBeDisplayedAndExplicitlyVerifiedBeforePinAuth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = requireNotNull(
            InstrumentationRegistry.getArguments().getString("trusted_sha256")
        ) { "Trusted host SHA256 from runner ssh-keygen is required" }
        require(expected.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertFalse(store.containsAlias(AndroidKeystoreIdentity.ALIAS))
        assertTrue(EnrollmentPending.load(context) == null)

        composeRule.onNodeWithTag("a1-verified-activate").assertDoesNotExist()
        composeRule.onNodeWithTag("a1-server").performTextInput("10.0.2.2:22224")
        composeRule.onNodeWithTag("a1-setup-user").performTextInput("aap2e123456abcdef")
        composeRule.onNodeWithTag("a1-pin").performTextInput("0000-0000-12")
        composeRule.onNodeWithTag("a1-verified-activate").assertDoesNotExist()
        composeRule.onNodeWithTag("a1-probe").performScrollTo().performClick()

        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("a1-fingerprint-full")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("a1-fingerprint-full").assertTextEquals(expected)
        composeRule.onNodeWithTag("a1-fingerprint-grouped").assertTextEquals(
            EnrollmentInput.groupedFingerprint(expected)
        )
        composeRule.onNodeWithTag("a1-verified-activate").assertExists()

        // Only pre-auth probe has run. Neither PIN/SFTP nor Keystore creation
        // is possible until the user actually presses Verified.
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        store.load(null)
        assertFalse(store.containsAlias(AndroidKeystoreIdentity.ALIAS))
        composeRule.onNodeWithTag("a1-cancel-verification").performScrollTo().performClick()
        composeRule.onNodeWithTag("a1-verified-activate").assertDoesNotExist()
        composeRule.onNodeWithTag("a1-probe").assertExists()
        assertTrue(EnrollmentPending.load(context) == null)
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
    }
}
