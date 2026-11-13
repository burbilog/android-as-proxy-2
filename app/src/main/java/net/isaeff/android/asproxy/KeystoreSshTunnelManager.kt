package net.isaeff.android.asproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

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
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)
    private var callbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Wake the existing retry loop; never start a new service/SSH session here.
            networkAvailable.trySend(Unit)
            Log.i("AAP2-P0", "BOOT_NETWORK_AVAILABLE")
        }
    }

    fun start() {
        if (job?.isActive == true) return
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = connectivityManager != null
        } catch (e: RuntimeException) {
            // Retry timer continues to work even if network callbacks are unavailable.
            Log.w("AAP2-P0", "BOOT_NETWORK_CALLBACK_UNAVAILABLE " + e.javaClass.simpleName)
        }
        job = scope.launch {
            val identity = try {
                AndroidKeystoreIdentity.getExisting(context)
            } catch (e: Exception) {
                fatal("Keystore key unavailable: " + e.javaClass.simpleName)
                return@launch
            }
            var retryMs = 5_000L
            while (isActive) {
                // Discard old availability notifications from previous successful attempts.
                // Keep events arriving during connect(): they should wake the next retry.
                networkAvailable.tryReceive()
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
                // Wait for either backoff expiry or a new default network (e.g. Wi-Fi
                // returns). A network change resets backoff; an offline server does not.
                val wokeOnNetwork = withTimeoutOrNull(retryMs) {
                    networkAvailable.receive()
                } != null
                if (wokeOnNetwork) {
                    retryMs = 5_000L
                    Log.i("AAP2-P0", "BOOT_RETRY_NETWORK_WAKE")
                } else {
                    retryMs = (retryMs * 2).coerceAtMost(120_000L)
                }
            }
        }
    }

    private fun fatal(message: String) {
        Log.e("AAP2-P0", "BOOT_SSH_FATAL " + message)
        AAPLog.append(message)
        onFatalError?.invoke(message)
    }

    fun destroy() {
        if (callbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (_: RuntimeException) {
                // Callback may already have been released by the framework.
            }
            callbackRegistered = false
        }
        job?.cancel()
        session?.disconnect()
        session = null
        scope.cancel()
    }
}
