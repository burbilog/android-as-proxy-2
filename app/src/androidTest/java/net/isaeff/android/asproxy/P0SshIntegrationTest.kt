package net.isaeff.android.asproxy

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSchException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Explicit on-device P0 test (never run against production sshd).
 *
 * Arguments are passed via adb shell am instrument -e host, port, user,
 * host_key_base64, remote_port. Missing host arguments skip network tests.
 * Private key and passwords are never printed or transmitted via test arguments.
 */
@RunWith(AndroidJUnit4::class)
class P0SshIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun keystorePublicKeyAndPersistence() {
        val original = AndroidKeystoreIdentity.getOrCreate(context)
        val publicKey = original.getPublicKeyBlob()
        assertEquals("ecdsa-sha2-nistp256", SshEcWire.hostKeyAlgorithm(publicKey))
        assertArrayEquals(publicKey, AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        val certificate = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getCertificate(AndroidKeystoreIdentity.ALIAS)
        val data = "aap2-p0-local-keystore-signature".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey(AndroidKeystoreIdentity.ALIAS, null) as java.security.PrivateKey
        )
        signer.update(data)
        val der = signer.sign()
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(certificate)
        verifier.update(data)
        assertTrue(verifier.verify(der))

        // Public key is deliberately printed for one-time test server authorization.
        Log.i("AAP2-P0", "PUBLIC_KEY " + original.authorizedKey())
        Log.i("AAP2-P0", "KEYSTORE_OK alias=" + AndroidKeystoreIdentity.ALIAS)
    }

    @Test
    fun probeHostKeyBeforeAuthentication() {
        val host = arguments.getString("host")
        assumeTrue("Supply -e host <name> to test SSH KEX", !host.isNullOrBlank())
        val port = arguments.getString("port")?.toIntOrNull() ?: 22
        val probe = VerifiedSshHostKey.probe(host!!, port)
        Log.i("AAP2-P0", "HOST_KEY " + probe.algorithm + " " + probe.fingerprint)
        Log.i("AAP2-P0", "HOST_KEY_BASE64 " + probe.base64)
        // No password or SSH identity was supplied to this probe.
        assertTrue(probe.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun publickeyAuthAndLoopbackReverseForward() {
        val host = arguments.getString("host")
        val user = arguments.getString("user")
        val blob64 = arguments.getString("host_key_base64")
        assumeTrue("Supply host, user, and host_key_base64 for SSH -R test",
            !host.isNullOrBlank() && !user.isNullOrBlank() && !blob64.isNullOrBlank())
        val port = arguments.getString("port")?.toIntOrNull() ?: 22
        val remotePort = arguments.getString("remote_port")?.toIntOrNull() ?: 10909
        require(remotePort in 1024..65535)
        val pinned = Base64.decode(blob64, Base64.DEFAULT)
        val actual = VerifiedSshHostKey.probe(host!!, port)
        assertArrayEquals("Remote host key differs from independently trusted key", pinned, actual.blob)
        val identity = AndroidKeystoreIdentity.getExisting(context)
        val jsch = VerifiedSshHostKey.pinnedJsch(host, port, pinned)
        jsch.addIdentity(identity, null)
        val session = jsch.getSession(user, host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        session.setConfig("NumberOfPasswordPrompts", "0")
        session.setServerAliveInterval(15_000)
        val socks = Socks5ProxyServer(0)
        try {
            socks.start()
            session.connect(30_000)
            session.setPortForwardingR("127.0.0.1", remotePort, "127.0.0.1", socks.boundPort)
            Log.i("AAP2-P0", "FORWARD_READY remote=127.0.0.1:" + remotePort +
                " local=127.0.0.1:" + socks.boundPort)
            // While this test runs, execute on the VM:
            // curl --proxy socks5h://127.0.0.1:10909 https://example.org/
            Thread.sleep(60_000)
            assertTrue("SSH disconnected during forward test", session.isConnected)
        } finally {
            session.disconnect()
            socks.close()
        }
    }
    /**
     * Non-destructive MITM/changed-host-key simulation.
     * Connect to the REAL test sshd with a deliberately incorrect local pin.
     * No remote host key is modified; neither AAP2's stored pin nor the
     * Android Keystore identity is changed.
     */
    @Test
    fun changedHostKeyRejectedBeforePublickeyAuth() {
        val host = requireNotNull(arguments.getString("host")) { "Missing host" }
        val user = requireNotNull(arguments.getString("user")) { "Missing user" }
        val trustedBase64 = requireNotNull(arguments.getString("host_key_base64")) {
            "Missing independently trusted SSH server host-key blob"
        }
        val port = arguments.getString("port")?.toIntOrNull() ?: 22
        val trusted = Base64.decode(trustedBase64, Base64.DEFAULT)

        // First prove this test is against the expected, reachable sshd.
        val actual = VerifiedSshHostKey.probe(host, port)
        assertArrayEquals("Test sshd host key has actually changed", trusted, actual.blob)

        val wrongPin = trusted.copyOf()
        wrongPin[wrongPin.lastIndex] =
            (wrongPin.last().toInt() xor 0x01).toByte()
        val originalProfile = DirectBootTunnelProfile.load(context)
        val originalIdentity = AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob()

        val jsch = VerifiedSshHostKey.pinnedJsch(host, port, wrongPin)
        jsch.addIdentity(AndroidKeystoreIdentity.getExisting(context), null)
        val session = jsch.getSession(user, host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        session.setConfig("NumberOfPasswordPrompts", "0")
        try {
            var rejectedByHostKey = false
            try {
                session.connect(15_000)
            } catch (e: JSchException) {
                rejectedByHostKey =
                    e.message.orEmpty().contains("HostKey", ignoreCase = true)
                assertTrue("Expected host-key rejection, got: " +
                    e.javaClass.simpleName, rejectedByHostKey)
            }
            assertTrue("Changed host key was accepted", rejectedByHostKey)
            assertFalse("No SSH login may survive an untrusted host key", session.isConnected)
        } finally {
            session.disconnect()
        }

        assertArrayEquals(originalIdentity,
            AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        val afterProfile = DirectBootTunnelProfile.load(context)
        assertEquals(originalProfile == null, afterProfile == null)
        if (originalProfile != null && afterProfile != null) {
            assertArrayEquals("Stored host key must never be repinned",
                originalProfile.verifiedHostKey, afterProfile.verifiedHostKey)
            assertEquals(originalProfile.host, afterProfile.host)
            assertEquals(originalProfile.remotePort, afterProfile.remotePort)
        }
        Log.i("AAP2-P0", "HOST_KEY_MISMATCH_REJECTED_PREAUTH")
    }

    /**
     * Negative key-loss path without deleting or invalidating the real key.
     * A random, absent alias is looked up through the same read-only Keystore
     * loading branch used at unattended boot. Nothing is ever generated.
     */
    @Test
    fun missingKeystoreIdentityFailsClosed() {
        val original = AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val missing = "aap2-test-absent-" + UUID.randomUUID().toString()
        assertFalse(store.containsAlias(missing))

        var rejected = false
        try {
            AndroidKeystoreIdentity.getMissingAliasForTest(context, missing)
        } catch (e: IllegalStateException) {
            rejected = true
        }
        assertTrue("Absent Keystore alias must fail closed", rejected)
        store.load(null)
        assertFalse("No replacement alias may be silently generated", store.containsAlias(missing))
        assertArrayEquals("Real SSH identity must survive negative test",
            original, AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        Log.i("AAP2-P0", "MISSING_KEYSTORE_REJECTED_NO_REGEN")
    }

    /**
     * The real sshd denies an unknown user while the host key is pinned and
     * the genuine Keystore identity is used. Exercise the production manager's
     * fatal-error classification without changing stored config or sshd.
     *
     * Stop the running AAP2 service through its notification first.
     */
    @Test
    fun rejectedPublickeyAuthenticationStopsRetryLoop() {
        val host = requireNotNull(arguments.getString("host")) { "Missing host" }
        val trustedBase64 = requireNotNull(arguments.getString("host_key_base64")) {
            "Missing independently trusted SSH server host-key blob"
        }
        val sshPort = arguments.getString("port")?.toIntOrNull() ?: 22
        val remotePort = arguments.getString("remote_port")?.toIntOrNull() ?: 10909
        val trusted = Base64.decode(trustedBase64, Base64.DEFAULT)

        // The actual host must still match the trusted key, or this test is invalid.
        val observed = VerifiedSshHostKey.probe(host, sshPort)
        assertArrayEquals("Cannot test auth against an unexpected sshd host key",
            trusted, observed.blob)

        val original = AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob()
        val profileBefore = DirectBootTunnelProfile.load(context)
        val desiredBefore = DirectBootTunnelProfile.wantsConnection(context)
        val nonexistentUser = "aap2_no_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val config = DirectBootTunnelProfile.Config(
            host, sshPort, nonexistentUser, remotePort, trusted
        )
        val errors = LinkedBlockingQueue<String>()
        val manager = KeystoreSshTunnelManager(context, config, localPort = 0)
        manager.onFatalError = { errors.offer(it) }
        try {
            manager.start()
            val error = errors.poll(25, TimeUnit.SECONDS)
            assertNotNull("Unknown SSH user must produce a fatal auth error", error)
            assertTrue("Authentication rejection must be fatal, got: " + error,
                error!!.startsWith("SSH rejected:"))
            // A fatal auth error must terminate the loop, not trigger repeated
            // login attempts which would cause a fail2ban problem.
            Thread.sleep(6_000)
            assertTrue("Fatal authentication must not be retried", errors.isEmpty())
        } finally {
            manager.destroy()
        }

        assertArrayEquals(original, AndroidKeystoreIdentity.getExisting(context).getPublicKeyBlob())
        val profileAfter = DirectBootTunnelProfile.load(context)
        assertEquals(profileBefore == null, profileAfter == null)
        if (profileBefore != null && profileAfter != null) {
            assertArrayEquals(profileBefore.verifiedHostKey, profileAfter.verifiedHostKey)
            assertEquals(profileBefore.username, profileAfter.username)
            assertEquals(profileBefore.remotePort, profileAfter.remotePort)
        }
        assertEquals(desiredBefore, DirectBootTunnelProfile.wantsConnection(context))
        Log.i("AAP2-P0", "AUTH_REJECTED_FATAL_NO_RETRY")
    }

    /**
     * One-time explicit opt-in for automatic SSH before first unlock.
     * The trusted fingerprint and raw host key must be sourced from the VM console;
     * never provision the unverified value captured by a first-seen network probe.
     */
    @Test
    fun provisionDirectBootTunnel() {
        val host = requireNotNull(arguments.getString("host")) { "Missing host" }
        val user = requireNotNull(arguments.getString("user")) { "Missing user" }
        val hostKey64 = requireNotNull(arguments.getString("host_key_base64")) {
            "Missing trusted host key"
        }
        val trustedFingerprint = requireNotNull(arguments.getString("trusted_sha256")) {
            "Missing independent SHA256 fingerprint"
        }
        val port = arguments.getString("port")?.toIntOrNull() ?: 22
        val remotePort = arguments.getString("remote_port")?.toIntOrNull() ?: 10909
        val key = Base64.decode(hostKey64, Base64.DEFAULT)

        // Test the currently reachable host against the independently trusted key
        // before committing the profile. No userauth during this KEX probe.
        val observed = VerifiedSshHostKey.probe(host, port)
        assertArrayEquals("SSH server key not trusted", key, observed.blob)

        DirectBootTunnelProfile.provision(
            context,
            DirectBootTunnelProfile.Config(host, port, user, remotePort, key),
            trustedFingerprint
        )
        val loaded = requireNotNull(DirectBootTunnelProfile.load(context))
        assertArrayEquals(key, loaded.verifiedHostKey)
        assertTrue(DirectBootTunnelProfile.wantsConnection(context))
        Log.i("AAP2-P0", "BOOT_PROFILE_CONFIGURED port=" + remotePort)
    }

    @Test
    fun disableDirectBootTunnel() {
        DirectBootTunnelProfile.disable(context)
        assertTrue(!DirectBootTunnelProfile.hasProfile(context))
        Log.i("AAP2-P0", "BOOT_PROFILE_DISABLED")
    }

}
