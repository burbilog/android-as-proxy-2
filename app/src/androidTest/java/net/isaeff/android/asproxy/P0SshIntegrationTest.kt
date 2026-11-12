package net.isaeff.android.asproxy

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSchException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.Signature

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
}
