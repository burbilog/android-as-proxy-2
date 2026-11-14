package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * LOCKED_BOOT_COMPLETED may run before CE storage is mounted.
 * Only the explicitly provisioned device-protected P-256 SSH profile may start then.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            // There is no legacy password-mode auto-connect fallback in AAP2.
            // Do not start anything (before OR after unlock) without a verified
            // device-protected profile explicitly marked as desired_running.
            if (!DirectBootTunnelProfile.wantsConnection(context)) return

            val serviceIntent = Intent(context, SocksForegroundService::class.java).apply {
                this.action = SocksForegroundService.ACTION_BOOT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i("AAP2-P0", "BOOT_RECEIVER_START " + action)
        } catch (e: Exception) {
            Log.e("AAP2-P0", "BOOT_RECEIVER_FAILED " +
                e.javaClass.simpleName + ": " + e.message)
        }
    }
}
