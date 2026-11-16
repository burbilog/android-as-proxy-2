package net.isaeff.android.asproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Durable, non-sensitive problem status visible even after foreground service
 * stops. Uses device-protected storage so it also works before first unlock.
 * Never stores SSH exception messages, credentials or key material.
 */
internal object ConnectionIssueStore {
    private const val PREFS = "aap2-connection-problem-v1"
    private const val KEY = "reason"
    private const val NOTIFICATION_ID = 2
    private const val CHANNEL = "AAP2ConnectionProblem"
    enum class Reason(val message: String) {
        HOST_KEY("Server identity differs. Connection blocked; checking again hourly. Contact the administrator."),
        AUTH("Server rejected the connection. AAP2 retries hourly; contact the administrator."),
        KEYSTORE("Phone identity unavailable. AAP2 retries hourly; contact the administrator. Do not reset enrollment."),
        SERVICE("Connection error. AAP2 retries hourly; contact the administrator if it persists.")
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Reason? {
        val code = prefs(context).getString(KEY, null)
        val reason = Reason.values().firstOrNull { it.name == code }
        ConnectionStateHolder.setProblem(reason?.message)
        return reason
    }

    fun report(context: Context, reason: Reason) {
        prefs(context).edit().putString(KEY, reason.name).commit()
        ConnectionStateHolder.setProblem(reason.message)
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL, "AAP2 connection problems", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("AAP2 needs attention")
                .setContentText(reason.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason.message))
                .setContentIntent(intent)
                .setAutoCancel(false)
                .build()
            manager.notify(NOTIFICATION_ID, note)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
        ConnectionStateHolder.setProblem(null)
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
