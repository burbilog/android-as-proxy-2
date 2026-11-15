package net.isaeff.android.asproxy

import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest

/**
 * Pre-auth SSH host-key probe. JSch verifies the KEX signature and calls
 * HostKeyRepository.check() BEFORE any userauth request. The repository always rejects,
 * so probe() can never send a password, PIN, or publickey authentication.
 *
 * The returned SHA256 fingerprint is only a candidate until an administrator verifies it
 * through a separate trusted channel. Do not auto-accept or persist it here.
 */
object VerifiedSshHostKey {
    data class Probe(val host: String, val port: Int, val blob: ByteArray) {
        val algorithm: String get() = SshEcWire.hostKeyAlgorithm(blob)
        val fingerprint: String get() = sha256Fingerprint(blob)
        val base64: String get() = Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun probe(host: String, port: Int, timeoutMs: Int = 15_000): Probe {
        require(host.isNotBlank() && port in 1..65535)
        val repository = ProbeRepository(hostId(host, port))
        val jsch = JSch().apply { setHostKeyRepository(repository) }
        val session = jsch.getSession("aap2-host-key-probe", host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "none")
        session.setConfig("NumberOfPasswordPrompts", "0")
        var failure: JSchException? = null
        var connected = false
        try {
            session.connect(timeoutMs)
            connected = true
        } catch (e: JSchException) {
            failure = e
        } finally {
            session.disconnect()
        }
        check(!connected) { "Host-key probe unexpectedly authenticated" }
        val blob = repository.captured
            ?: throw (failure ?: JSchException("No authenticated host key received"))
        return Probe(host, port, blob)
    }

    /** Can only be used after separate, explicit fingerprint verification. */
    fun pinnedJsch(host: String, port: Int, verifiedBlob: ByteArray): JSch {
        require(verifiedBlob.isNotEmpty() && port in 1..65535)
        SshEcWire.hostKeyAlgorithm(verifiedBlob)
        return JSch().apply {
            setHostKeyRepository(PinnedRepository(hostId(host, port), verifiedBlob.copyOf()))
        }
    }

    fun sha256Fingerprint(keyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        return "SHA256:" + Base64.encodeToString(
            digest, Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun hostId(host: String, port: Int): String =
        if (port == 22) host else "[" + host + "]:" + port

    private abstract class ReadOnlyRepository : HostKeyRepository {
        override fun getKnownHostsRepositoryID(): String = "aap2-verified-host"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        override fun add(hostkey: HostKey?, ui: UserInfo?) {
            throw UnsupportedOperationException("Host key is never automatically accepted")
        }
        override fun remove(host: String?, type: String?) {
            throw UnsupportedOperationException("Host keys cannot be removed by JSch")
        }
        override fun remove(host: String?, type: String?, key: ByteArray?) {
            throw UnsupportedOperationException("Host keys cannot be removed by JSch")
        }
    }

    private class ProbeRepository(private val expectedHostId: String) : ReadOnlyRepository() {
        @Volatile var captured: ByteArray? = null
            private set

        override fun check(host: String?, key: ByteArray?): Int {
            if (host == expectedHostId && key != null) captured = key.copyOf()
            return HostKeyRepository.CHANGED // KEX finishes, userauth never starts
        }
    }

    private class PinnedRepository(
        private val expectedHostId: String,
        private val verifiedBlob: ByteArray
    ) : ReadOnlyRepository() {
        override fun check(host: String?, key: ByteArray?): Int =
            if (host == expectedHostId && key != null &&
                MessageDigest.isEqual(verifiedBlob, key)
            ) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
}
