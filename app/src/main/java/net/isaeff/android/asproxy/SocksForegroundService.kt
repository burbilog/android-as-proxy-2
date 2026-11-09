package net.isaeff.android.asproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One foreground service owns one local SOCKS5 listener and one SSH/reconnect loop.
 * Retry runs *inside* this foreground service. No alarms, broadcasts containing secrets,
 * or background-start attempts after connectivity loss.
 */
class SocksForegroundService : Service() {
    companion object {
        const val ACTION_STOP = "net.isaeff.android.asproxy.action.STOP_SERVICE"
        const val ACTION_BOOT = "net.isaeff.android.asproxy.action.AUTO_CONNECT_ON_BOOT"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "AAP2ProxyChannel"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socksServer: Socks5ProxyServer? = null
    private var sshTunnelManager: SSHTunnelManager? = null
    private var trafficJob: Job? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AAP 2 proxy", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("aap_prefs", Context.MODE_PRIVATE)
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("desired_running", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        when (intent?.action) {
            ACTION_BOOT -> if (!prefs.getBoolean("auto_connect", false)) {
                stopSelf()
                return START_NOT_STICKY
            }
            null -> if (!prefs.getBoolean("desired_running", false)) {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit // Explicit user-initiated launch from MainActivity
        }

        val hostAndPort = try {
            parseEndpoint(prefs.getString("ssh_server", "") ?: "")
        } catch (e: IllegalArgumentException) {
            AAPLog.append("Invalid SSH address: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val remotePort = prefs.getString("remote_port", "")?.toIntOrNull()
        if (username.isBlank() || password.isBlank() || remotePort == null || remotePort !in 1..65535) {
            AAPLog.append("SSH configuration incomplete or invalid")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            val proxy = Socks5ProxyServer(11080)
            proxy.start()
            socksServer = proxy
            prefs.edit().putBoolean("desired_running", true).apply()

            sshTunnelManager = SSHTunnelManager(
                context = this,
                sshUser = username,
                sshHost = hostAndPort.first,
                sshPort = hostAndPort.second,
                sshPassword = password,
                remotePort = remotePort,
                localHost = "127.0.0.1",
                localPort = proxy.boundPort
            ).apply {
                onError = { message ->
                    AAPLog.append(message)
                    stopSelf()
                }
                startSSHTunnel()
            }

            running = true
            monitorTraffic()
            AAPLog.append("AAP2 SOCKS5 listening on 127.0.0.1:${proxy.boundPort}")
            return START_STICKY
        } catch (e: Exception) {
            AAPLog.append("Unable to start proxy: ${e.message}")
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun parseEndpoint(value: String): Pair<String, Int> {
        val address = value.trim()
        require(address.isNotBlank()) { "empty host" }
        val host: String
        val port: Int
        if (address.startsWith("[")) {
            val end = address.indexOf(']')
            require(end > 1) { "invalid bracketed IPv6 address" }
            host = address.substring(1, end)
            val suffix = address.substring(end + 1)
            require(suffix.isEmpty() || suffix.startsWith(":")) { "invalid IPv6 port" }
            port = if (suffix.isEmpty()) 22 else suffix.substring(1).toIntOrNull() ?: -1
        } else if (address.count { it == ':' } == 1) {
            host = address.substringBeforeLast(":")
            port = address.substringAfterLast(":").toIntOrNull() ?: -1
        } else {
            host = address // Bare IPv6 or hostname, default SSH port.
            port = 22
        }
        require(host.isNotBlank() && port in 1..65535) { "invalid host or port" }
        return host to port
    }

    private fun monitorTraffic() {
        trafficJob = serviceScope.launch {
            while (isActive) {
                socksServer?.let { proxy ->
                    ConnectionStateHolder.setTrafficBytes(
                        proxy.bytesFromInternet,
                        proxy.bytesToInternet
                    )
                }
                delay(1000)
            }
        }
    }

    private fun createNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SocksForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AAP 2")
            .setContentText("SSH SOCKS5 tunnel")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        running = false
        trafficJob?.cancel()
        sshTunnelManager?.destroy()
        sshTunnelManager = null
        socksServer?.close()
        socksServer = null
        serviceScope.cancel()
        ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
        AAPLog.append("AAP2 proxy stopped")
        super.onDestroy()
    }
}
