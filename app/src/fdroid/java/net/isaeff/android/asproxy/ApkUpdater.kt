package net.isaeff.android.asproxy

import android.content.Context
import java.io.File

/**
 * F-Droid build: no downloadable executable, no installer intent, no
 * REQUEST_INSTALL_PACKAGES permission. Updates come from the F-Droid client.
 */
internal object ApkUpdater {
    data class Ready(val file: File, val versionName: String)
    suspend fun download(context: Context): Ready =
        error("Install updates using F-Droid")

    fun launchSystemInstaller(context: Context, file: File): Unit =
        error("Install updates using F-Droid")
}
