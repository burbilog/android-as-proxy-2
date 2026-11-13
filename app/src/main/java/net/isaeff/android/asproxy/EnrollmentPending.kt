package net.isaeff.android.asproxy

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Crash recovery between SFTP upload and admin Approve.
 * Contains NO PIN/password/private key. The host blob is only written
 * AFTER explicit human verification, never from SFTP config.json.
 */
internal object EnrollmentPending {
    private const val NAME = "aap2-enrollment-pending-v1"

    data class Info(
        val host: String,
        val sshPort: Int,
        val permanentUser: String,
        val remotePort: Int,
        val verifiedHostKey: ByteArray
    ) {
        fun profile() = DirectBootTunnelProfile.Config(
            host, sshPort, permanentUser, remotePort, verifiedHostKey.copyOf()
        )
        fun sameAs(other: Info): Boolean =
            host == other.host && sshPort == other.sshPort &&
                permanentUser == other.permanentUser && remotePort == other.remotePort &&
                MessageDigest.isEqual(verifiedHostKey, other.verifiedHostKey)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Info? {
        val p = prefs(context)
        if (!p.getBoolean("pending", false)) return null
        val result = Info(
            p.getString("host", null) ?: error("Pending host missing"),
            p.getInt("port", 0),
            p.getString("user", null) ?: error("Pending user missing"),
            p.getInt("remote_port", 0),
            Base64.decode(p.getString("host_key", null) ?: error("Pending host key missing"),
                Base64.DEFAULT)
        )
        require(result.sshPort in 1..65535 && result.remotePort in 1024..65535 &&
            result.host.isNotBlank() &&
            result.permanentUser.matches(Regex("aap2p[0-9a-f]{12}"))) {
            "Corrupted pending enrollment"
        }
        SshEcWire.hostKeyAlgorithm(result.verifiedHostKey)
        return result
    }

    fun save(context: Context, info: Info) {
        val previous = load(context)
        check(previous == null || previous.sameAs(info)) {
            "Different enrollment already pending; do not overwrite trusted host/key"
        }
        check(prefs(context).edit()
            .putString("host", info.host).putInt("port", info.sshPort)
            .putString("user", info.permanentUser).putInt("remote_port", info.remotePort)
            .putString("host_key",
                Base64.encodeToString(info.verifiedHostKey, Base64.NO_WRAP))
            .putBoolean("pending", true).commit()) {
            "Could not persist pending enrollment before SFTP upload"
        }
    }

    fun clear(context: Context) {
        check(prefs(context).edit().clear().commit()) {
            "Could not clear completed pending enrollment"
        }
    }
}
