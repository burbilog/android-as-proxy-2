package net.isaeff.android.asproxy

import android.content.Context
import com.jcraft.jsch.JSch
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
import java.io.ByteArrayInputStream

/**
 * A single SSH session with a single loopback-only remote TCP forward.
 *
 * No remote exec, shell commands, port cleanup, weak cipher fallbacks or wildcard listeners.
 * On tunnel loss, the owning Android service is notified exactly once; it owns retry policy.
 */
class SSHTunnelManager(
    private val context: Context,
    private val sshUser: String,
    private val sshHost: String,
    private val sshPort: Int,
    private val sshPassword: String,
    private val remotePort: Int,
    private val localHost: String = "127.0.0.1",
    private val localPort: Int = 1080
) {
    companion object {
        private const val PREFS_NAME = "aap_prefs"
        private const val SERVER_KEY = "server_key"
        private const val CONNECT_TIMEOUT_MS = 30_000
    }

    var onError: ((String) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    @Volatile private var session: Session? = null
    @Volatile private var stopping = false
    private var tunnelJob: Job? = null

    @Synchronized
    fun startSSHTunnel() {
        if (tunnelJob?.isActive == true) return
        require(remotePort in 1..65535) { "Invalid remote port" }
        require(sshPort in 1..65535) { "Invalid SSH port" }

        stopping = false
        tunnelJob = scope.launch {
            try {
                ConnectionStateHolder.setState(ConnectionState.CONNECTING)
                val jsch = JSch()
                val savedHostKey = prefs.getString(SERVER_KEY, null)
                val hostId = if (sshPort == 22) sshHost else "[${sshHost}]:$sshPort"
                if (!savedHostKey.isNullOrBlank()) {
                    val knownHosts = "$hostId $savedHostKey\n"
                    jsch.setKnownHosts(ByteArrayInputStream(knownHosts.toByteArray(Charsets.UTF_8)))
                } else {
                    // DEV-ONLY transitional behaviour. Before shipping to an unattended
                    // device, the config import wizard must require a verified host key.
                    AAPLog.append("WARNING: no pinned SSH host key; first connection uses TOFU")
                }

                val candidate = jsch.getSession(sshUser, sshHost, sshPort)
                session = candidate
                candidate.setPassword(sshPassword)
                candidate.setConfig("PreferredAuthentications", "password")
                candidate.setConfig("StrictHostKeyChecking", if (savedHostKey.isNullOrBlank()) "no" else "yes")
                candidate.setServerAliveInterval(15_000)
                candidate.setServerAliveCountMax(2)

                AAPLog.append("Connecting SSH to $sshHost:$sshPort")
                candidate.connect(CONNECT_TIMEOUT_MS)
                coroutineContext.ensureActive()

                if (savedHostKey.isNullOrBlank()) {
                    val hostKey = candidate.hostKey
                        ?: throw JSchException("Server did not supply an SSH host key")
                    prefs.edit().putString(SERVER_KEY, "${hostKey.type} ${hostKey.key}").apply()
                    AAPLog.append("Pinned first-seen SSH host key for subsequent connections")
                }

                // SSHD must permit remote forwarding; no broad bind fallback is allowed.
                candidate.setPortForwardingR("127.0.0.1", remotePort, localHost, localPort)
                coroutineContext.ensureActive()
                AAPLog.append("Remote forwarding 127.0.0.1:$remotePort -> $localHost:$localPort")
                ConnectionStateHolder.setState(ConnectionState.CONNECTED)

                while (isActive) {
                    delay(5_000)
                    if (!candidate.isConnected) throw JSchException("SSH session disconnected")
                }
            } catch (_: CancellationException) {
                // Normal stop; no retry notification.
            } catch (e: Exception) {
                if (!stopping) {
                    AAPLog.append("SSH failed: ${e.javaClass.simpleName}: ${e.message}")
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                    onError?.invoke("SSH failed: ${e.message}")
                }
            } finally {
                session?.disconnect()
                session = null
                ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            }
        }
    }

    @Synchronized
    fun stopSSHTunnel() {
        stopping = true
        tunnelJob?.cancel()
        tunnelJob = null
        session?.disconnect()
        session = null
        ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
    }

    fun destroy() {
        stopSSHTunnel()
        scope.cancel()
    }
}
