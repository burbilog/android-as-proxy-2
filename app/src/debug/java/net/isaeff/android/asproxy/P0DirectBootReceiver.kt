package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug APK only: measure Android Keystore signing before first unlock.
 * This does not start a tunnel or silently regenerate a missing key.
 */
class P0DirectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                val identity = AndroidKeystoreIdentity.getExisting(context)
                val data = "aap2-p0-cold-boot".toByteArray(Charsets.US_ASCII)
                val signature = identity.getSignature(data)
                check(signature.isNotEmpty())
                Log.i("AAP2-P0", "DIRECT_BOOT_KEYSTORE_OK")
            } catch (e: Exception) {
                Log.e("AAP2-P0", "DIRECT_BOOT_KEYSTORE_FAILED: " +
                    e.javaClass.simpleName + ": " + e.message)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
