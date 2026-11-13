package net.isaeff.android.asproxy

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Only non-secret boot-critical SSH configuration, stored in device-protected storage.
 * The private SSH key remains in Android Keystore; passwords and enrollment PINs
 * never enter this store. Provisioning requires a host key verified out of band.
 */
internal object DirectBootTunnelProfile {
    private const val PREFS = "aap2-direct-boot-v1"

    data class Config(
        val host: String,
        val sshPort: Int,
        val username: String,
        val remotePort: Int,
        val verifiedHostKey: ByteArray
    )

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True only for an explicitly committed profile that has not been stopped. */
    fun wantsConnection(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean("configured", false) && p.getBoolean("desired_running", false)
    }

    fun hasProfile(context: Context): Boolean =
        prefs(context).getBoolean("configured", false)

    fun load(context: Context): Config? {
        val p = prefs(context)
        if (!p.getBoolean("configured", false)) return null
        val config = Config(
            host = p.getString("host", null) ?: error("Boot SSH host missing"),
            sshPort = p.getInt("port", 0),
            username = p.getString("user", null) ?: error("Boot SSH user missing"),
            remotePort = p.getInt("remote_port", 0),
            verifiedHostKey = Base64.decode(
                p.getString("host_key", null) ?: error("Pinned host key missing"),
                Base64.DEFAULT
            )
        )
        validate(config)
        return config
    }

    /** The trusted SHA256 fingerprint must come from an independent channel (e.g., sshd console). */
    fun provision(context: Context, config: Config, expectedFingerprint: String) {
        validate(config)
        check(expectedFingerprint.startsWith("SHA256:") &&
            expectedFingerprint == VerifiedSshHostKey.sha256Fingerprint(config.verifiedHostKey)) {
            "Provided host key does not match independently verified SHA256 fingerprint"
        }
        // Never silently create a new identity in an unattended boot profile.
        AndroidKeystoreIdentity.getExisting(context)
        check(prefs(context).edit()
            .putString("host", config.host)
            .putInt("port", config.sshPort)
            .putString("user", config.username)
            .putInt("remote_port", config.remotePort)
            .putString("host_key", Base64.encodeToString(config.verifiedHostKey, Base64.NO_WRAP))
            .putBoolean("configured", true)
            .putBoolean("desired_running", true)
            .commit()) { "Could not persist Direct Boot configuration" }
    }

    fun setDesiredRunning(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean("desired_running", enabled).commit()) {
            "Could not persist Direct Boot desired state"
        }
    }

    /** Debug/test teardown; never deletes the Android Keystore identity. */
    fun disable(context: Context) {
        check(prefs(context).edit().clear().commit()) {
            "Could not clear Direct Boot configuration"
        }
    }

    private fun validate(config: Config) {
        require(config.host.isNotBlank() && config.host.length <= 253 &&
            config.host.none { it.isWhitespace() || it == '/' }) { "Invalid SSH host" }
        require(config.username.matches(Regex("[a-z_][a-z0-9_-]{0,31}"))) {
            "Invalid SSH username"
        }
        require(config.sshPort in 1..65535 && config.remotePort in 1024..65535) {
            "Invalid SSH or reverse-forward port"
        }
        require(config.verifiedHostKey.size in 32..8192) { "Invalid pinned host key size" }
        val algorithm = SshEcWire.hostKeyAlgorithm(config.verifiedHostKey)
        require(algorithm in setOf("ssh-ed25519", "ecdsa-sha2-nistp256", "rsa-sha2-256", "rsa-sha2-512", "ssh-rsa")) {
            "Unsupported host key algorithm"
        }
    }
}
