package net.isaeff.android.asproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SshEcWireTest {
    private fun sshString(source: ByteBuffer): ByteArray {
        val length = source.int
        require(length >= 0 && length <= source.remaining())
        return ByteArray(length).also(source::get)
    }

    @Test
    fun p256PublicKeyHasExpectedSshStructure() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as java.security.interfaces.ECPublicKey
        val buffer = ByteBuffer.wrap(SshEcWire.publicKeyBlob(publicKey))

        assertEquals("ecdsa-sha2-nistp256", sshString(buffer).toString(Charsets.US_ASCII))
        assertEquals("nistp256", sshString(buffer).toString(Charsets.US_ASCII))
        val point = sshString(buffer)
        assertEquals(65, point.size)
        assertEquals(4, point[0].toInt())
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun derSignatureProducesSshEcdsaPair() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val signedData = "aap2-test-signature".toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(signedData)
        val der = signer.sign()

        val ssh = ByteBuffer.wrap(SshEcWire.signatureBlob(der))
        assertEquals("ecdsa-sha2-nistp256", sshString(ssh).toString(Charsets.US_ASCII))
        val pairBuffer = ByteBuffer.wrap(sshString(ssh))
        val r = java.math.BigInteger(sshString(pairBuffer))
        val s = java.math.BigInteger(sshString(pairBuffer))
        assertEquals(0, pairBuffer.remaining())
        assertEquals(0, ssh.remaining())
        assertEquals(1, r.signum())
        assertEquals(1, s.signum())

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(signedData)
        org.junit.Assert.assertTrue(verifier.verify(der))
    }

    @Test
    fun rejectsCorruptDer() {
        assertThrows(IllegalArgumentException::class.java) {
            SshEcWire.signatureBlob(byteArrayOf(0x30, 0x05, 0x02, 0x01, 0x01))
        }
    }

    @Test
    fun parsesHostKeyAlgorithmFromSshBlob() {
        val alg = "ssh-ed25519".toByteArray()
        val blob = ByteBuffer.allocate(4 + alg.size + 4).putInt(alg.size).put(alg).putInt(0).array()
        assertEquals("ssh-ed25519", SshEcWire.hostKeyAlgorithm(blob))
        assertThrows(IllegalArgumentException::class.java) {
            SshEcWire.hostKeyAlgorithm(byteArrayOf(0, 0, 1, 1))
        }
    }
}
