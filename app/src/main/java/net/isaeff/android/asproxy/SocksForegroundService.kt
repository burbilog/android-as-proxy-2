package net.isaeff.android.asproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
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
    private var keystoreTunnelManager: KeystoreSshTunnelManager? = null
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
        if (intent?.action == ACTION_STOP) {
            if (DirectBootTunnelProfile.hasProfile(this)) {
                DirectBootTunnelProfile.setDesiredRunning(this, false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        // Read only device-protected data until the user has unlocked once.
        val bootProfile = try {
            DirectBootTunnelProfile.load(this)
        } catch (e: Exception) {
            Log.e("AAP2-P0", "BOOT_PROFILE_INVALID " + e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }

        if (bootProfile != null) {
            val bootOrRestart = intent == null || intent.action == ACTION_BOOT
            if (bootOrRestart && !DirectBootTunnelProfile.wantsConnection(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (!bootOrRestart) DirectBootTunnelProfile.setDesiredRunning(this, true)
            return startKeystoreTunnel(bootProfile)
        }

        // AAP2 deliberately has no password/TOFU fallback. In particular a
        // stale desired_running flag or legacy credentials must never launch
        // an unaudited SSH connection after boot or an APK update.
        AAPLog.append("No verified enrollment profile; refusing SSH startup")
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startKeystoreTunnel(profile: DirectBootTunnelProfile.Config): Int {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            val proxy = Socks5ProxyServer(11080)
            proxy.start()
            socksServer = proxy
            keystoreTunnelManager = KeystoreSshTunnelManager(this, profile, proxy.boundPort).apply {
                onFatalError = { stopSelf() }
                start()
            }
            running = true
            monitorTraffic()
            Log.i("AAP2-P0", "BOOT_TUNNEL_STARTED port=" + profile.remotePort)
            return START_STICKY
        } catch (e: Exception) {
            Log.e("AAP2-P0", "BOOT_TUNNEL_FAILED " + e.javaClass.simpleName)
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }
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
        keystoreTunnelManager?.destroy()
        keystoreTunnelManager = null
        socksServer?.close()
        socksServer = null
        serviceScope.cancel()
        ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
        AAPLog.append("AAP2 proxy stopped")
        super.onDestroy()
    }
}
