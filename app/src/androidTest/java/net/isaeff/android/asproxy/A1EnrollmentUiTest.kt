package net.isaeff.android.asproxy

import androidx.compose.ui.test.assertTextEquals
import android.util.Log
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithTag("a1-pin-toggle").assertTextEquals("Show")
        composeRule.onNodeWithTag("a1-pin-toggle").performClick()
        composeRule.onNodeWithTag("a1-pin-toggle").assertTextEquals("Hide")
        composeRule.onNodeWithTag("a1-pin-toggle").performClick()
        composeRule.onNodeWithTag("a1-verified-activate").assertDoesNotExist()
        composeRule.onNodeWithTag("a1-probe").performScrollTo().performClick()

        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("a1-fingerprint-full")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("a1-fingerprint-full").assertTextEquals(expected)
        composeRule.onNodeWithTag("a1-setup-code-confirmation")
            .assertTextEquals("Setup code / username: aap2e123456abcdef")
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

    @Test fun realComposeVerifiedClickEnrollsAndStartsSocks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invitation = File(context.filesDir, "a1-ci-ui-invitation")
        val lines = invitation.readLines(Charsets.UTF_8)
        check(invitation.delete()) { "Failed to remove temporary UI invitation" }
        require(lines.size == 3) { "Private CI UI invitation requires three fields" }
        val setupUser = EnrollmentInput.setupUser(lines[0])
        val pin = EnrollmentInput.pin(lines[1])
        val independentlyVerified = lines[2]
        require(independentlyVerified.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Fresh UI activation must not start with a saved SSH identity",
            keystore.containsAlias(AndroidKeystoreIdentity.ALIAS))

        composeRule.onNodeWithTag("a1-verified-activate").assertDoesNotExist()
        composeRule.onNodeWithTag("a1-server").performTextInput("10.0.2.2:22224")
        assertTrue("CI manual setup code must be six decimal digits",
            setupUser.matches(Regex("aap2e[0-9]{6}")))
        // Real manual entry has NO clipboard or Unix name prefix.
        composeRule.onNodeWithTag("a1-setup-user").performTextInput(setupUser.removePrefix("aap2e"))
        composeRule.onNodeWithTag("a1-pin").performTextInput(pin)
        composeRule.onNodeWithTag("a1-probe").performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("a1-fingerprint-full")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("a1-fingerprint-full")
            .assertTextEquals(independentlyVerified)
        composeRule.onNodeWithTag("a1-setup-code-confirmation")
            .assertTextEquals("Setup code / username: " + setupUser.removePrefix("aap2e"))
        composeRule.onNodeWithTag("a1-fingerprint-grouped")
            .assertTextEquals(EnrollmentInput.groupedFingerprint(independentlyVerified))
        assertFalse("Host key probe must not create a pending profile",
            DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        keystore.load(null)
        assertFalse("PIN/identity must not be used before Verified click",
            keystore.containsAlias(AndroidKeystoreIdentity.ALIAS))

        // Allow the ephemeral CI administrator to inspect the untouched
        // upload inbox *before* the only button that can send the PIN.
        val preVerified = File(context.filesDir, "a1-ci-ui-fingerprint-ready")
        val go = File(context.filesDir, "a1-ci-ui-verified-go")
        val release = File(context.filesDir, "a1-ci-ui-release")
        preVerified.delete()
        go.delete()
        release.delete()
        check(preVerified.createNewFile())
        composeRule.waitUntil(45_000) { go.exists() }
        go.delete()

        composeRule.onNodeWithTag("a1-verified-activate")
            .performScrollTo().performClick()
        composeRule.waitUntil(120_000) { DirectBootTunnelProfile.hasProfile(context) }
        val profile = requireNotNull(DirectBootTunnelProfile.load(context))
        assertEquals("10.0.2.2", profile.host)
        assertEquals(22224, profile.sshPort)
        // Six manually entered digits must select the matching permanent SSH account.
        assertEquals("aap2p" + setupUser.removePrefix("aap2e"), profile.username)
        assertArrayEquals(
            "The stored pin must equal the host key independently checked on the UI",
            VerifiedSshHostKey.probe(profile.host, profile.sshPort).blob,
            profile.verifiedHostKey
        )
        assertEquals(independentlyVerified,
            VerifiedSshHostKey.sha256Fingerprint(profile.verifiedHostKey))
        assertTrue(EnrollmentPending.load(context) == null)
        assertTrue(DirectBootTunnelProfile.wantsConnection(context))
        composeRule.waitUntil(60_000) {
            ConnectionStateHolder.connectionState.value == ConnectionState.CONNECTED
        }
        Log.i("AAP2-A1", "A1_REAL_COMPOSE_CLICK_PIN_SFTP_APPROVAL_SOCKS_CONNECTED")

        // The runner checks HTTP over this live foreground SOCKS, then
        // revokes the dedicated key before releasing instrumentation.
        composeRule.waitUntil(90_000) { release.exists() }
        release.delete()
    }
}
