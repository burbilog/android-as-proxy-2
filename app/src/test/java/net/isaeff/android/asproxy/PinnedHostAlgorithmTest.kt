package net.isaeff.android.asproxy

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedHostAlgorithmTest {
    private fun blob(algorithm: String): ByteArray {
        val a = algorithm.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0, 0, 0, a.size.toByte()) + a + byteArrayOf(1)
    }

    @Test fun pinnedEd25519DoesNotOfferNewEcdsaKeys() {
        assertEquals("ssh-ed25519", VerifiedSshHostKey.hostKeyNegotiationAlgorithms(blob("ssh-ed25519")))
        assertEquals("ecdsa-sha2-nistp256",
            VerifiedSshHostKey.hostKeyNegotiationAlgorithms(blob("ecdsa-sha2-nistp256")))
    }

    @Test fun rsaUsesModernSignaturesWithoutSha1() {
        assertEquals("rsa-sha2-512,rsa-sha2-256",
            VerifiedSshHostKey.hostKeyNegotiationAlgorithms(blob("ssh-rsa")))
    }

    @Test fun pinnedRepositoryReportsRejectionWithoutChangingPin() {
        val expected = blob("ssh-ed25519")
        val client = VerifiedSshHostKey.pinnedJschWithCheck("test.example", 22, expected)
        val repository = client.jsch.hostKeyRepository
        assertFalse(client.hostKeyRejected)
        assertEquals(HostKeyRepository.OK, repository.check("test.example", expected))
        assertFalse(client.hostKeyRejected)
        assertEquals(HostKeyRepository.CHANGED,
            repository.check("test.example", blob("ecdsa-sha2-nistp256")))
        assertTrue(client.hostKeyRejected)
        assertEquals(HostKeyRepository.OK, repository.check("test.example", expected))
        assertTrue("The rejection flag must survive subsequent checks", client.hostKeyRejected)
    }

    @Test fun unknownAlgorithmFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedSshHostKey.hostKeyNegotiationAlgorithms(blob("ssh-dss"))
        }
    }
}
