package net.isaeff.android.asproxy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * RFC 5656 ECDSA P-256 public key and signature wire format.
 * Pure JVM code; Keystore signing stays in AndroidKeystoreIdentity.
 */
internal object SshEcWire {
    const val ALGORITHM = "ecdsa-sha2-nistp256"
    private const val CURVE = "nistp256"
    private const val FIELD_BYTES = 32

    fun publicKeyBlob(publicKey: ECPublicKey): ByteArray {
        require(publicKey.params.curve.field.fieldSize == 256) { "Expected an EC P-256 key" }
        val point = byteArrayOf(0x04) +
            fixedWidth(publicKey.w.affineX) +
            fixedWidth(publicKey.w.affineY)
        return sshString(ALGORITHM.toByteArray(Charsets.US_ASCII)) +
            sshString(CURVE.toByteArray(Charsets.US_ASCII)) +
            sshString(point)
    }

    /** Transform Android/JCA DER SEQUENCE(r,s) into SSH string(algorithm) + string(mpint(r),mpint(s)). */
    fun signatureBlob(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        val sequence = reader.element(0x30)
        require(reader.finished()) { "Trailing ECDSA DER data" }
        val pair = DerReader(sequence)
        val r = pair.element(0x02)
        val s = pair.element(0x02)
        require(pair.finished()) { "Trailing ECDSA integers" }
        require(r.isNotEmpty() && r.size <= 33 && s.isNotEmpty() && s.size <= 33) {
            "Invalid P-256 signature size"
        }
        val encodedPair = sshString(BigInteger(1, r).toByteArray()) +
            sshString(BigInteger(1, s).toByteArray())
        return sshString(ALGORITHM.toByteArray(Charsets.US_ASCII)) + sshString(encodedPair)
    }

    fun hostKeyAlgorithm(blob: ByteArray): String {
        require(blob.size >= 5) { "Truncated SSH host key" }
        val size = ((blob[0].toInt() and 255) shl 24) or
            ((blob[1].toInt() and 255) shl 16) or
            ((blob[2].toInt() and 255) shl 8) or (blob[3].toInt() and 255)
        require(size in 1..128 && 4 + size <= blob.size) { "Malformed SSH host key" }
        return String(blob, 4, size, Charsets.US_ASCII)
    }

    private fun fixedWidth(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "Negative EC coordinate" }
        val raw = value.toByteArray()
        val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= FIELD_BYTES) { "EC coordinate too large" }
        return ByteArray(FIELD_BYTES - unsigned.size) + unsigned
    }

    private fun sshString(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + 4)
        DataOutputStream(output).use { stream ->
            stream.writeInt(data.size)
            stream.write(data)
        }
        return output.toByteArray()
    }

    private class DerReader(private val source: ByteArray) {
        private var pos = 0
        fun finished(): Boolean = pos == source.size

        fun element(tag: Int): ByteArray {
            require(pos < source.size && (source[pos++].toInt() and 255) == tag) { "Unexpected DER tag" }
            require(pos < source.size) { "Truncated DER length" }
            val first = source[pos++].toInt() and 255
            val count = first and 127
            val size = if (first < 128) first else {
                require(count in 1..2 && pos + count <= source.size) { "Invalid DER length" }
                var n = 0
                repeat(count) { n = (n shl 8) or (source[pos++].toInt() and 255) }
                require(n >= 128) { "Non-minimal DER length" }
                n
            }
            require(size >= 0 && size <= source.size - pos) { "DER length exceeds buffer" }
            val data = source.copyOfRange(pos, pos + size)
            pos += size
            return data
        }
    }
}
