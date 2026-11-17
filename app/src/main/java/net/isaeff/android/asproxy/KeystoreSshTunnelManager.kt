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
 * shell execution, or key generation. Uses the approved AAP2 enrollment profile.
 */
internal class KeystoreSshTunnelManager(
    private val context: Context,
    private val profile: DirectBootTunnelProfile.Config,
    private val localPort: Int
) {
    var onProblem: ((ConnectionIssueStore.Reason) -> Unit)? = null
    var onHealthy: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var session: Session? = null
    private var job: Job? = null
    private val lifecycleLock = Any()
    @Volatile private var destroyed = false
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)
    private var callbackRegistered = false
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var forwardReady = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            networkAvailable.trySend(Unit)
            // Same-network updates must not repeatedly tear down the tunnel.
            // On a real default-network switch, the existing socket is bound
            // to the old network. Disconnect once and let the normal SSH loop
            // reestablish the pinned, approved reverse forward.
            if (previous != null && previous != network && forwardReady) {
                Log.i("AAP2-SSH", "Default network changed; reconnecting SSH")
                session?.disconnect()
            }
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                defaultNetwork = null
                if (forwardReady) {
                    Log.i("AAP2-SSH", "Default network lost; reconnecting SSH")
                    session?.disconnect()
                }
            }
        }
    }

    // Serialize state/problem publication with destroy(). A cancelled, old
    // manager must not update the UI or persistent issue after service shutdown.
    private fun publishWhileRunning(block: () -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (destroyed) false else {
                block()
                true
            }
        }

    fun start() {
        if (destroyed || job?.isActive == true) return
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = connectivityManager != null
        } catch (e: RuntimeException) {
            // Retry timer continues to work even if network callbacks are unavailable.
            Log.w("AAP2-P0", "BOOT_NETWORK_CALLBACK_UNAVAILABLE " + e.javaClass.simpleName)
        }
        job = scope.launch {
            // Keep a successfully loaded Keystore handle across ordinary network
            // retries; getExisting() also synchronously persists its marker.
            var identity: AndroidKeystoreIdentity? = null
            var retryMs = 5_000L
            val retrySequence = SshFailurePolicy.RetrySequence()
            while (isActive) {
                // Discard old availability notifications from previous successful attempts.
                // Keep events arriving during connect(): they should wake the next retry.
                networkAvailable.tryReceive()
                var next: Session? = null
                var hourlyRetry = false
                var pinned: VerifiedSshHostKey.PinnedClient? = null
                var loadingIdentity = identity == null
                try {
                    if (!publishWhileRunning {
                            ConnectionStateHolder.setState(ConnectionState.CONNECTING)
                        }) break
                    // Retry failed Keystore loads hourly. A successfully loaded
                    // key is reused until a local error; no key is ever generated.
                    val sshIdentity = identity ?: AndroidKeystoreIdentity.getExisting(context)
                        .also { identity = it }
                    loadingIdentity = false
                    pinned = VerifiedSshHostKey.pinnedJschWithCheck(
                        profile.host, profile.sshPort, profile.verifiedHostKey
                    )
                    val jsch = pinned.jsch
                    jsch.addIdentity(sshIdentity, null)
                    val attempt = jsch.getSession(profile.username, profile.host, profile.sshPort)
                    next = attempt
                    if (!publishWhileRunning { session = attempt }) break
                    coroutineContext.ensureActive()
                    VerifiedSshHostKey.configurePinnedHostKey(next, profile.verifiedHostKey)
                    next.setConfig("StrictHostKeyChecking", "yes")
                    next.setConfig("PreferredAuthentications", "publickey")
                    next.setConfig("NumberOfPasswordPrompts", "0")
                    next.setServerAliveInterval(15_000)
                    next.setServerAliveCountMax(2)
                    next.connect(30_000)
                    coroutineContext.ensureActive()

                    next.setPortForwardingR("127.0.0.1", profile.remotePort, "127.0.0.1", localPort)
                    coroutineContext.ensureActive()
                    if (!publishWhileRunning {
                            forwardReady = true
                            ConnectionStateHolder.setState(ConnectionState.CONNECTED)
                            onHealthy?.invoke()
                        }) break
                    retrySequence.reset()
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
                    if (!isActive || destroyed) break
                    // A rejected pinned key can NEVER authenticate or be replaced.
                    // Retry infrequently so a temporary wrong server or Keystore
                    // failure cannot permanently strand an unattended device.
                    val action = retrySequence.next(SshFailurePolicy.classify(
                        e.javaClass.simpleName, e.message, e is JSchException,
                        pinned?.hostKeyRejected == true
                    ))
                    when (action) {
                        SshFailurePolicy.Action.RETRY_HOST_IDENTITY_HOURLY,
                        SshFailurePolicy.Action.RETRY_LOCAL_HOURLY,
                        SshFailurePolicy.Action.RETRY_AUTH_HOURLY -> {
                            val reason = when (action) {
                                SshFailurePolicy.Action.RETRY_HOST_IDENTITY_HOURLY ->
                                    ConnectionIssueStore.Reason.HOST_KEY
                                SshFailurePolicy.Action.RETRY_LOCAL_HOURLY ->
                                    if (loadingIdentity) ConnectionIssueStore.Reason.KEYSTORE
                                    else ConnectionIssueStore.Reason.SERVICE
                                else -> ConnectionIssueStore.Reason.AUTH
                            }
                            if (action == SshFailurePolicy.Action.RETRY_LOCAL_HOURLY) {
                                // Retry with a fresh handle after a transient local error.
                                identity = null
                            }
                            if (!publishWhileRunning {
                                    onProblem?.invoke(reason)
                                    ConnectionStateHolder.setState(ConnectionState.RETRYING)
                                }) break
                            Log.w("AAP2-SSH", "SSH problem " + reason.name + "; retrying hourly")
                            hourlyRetry = true
                        }
                        SshFailurePolicy.Action.RETRY_NETWORK ->
                            Log.w("AAP2-SSH", "SSH reconnect after " + e.javaClass.simpleName)
                    }
                } finally {
                    forwardReady = false
                    next?.disconnect()
                    session = null
                }
                if (!isActive) break
                if (hourlyRetry) {
                    // Always close JSch sockets in finally BEFORE the long delay.
                    delay(SshFailurePolicy.HOURLY_RETRY_MS)
                    continue
                }
                // The foreground service is still running and will try again.
                // DISCONNECTED is reserved for an actually stopped service.
                if (!publishWhileRunning {
                        ConnectionStateHolder.setState(ConnectionState.RETRYING)
                    }) break
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

    fun destroy(): Job? {
        synchronized(lifecycleLock) {
            destroyed = true
            job?.cancel()
        }
        if (callbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (_: RuntimeException) {
                // Callback may already have been released by the framework.
            }
            callbackRegistered = false
        }
        forwardReady = false
        session?.disconnect()
        session = null
        scope.cancel()
        return job
    }
}
