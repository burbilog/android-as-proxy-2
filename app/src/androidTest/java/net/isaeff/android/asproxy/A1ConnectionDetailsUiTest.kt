package net.isaeff.android.asproxy

import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run after disposable E2E enrollment and before clearing its profile.
 * Deliberately uses the REAL MainActivity and the REAL approved Keystore key:
 * no fake account, fingerprint, or persisted credential is installed.
 * Does not connect to the server, alter the profile, or reset the phone.
 */
@RunWith(AndroidJUnit4::class)
class A1ConnectionDetailsUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun approvedIdentityVisibleAndFullDiagnosticsFitOnePhoneScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = requireNotNull(DirectBootTunnelProfile.load(context)) {
            "Real disposable E2E enrollment must precede details UI test"
        }
        val id = ConnectionIdentity.id(profile.username)
        val hostFingerprint = VerifiedSshHostKey.sha256Fingerprint(profile.verifiedHostKey)
        val phoneFingerprint = VerifiedSshHostKey.sha256Fingerprint(
            AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob()
        )

        // The package manager must recognize an installed APK as the SAME
        // AAP2 package and reject it as a non-upgrade. No HTTP or install UI.
        val alreadyInstalled = File(context.applicationInfo.sourceDir)
        val sameVersion = runCatching {
            ApkUpdater.validatePackage(context.packageManager, context.packageName,
                alreadyInstalled)
        }
        assertTrue("Updater did not reject same-version installed APK",
            sameVersion.exceptionOrNull()?.message?.contains("Already up to date") == true)

        // Regression: Android 11 may provide a valid archive's package
        // metadata but null signingInfo. That is NOT a signer mismatch.
        // The real system installer verifies the APK signature at install time.
        val installedMetadata = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val newerMetadata = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            versionCode = installedMetadata.longVersionCode.toInt() + 1
            versionName = "next-release"
        }
        assertTrue("Test candidate must not have signer metadata",
            newerMetadata.signingInfo == null)
        assertEquals("next-release", ApkUpdater.validatePackageMetadata(
            installedMetadata, newerMetadata, context.packageName
        ))
        newerMetadata.packageName = "wrong.package"
        assertTrue("Updater must reject an unrelated package",
            runCatching {
                ApkUpdater.validatePackageMetadata(
                    installedMetadata, newerMetadata, context.packageName
                )
            }.isFailure)

        // Only a scoped cached APK is readable through the FileProvider. The
        // system installer receives this content URI with a one-time read grant.
        val tempDir = File(context.cacheDir, "updates")
        assertTrue(tempDir.isDirectory || tempDir.mkdirs())
        val cacheApk = File(tempDir, "aap2.apk")
        try {
            cacheApk.writeText("file-provider-test")
            val uri = FileProvider.getUriForFile(context,
                "${context.packageName}.updates", cacheApk)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.updates", uri.authority)
            assertEquals("file-provider-test",
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                    it.readText()
                })
        } finally {
            cacheApk.delete()
        }

        composeRule.onNodeWithTag("a1-connection-id")
            .assertTextEquals("Connection ID: $id")
        // Never click Update in CI: the latest release may not be published,
        // and the test must not trigger Android's real package installer.
        composeRule.onNodeWithTag("a1-update").assertIsDisplayed()
        composeRule.onNodeWithTag("a1-open-details").assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("a1-details-id").assertTextEquals("ID: $id")
        composeRule.onNodeWithTag("a1-details-account").assertTextEquals(profile.username)
        composeRule.onNodeWithTag("a1-details-server")
            .assertTextEquals("${profile.host}:${profile.sshPort}")
        composeRule.onNodeWithTag("a1-details-socks")
            .assertTextEquals("127.0.0.1:${profile.remotePort}")
        composeRule.onNodeWithTag("a1-details-local")
            .assertTextEquals("127.0.0.1:11080")
        composeRule.onNodeWithTag("a1-details-host-fingerprint")
            .assertTextEquals(hostFingerprint).assertIsDisplayed()
        composeRule.onNodeWithTag("a1-details-device-fingerprint")
            .assertTextEquals(phoneFingerprint).assertIsDisplayed()
        composeRule.onNodeWithTag("a1-details-phone").assertIsDisplayed()
        composeRule.onNodeWithTag("a1-details-version").assertIsDisplayed()
        composeRule.onNodeWithTag("a1-details-status").assertIsDisplayed()
        composeRule.onNodeWithTag("a1-details-footer").assertIsDisplayed()

        // One portrait screenshot, not a scrolling support wizard.
        val parent = composeRule.onNodeWithTag("a1-details-screen").fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag("a1-details-footer").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Diagnostics footer must be inside the visible screen", bottom <= parent.bottom)
        assertTrue("Public SSH fingerprint must be full SHA256", hostFingerprint.length > 40)
        assertEquals("Public identity must match persisted SSH account", "aap2p$id", profile.username)

        // Simulate a retry state in the shared UI flow WITHOUT touching the
        // actual foreground service or interrupting the real E2E SSH tunnel.
        val previousState = ConnectionStateHolder.connectionState.value
        try {
            composeRule.runOnUiThread {
                ConnectionStateHolder.setState(ConnectionState.RETRYING)
            }
            composeRule.onNodeWithTag("a1-details-status")
                .assertTextEquals("Reconnecting")
            composeRule.onNodeWithTag("a1-details-close").performClick()
            composeRule.onNodeWithTag("a1-connection-id")
                .assertTextEquals("Connection ID: $id")
            composeRule.onNodeWithTag("a1-main-status")
                .assertTextEquals("Reconnecting…")
            composeRule.onNodeWithTag("a1-disconnect").assertIsDisplayed()
            composeRule.onNodeWithTag("a1-connect").assertDoesNotExist()
            // Only check button visibility; do NOT click Disconnect here.
        } finally {
            composeRule.runOnUiThread {
                ConnectionStateHolder.setState(previousState)
            }
        }
    }
}
