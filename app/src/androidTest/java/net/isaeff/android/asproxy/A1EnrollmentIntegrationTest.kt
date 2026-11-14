package net.isaeff.android.asproxy

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run ONLY on a disposable Android emulator with CI's isolated S2 sshd.
 * This class has no test fallback to the production AAP2 port or identity.
 * Test-only PIN input is pushed privately by the ephemeral CI harness and
 * deleted before an SSH connection is attempted.
 */
@RunWith(AndroidJUnit4::class)
class A1EnrollmentIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = "10.0.2.2"
    private val port = 22224

    private data class Invitation(val user: String, val pin: String, val trustedFingerprint: String)

    private fun invitation(filename: String = "a1-ci-invitation"): Invitation {
        val file = File(context.filesDir, filename)
        val lines = file.readLines(Charsets.UTF_8)
        file.delete()
        require(lines.size == 3) { "CI invitation must have three lines" }
        val user = EnrollmentInput.setupUser(lines[0])
        val pin = EnrollmentInput.pin(lines[1])
        val fingerprint = lines[2]
        require(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))) {
            "Host fingerprint must come from CI server's local ssh-keygen, not the probe"
        }
        return Invitation(user, pin, fingerprint)
    }

    @Test
    fun a1_0_malformedServerJsonRejectedBeforeProvisioning() {
        val client = EnrollmentClient(context)
        val validConfig = """{"schema":1,"username":"aap2p123456abcdef","remote_port":11010}"""
        val validStatus = """{"schema":1,"status":"pending","expires_at":1790000000}"""
        client.parseConfig(validConfig.toByteArray())
        client.parseStatus(validStatus.toByteArray())

        val badConfigs = listOf(
            """{"schema":"1","username":"aap2p123456abcdef","remote_port":11010}""",
            """{"schema":1.5,"username":"aap2p123456abcdef","remote_port":11010}""",
            """{"schema":1,"username":"aap2p123456abcdef","remote_port":11010.5}""",
            """{"schema":1,"username":"aap2p123456abcdef","remote_port":"11010"}""",
            """{"schema":1,"username":"root","remote_port":11010}""",
            """{"schema":1,"username":"aap2p123456abcdef","remote_port":11009}""",
            """{"schema":1,"username":"aap2p123456abcdef","remote_port":11010,"extra":true}""",
            """{"schema":1,"username":"aap2p123456abcdef"}""",
            """not JSON"""
        )
        val badStatuses = listOf(
            """{"schema":"1","status":"pending","expires_at":1790000000}""",
            """{"schema":1,"status":"approved","expires_at":"1790000000"}""",
            """{"schema":1,"status":"approved","expires_at":1790000000.5}""",
            """{"schema":1,"status":"rejected","expires_at":1790000000}""",
            """{"schema":1,"status":"approved","expires_at":0}""",
            """{"schema":1,"status":"pending","expires_at":1790000000,"extra":true}""",
            """[]"""
        )
        for (json in badConfigs) {
            assertTrue("Malformed S2 config accepted: $json",
                runCatching { client.parseConfig(json.toByteArray()) }.isFailure)
        }
        for (json in badStatuses) {
            assertTrue("Malformed S2 status accepted: $json",
                runCatching { client.parseStatus(json.toByteArray()) }.isFailure)
        }
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        Log.i("AAP2-A1", "A1_MALFORMED_SFTP_JSON_REJECTED")
    }

    @Test
    fun a1_1_wrongPinnedHostRejectedBeforeAuthenticationOrKeyCreation() = runBlocking<Unit> {
        assertFalse("Disposable emulator must have no existing profile",
            DirectBootTunnelProfile.hasProfile(context))
        assertTrue("No pending data permitted before enrollment",
            EnrollmentPending.load(context) == null)
        val observed = withContext(Dispatchers.IO) {
            VerifiedSshHostKey.probe(host, port)
        }
        val wrong = observed.blob.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
        }
        val fake = VerifiedSshHostKey.Probe(host, port, wrong)
        var rejected = false
        try {
            withTimeout(30_000) {
                EnrollmentClient(context).enroll(
                    fake, "aap2e123456abcdef", "0000000000"
                ) {}
            }
        } catch (e: Exception) {
            rejected = e.message.orEmpty().contains("HostKey", ignoreCase = true)
        }
        assertTrue("An unexpected SSH host key MUST be rejected pre-auth", rejected)
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        Log.i("AAP2-A1", "A1_WRONG_PINNED_HOST_REJECTED_PREAUTH")
    }

    @Test
    fun a1_1b_expiredPinCannotCreatePendingProfile() = runBlocking<Unit> {
        val expired = invitation("a1-ci-expired-invitation")
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        val probe = withContext(Dispatchers.IO) {
            VerifiedSshHostKey.probe(host, port)
        }
        assertEquals(expired.trustedFingerprint, probe.fingerprint)
        val outcome = runCatching {
            withTimeout(30_000) {
                EnrollmentClient(context).enroll(probe, expired.user, expired.pin) {}
            }
        }
        assertTrue("Expired S2 setup PIN was accepted", outcome.isFailure)
        assertFalse("Expired PIN must not provision Direct Boot",
            DirectBootTunnelProfile.hasProfile(context))
        assertTrue("Expired PIN must not leave pending enrollment",
            EnrollmentPending.load(context) == null)
        Log.i("AAP2-A1", "A1_EXPIRED_PIN_REJECTED_NO_PROFILE")
    }

    @Test
    fun a1_1c_resetRevokedUiFixtureWithoutDeletingKeystore() = runBlocking<Unit> {
        // Only on disposable CI Android, after the previous UI invitation was
        // explicitly revoked by S2. Preserve the device Keystore identity.
        require(DirectBootTunnelProfile.hasProfile(context))
        val before = AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob()
        context.startService(Intent(context, SocksForegroundService::class.java).apply {
            action = SocksForegroundService.ACTION_STOP
        })
        withTimeout(15_000) {
            while (ConnectionStateHolder.connectionState.value != ConnectionState.DISCONNECTED)
                delay(200)
        }
        DirectBootTunnelProfile.disable(context)
        assertFalse(DirectBootTunnelProfile.hasProfile(context))
        assertTrue(EnrollmentPending.load(context) == null)
        assertArrayEquals(before, AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        Log.i("AAP2-A1", "A1_CI_UI_PROFILE_RESET_KEYSTORE_PRESERVED")
    }

    @Test
    fun a1_2_realPinSftpApprovalAndPersistentKeystoreTunnel() = runBlocking<Unit> {
        val invite = invitation()
        assertFalse("This test must never be run over an existing AAP2 profile",
            DirectBootTunnelProfile.hasProfile(context))
        val probe = withContext(Dispatchers.IO) {
            VerifiedSshHostKey.probe(host, port)
        }
        assertEquals("Independent local ssh-keygen host fingerprint must match",
            invite.trustedFingerprint, probe.fingerprint)
        assertFalse("A host-key probe is not enrollment",
            DirectBootTunnelProfile.hasProfile(context))

        // Host waits for the upload marker, kills the disposable SFTP user
        // session, then approves ONLY after this test confirms pending state.
        val keyUploaded = File(context.filesDir, "a1-ci-key-uploaded")
        val awaitingApproval = File(context.filesDir, "a1-ci-awaiting-approval")
        val approvalDone = File(context.filesDir, "a1-ci-approval-done")
        keyUploaded.delete()
        awaitingApproval.delete()
        approvalDone.delete()
        val failure = runCatching {
            withTimeout(90_000) {
                EnrollmentClient(context).enroll(probe, invite.user, invite.pin) {
                    Log.i("AAP2-A1", "A1_ENROLL_PROGRESS " + it)
                    if (it.startsWith("Waiting for administrator approval")) {
                        // callback is reached only AFTER successful SFTP put()
                        keyUploaded.createNewFile()
                    }
                }
            }
        }
        assertTrue("Interrupted SFTP should leave enrollment incomplete", failure.isFailure)
        assertFalse("No Direct Boot profile before admin approval",
            DirectBootTunnelProfile.hasProfile(context))
        val pending = requireNotNull(EnrollmentPending.load(context))
        assertEquals(host, pending.host)
        assertEquals(port, pending.sshPort)
        assertArrayEquals(probe.blob, pending.verifiedHostKey)
        val originalKey = AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob().copyOf()
        Log.i("AAP2-A1", "A1_INTERRUPTED_SFTP_PENDING_SAVED")
        awaitingApproval.createNewFile()

        // No PIN is available to resume(), and it must not regenerate a key.
        withTimeout(90_000) {
            while (!approvalDone.exists()) delay(250)
        }
        approvalDone.delete()
        withTimeout(40_000) {
            EnrollmentClient(context).resume {
                Log.i("AAP2-A1", "A1_RESUME_PROGRESS " + it)
            }
        }
        assertArrayEquals("Resume must keep exactly the uploaded Keystore identity",
            originalKey, AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        Log.i("AAP2-A1", "A1_INTERRUPTED_SFTP_RESUME_WITHOUT_PIN_OK")

        val profile = requireNotNull(DirectBootTunnelProfile.load(context))
        assertTrue(DirectBootTunnelProfile.wantsConnection(context))
        assertEquals(host, profile.host)
        assertEquals(port, profile.sshPort)
        assertTrue(profile.username.matches(Regex("aap2p[0-9a-f]{12}")))
        assertTrue(profile.remotePort in 11010..11509)
        assertArrayEquals(probe.blob, profile.verifiedHostKey)
        assertTrue("Pending state must be cleared after durable profile commit",
            EnrollmentPending.load(context) == null)

        val identity = AndroidKeystoreIdentity.getExisting(context)
        assertArrayEquals(identity.getPublicKeyBlob(),
            AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        // Test-only PUBLIC blob expected across an in-place signed APK update.
        // No private key, PIN or SSH password is copied to app storage.
        File(context.filesDir, "a1-ci-before-update-publickey")
            .writeBytes(identity.getPublicKeyBlob())

        // A real SSH publickey authentication must succeed, but OpenSSH's
        // permitlisten on THIS key must reject a neighboring reserved port.
        // The check uses the same client implementation as enrollment; no
        // mocking of the SSH server or fabricated config.json status.
        val wrongPort = if (profile.remotePort == 11509) 11508 else profile.remotePort + 1
        val wrongForward = EnrollmentPending.Info(
            host, port, profile.username, wrongPort, profile.verifiedHostKey.copyOf()
        )
        val refused = withContext(Dispatchers.IO) {
            runCatching { EnrollmentClient(context).verifyPermanentTunnel(wrongForward) }
        }
        assertTrue("OpenSSH allowed a reverse port outside authorized permitlisten", refused.isFailure)
        assertEquals("Rejected -R must not overwrite the enrolled profile",
            profile.remotePort, requireNotNull(DirectBootTunnelProfile.load(context)).remotePort)
        withContext(Dispatchers.IO) {
            EnrollmentClient(context).verifyPermanentTunnel(
                EnrollmentPending.Info(
                    host, port, profile.username, profile.remotePort, profile.verifiedHostKey.copyOf()
                )
            )
        }
        Log.i("AAP2-A1", "A1_WRONG_PERMITLISTEN_DENIED_CORRECT_PORT_ACCEPTED")

        val intent = Intent(context, SocksForegroundService::class.java).apply {
            action = "net.isaeff.android.asproxy.action.START_SERVICE"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        withTimeout(45_000) {
            while (ConnectionStateHolder.connectionState.value != ConnectionState.CONNECTED) {
                delay(500)
            }
        }
        assertEquals(ConnectionState.CONNECTED, ConnectionStateHolder.connectionState.value)
        Log.i("AAP2-A1", "A1_PIN_SFTP_APPROVAL_KEYSTORE_R_READY remote=" + profile.remotePort)
        // Keep the instrumented target process alive while the CI host curls
        // through this real Android foreground SOCKS and then revokes it.
        // Android's test runner can stop the target process after a test ends,
        // so post-test network checks alone can be a false negative.
        val release = File(context.filesDir, "a1-ci-release-test")
        withTimeout(90_000) {
            while (!release.exists()) delay(250)
        }
        release.delete()
        // Do NOT stop the service ourselves; server revoke owns teardown.
    }

    @Test
    fun a1_3_inPlaceSignedApkUpgradePreservesKeystoreAndPin() = runBlocking<Unit> {
        val baselineFile = File(context.filesDir, "a1-ci-before-update-publickey")
        val baseline = baselineFile.readBytes()
        assertTrue("Missing pre-upgrade PUBLIC key fixture", baseline.isNotEmpty())
        val current = AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob()
        assertArrayEquals("In-place APK update changed Android Keystore SSH identity", baseline, current)
        val profile = requireNotNull(DirectBootTunnelProfile.load(context))
        assertTrue("In-place update cleared desired_running", DirectBootTunnelProfile.wantsConnection(context))
        assertEquals(host, profile.host)
        assertEquals(port, profile.sshPort)
        val actual = withContext(Dispatchers.IO) { VerifiedSshHostKey.probe(host, port) }
        assertArrayEquals("Pinned host key changed after APK update", profile.verifiedHostKey, actual.blob)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertTrue("Upgrade did not install a newer versionCode", packageInfo.longVersionCode >= 20001L)
        withContext(Dispatchers.IO) {
            EnrollmentClient(context).verifyPermanentTunnel(
                EnrollmentPending.Info(
                    profile.host, profile.sshPort, profile.username,
                    profile.remotePort, profile.verifiedHostKey.copyOf()
                )
            )
        }
        assertTrue("Upgrade recreated pending PIN enrollment", EnrollmentPending.load(context) == null)
        val service = Intent(context, SocksForegroundService::class.java).apply {
            action = "net.isaeff.android.asproxy.action.START_SERVICE"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
        withTimeout(45_000) {
            while (ConnectionStateHolder.connectionState.value != ConnectionState.CONNECTED)
                delay(500)
        }
        Log.i("AAP2-A1", "A1_SIGNED_UPGRADE_SAME_KEY_PIN_PUBLICKEY_R_READY")
        val release = File(context.filesDir, "a1-ci-release-upgrade-test")
        withTimeout(90_000) {
            while (!release.exists()) delay(250)
        }
        release.delete()
    }
}
