package net.isaeff.android.asproxy

import android.content.Context
import android.os.SystemClock
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * S2 server protocol, using two SSH connections:
 *  1) VerifiedSshHostKey.probe() has already completed KEX, captured the
 *     candidate blob and REJECTED authentication (no PIN sent).
 *  2) Only after explicit Verified from UI, a STRICT pinned SSH session
 *     authenticates the ten-digit PIN, using only internal-sftp.
 *
 * The server supplies username/port, never the host-key pin. After approval
 * we verify actual publickey SSH + exact -R before committing Direct Boot.
 */
internal class EnrollmentClient(private val context: Context) {
    internal data class ServerConfig(val permanentUser: String, val remotePort: Int)
    internal data class ServerStatus(val status: String, val expiresAt: Long)

    suspend fun enroll(
        probe: VerifiedSshHostKey.Probe,
        setupUser: String,
        typedPin: String,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        check(!DirectBootTunnelProfile.hasProfile(context)) {
            "This device already has a working AAP2 profile; refusing to replace it"
        }
        val user = EnrollmentInput.setupUser(setupUser)
        val pin = EnrollmentInput.pin(typedPin)
        val host = probe.host
        val sshPort = probe.port
        val verifiedBlob = probe.blob.copyOf()
        val expectedFingerprint = probe.fingerprint
        val jsch = VerifiedSshHostKey.pinnedJsch(host, sshPort, verifiedBlob)
        val session = jsch.getSession(user, host, sshPort)
        var channel: ChannelSftp? = null
        var uploaded = false
        var proposed: EnrollmentPending.Info? = null
        var approved = false

        try {
            emit(onProgress, "Connecting with verified server key…")
            // No password is set until this function, reached only by explicit
            // Verified button. HostKeyRepository checks the exact pinned blob
            // before JSch attempts any password authentication.
            session.setPassword(pin)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password")
            session.setConfig("NumberOfPasswordPrompts", "1")
            session.connect(20_000)

            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            val config = parseConfig(readLimited(channel.get("config.json"), 2048))
            val identity = AndroidKeystoreIdentity.getOrCreate(context)
            val pending = EnrollmentPending.Info(
                host, sshPort, config.permanentUser, config.remotePort, verifiedBlob
            )
            // Save permanent login information BEFORE upload. No PIN or
            // private key ever enters the pending state or Direct Boot prefs.
            EnrollmentPending.save(context, pending)
            proposed = pending

            emit(onProgress, "Uploading public key; private key stays on the phone…")
            val pub = (identity.authorizedKey() + "\n").toByteArray(Charsets.US_ASCII)
            channel.put(ByteArrayInputStream(pub), "inbox/public_key", ChannelSftp.OVERWRITE)
            uploaded = true

            // One SFTP session; status polling, never SSH exec or shell.
            // Timer is based on elapsed time to avoid the phone wall clock
            // differing from the SSH server's time.
            // Manual 6-digit invitations allow more time for human entry and
            // administrator approval. Legacy invitations keep their 10-min cap.
            val approvalWindowMs = if (user.matches(Regex("aap2e[0-9]{6}")))
                1_200_000L else 600_000L
            val deadline = SystemClock.elapsedRealtime() + approvalWindowMs
            emit(onProgress, "Waiting for administrator approval…")
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = parseStatus(readLimited(channel.get("status.json"), 2048))
                if (status.status == "approved") {
                    approved = true
                    break
                }
                if (status.status != "pending") {
                    error("Unexpected enrollment status")
                }
                delay(3_000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!uploaded || proposed == null) throw e
            // SFTP can be torn down after approval / at PIN expiry. Make ONE
            // publickey attempt, never an automatic auth retry loop.
            emit(onProgress, "SFTP interrupted; checking permanent key once…")
        } finally {
            channel?.disconnect()
            session.disconnect()
        }

        val pending = proposed ?: error("Server did not provide valid enrollment config")
        if (!approved && !uploaded) error("Enrollment key not uploaded")
        emit(onProgress, "Checking permanent SSH access and reverse-forward port…")
        try {
            verifyPermanentTunnel(pending)
        } catch (e: Exception) {
            // Keep non-secret pending state and SAME Android Keystore identity;
            // the user can resume after admin approval without resending PIN.
            throw IllegalStateException(
                "Permanent SSH not approved/ready yet. After admin approval, use Resume; " +
                    "do not create a new key or repeatedly retry the PIN.", e
            )
        }
        finish(pending, expectedFingerprint)
        emit(onProgress, "Enrollment verified. Starting AAP2 tunnel…")
    }

    suspend fun resume(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(!DirectBootTunnelProfile.hasProfile(context)) {
            "AAP2 is already configured; no recovery needed"
        }
        val pending = EnrollmentPending.load(context)
            ?: error("No pending enrollment to resume")
        emit(onProgress, "Checking existing pinned host/key without a PIN…")
        verifyPermanentTunnel(pending) // exactly ONE auth attempt
        finish(pending, VerifiedSshHostKey.sha256Fingerprint(pending.verifiedHostKey))
        emit(onProgress, "Recovered approved enrollment; starting tunnel…")
    }

    private fun finish(info: EnrollmentPending.Info, expectedFingerprint: String) {
        check(!DirectBootTunnelProfile.hasProfile(context)) {
            "Configured profile changed during enrollment; refusing overwrite"
        }
        // Only after actual publickey auth and allowed -R succeeded.
        DirectBootTunnelProfile.provision(context, info.profile(), expectedFingerprint)
        EnrollmentPending.clear(context)
    }

    internal fun verifyPermanentTunnel(info: EnrollmentPending.Info) {
        val identity = AndroidKeystoreIdentity.getExisting(context)
        val jsch = VerifiedSshHostKey.pinnedJsch(
            info.host, info.sshPort, info.verifiedHostKey
        )
        jsch.addIdentity(identity, null)
        val session: Session = jsch.getSession(info.permanentUser, info.host, info.sshPort)
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("NumberOfPasswordPrompts", "0")
            session.connect(20_000)
            // The actual OpenSSH permitlisten policy is tested, not inferred
            // from status.json. The local target needs no listener until use.
            session.setPortForwardingR("127.0.0.1", info.remotePort, "127.0.0.1", 11080)
        } finally {
            session.disconnect()
        }
    }

    private suspend fun emit(callback: (String) -> Unit, text: String) {
        withContext(Dispatchers.Main) { callback(text) }
    }

    private fun readLimited(stream: InputStream, maximum: Int): ByteArray = stream.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (true) {
            val count = it.read(buf)
            if (count < 0) break
            require(out.size() + count <= maximum) { "Oversized server SFTP JSON" }
            out.write(buf, 0, count)
        }
        out.toByteArray()
    }

    // JSONObject.getInt/getLong silently coerce fractional or string values.
    // S2 protocol fields are exact JSON integers, not coercible numbers.
    private fun exactPositiveInteger(obj: JSONObject, key: String): Long {
        val value = obj.get(key)
        require(value is Number && value !is Float && value !is Double) {
            "Expected integer JSON field: $key"
        }
        return value.toString().toLongOrNull()
            ?: throw IllegalArgumentException("Invalid integer JSON field: $key")
    }

    internal fun parseConfig(data: ByteArray): ServerConfig {
        val obj = JSONObject(data.toString(Charsets.UTF_8))
        require(obj.length() == 3 && obj.has("schema") &&
            obj.has("username") && obj.has("remote_port")) {
            "Unexpected enrollment config fields"
        }
        require(exactPositiveInteger(obj, "schema") == 1L) {
            "Unsupported enrollment schema"
        }
        val username = obj.getString("username")
        require(username.matches(Regex("aap2p(?:[0-9]{6}|[0-9a-f]{12})"))) {
            "Unexpected permanent username"
        }
        require(exactPositiveInteger(obj, "remote_port") in 11010L..11509L) {
            "Unexpected reserved reverse-forward port"
        }
        return ServerConfig(username, exactPositiveInteger(obj, "remote_port").toInt())
    }

    internal fun parseStatus(data: ByteArray): ServerStatus {
        val obj = JSONObject(data.toString(Charsets.UTF_8))
        require(obj.length() == 3 && obj.has("schema") &&
            obj.has("status") && obj.has("expires_at")) {
            "Unexpected enrollment status fields"
        }
        require(exactPositiveInteger(obj, "schema") == 1L)
        require(exactPositiveInteger(obj, "expires_at") > 0L)
        val status = obj.getString("status")
        require(status in setOf("pending", "approved"))
        return ServerStatus(status, exactPositiveInteger(obj, "expires_at"))
    }
}
