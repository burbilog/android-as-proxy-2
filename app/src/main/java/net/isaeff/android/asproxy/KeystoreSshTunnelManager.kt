package net.isaeff.android.asproxy

import android.content.Context
import android.util.Log
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Direct-Boot-safe SSH loop: no credential-encrypted files, password login, TOFU,
 * shell execution, or key generation. Designed for the P0 test sshd, not enrollment.
 */
internal class KeystoreSshTunnelManager(
    private val context: Context,
    private val profile: DirectBootTunnelProfile.Config,
    private val localPort: Int
) {
    var onFatalError: ((String) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var session: Session? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val identity = try {
                AndroidKeystoreIdentity.getExisting(context)
            } catch (e: Exception) {
                fatal("Keystore key unavailable: " + e.javaClass.simpleName)
                return@launch
            }
            var retryMs = 5_000L
            while (isActive) {
                var next: Session? = null
                try {
                    ConnectionStateHolder.setState(ConnectionState.CONNECTING)
                    val jsch = VerifiedSshHostKey.pinnedJsch(
                        profile.host, profile.sshPort, profile.verifiedHostKey
                    )
                    jsch.addIdentity(identity, null)
                    next = jsch.getSession(profile.username, profile.host, profile.sshPort)
                    session = next
                    next.setConfig("StrictHostKeyChecking", "yes")
                    next.setConfig("PreferredAuthentications", "publickey")
                    next.setConfig("NumberOfPasswordPrompts", "0")
                    next.setServerAliveInterval(15_000)
                    next.setServerAliveCountMax(2)
                    next.connect(30_000)
                    coroutineContext.ensureActive()

                    next.setPortForwardingR("127.0.0.1", profile.remotePort, "127.0.0.1", localPort)
                    coroutineContext.ensureActive()
                    ConnectionStateHolder.setState(ConnectionState.CONNECTED)
                    Log.i("AAP2-P0", "BOOT_FORWARD_READY 127.0.0.1:" + profile.remotePort)
                    AAPLog.append("Keystore SSH reverse forward ready")
                    retryMs = 5_000L
                    while (isActive) {
                        delay(5_000L)
                        if (!next.isConnected) throw JSchException("SSH connection lost")
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    // A changed pinned key or failed authentication must NOT cause
                    // repeated login attempts, fail2ban bans, or accidental re-pinning.
                    val message = e.message.orEmpty()
                    if (e !is JSchException ||
                        message.contains("HostKey", ignoreCase = true) ||
                        message.contains("auth fail", ignoreCase = true) ||
                        message.contains("auth cancel", ignoreCase = true) ||
                        message.contains("USERAUTH", ignoreCase = true)) {
                        fatal("SSH rejected: " + e.javaClass.simpleName)
                        break
                    }
                    Log.w("AAP2-P0", "BOOT_SSH_RETRY " + e.javaClass.simpleName)
                } finally {
                    next?.disconnect()
                    session = null
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                }
                if (!isActive) break
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(120_000L)
            }
        }
    }

    private fun fatal(message: String) {
        Log.e("AAP2-P0", "BOOT_SSH_FATAL " + message)
        AAPLog.append(message)
        onFatalError?.invoke(message)
    }

    fun destroy() {
        job?.cancel()
        session?.disconnect()
        session = null
        scope.cancel()
    }
}
