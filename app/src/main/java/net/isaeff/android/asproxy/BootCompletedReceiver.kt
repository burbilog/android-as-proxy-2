package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * LOCKED_BOOT_COMPLETED and MY_PACKAGE_REPLACED may arrive without CE storage.
 * Restore only a previously enabled, independently pinned device-protected
 * SSH profile. An explicit Disconnect must survive both reboot and APK update.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        try {
            // There is no legacy password-mode auto-connect fallback in AAP2.
            // Do not start anything (before OR after unlock) without a verified
            // device-protected profile explicitly marked as desired_running.
            if (!DirectBootTunnelProfile.wantsConnection(context)) return

            val serviceIntent = Intent(context, SocksForegroundService::class.java).apply {
                this.action = if (action == Intent.ACTION_MY_PACKAGE_REPLACED)
                    SocksForegroundService.ACTION_RESTORE
                else SocksForegroundService.ACTION_BOOT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i("AAP2-P0", "SYSTEM_RESTORE_START " + action)
        } catch (e: Exception) {
            Log.e("AAP2-P0", "SYSTEM_RESTORE_FAILED " +
                e.javaClass.simpleName + ": " + e.message)
        }
    }
}
