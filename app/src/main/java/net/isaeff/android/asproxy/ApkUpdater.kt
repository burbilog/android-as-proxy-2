package net.isaeff.android.asproxy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * User-initiated update only. Never changes the SSH profile, starts a second
 * service, stores credentials, or silently installs an APK.
 *
 * Fetch the newest stable release directly from GitHub. No dependency on
 * the author's personal domain. The Android package installer ultimately
 * verifies the APK signature again.
 */
internal object ApkUpdater {
    private const val SOURCE =
        "https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk"
    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val APK_MIME = "application/vnd.android.package-archive"

    data class Ready(val file: File, val versionName: String)

    internal fun allowedDownloadUri(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    suspend fun download(context: Context): Ready = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        require(dir.isDirectory || dir.mkdirs()) { "Cannot create update cache" }
        val temp = File(dir, "aap2.part")
        val apk = File(dir, "aap2.apk")
        temp.delete()
        try {
            var uri = URI(SOURCE)
            var completed = false
            for (redirect in 0..MAX_REDIRECTS) {
                require(allowedDownloadUri(uri)) { "Untrusted update download address" }
                val connection = (uri.toURL().openConnection() as HttpsURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept-Encoding", "identity")
                }
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        require(redirect < MAX_REDIRECTS) { "Too many update redirects" }
                        val location = connection.getHeaderField("Location")
                            ?: error("Update redirect without Location")
                        uri = uri.resolve(location)
                        continue
                    }
                    require(status == 200) { "Update unavailable (HTTP $status)" }
                    val reported = connection.contentLengthLong
                    require(reported <= MAX_BYTES) { "Update APK exceeds size limit" }
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buf = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buf)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BYTES) { "Update APK exceeds size limit" }
                                output.write(buf, 0, count)
                            }
                            require(total > 0) { "Empty update APK" }
                        }
                    }
                    completed = true
                    break
                } finally {
                    connection.disconnect()
                }
            }
            check(completed) { "No update APK available" }
            val version = validatePackage(context.packageManager, context.packageName, temp)
            if (apk.exists()) require(apk.delete()) { "Cannot replace cached APK" }
            require(temp.renameTo(apk)) { "Cannot prepare update APK" }
            Ready(apk, version)
        } finally {
            temp.delete()
        }
    }

    /**
     * Check package identity and version before invoking the Android installer.
     * PackageManager.getPackageArchiveInfo() may return null signingInfo for
     * uninstalled APKs on Android 11 even when they have valid v2/v3 signatures.
     * The platform Package Installer cryptographically verifies the downloaded
     * APK and REFUSES to replace an installed app signed by a different key.
     * Do not mistake an unavailable archive signer for a mismatched signer.
     */
    internal fun validatePackage(pm: PackageManager, packageName: String, apk: File): String {
        val installed = pm.getPackageInfo(packageName, 0)
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("Downloaded file is not an Android APK")
        return validatePackageMetadata(installed, candidate, packageName)
    }

    internal fun validatePackageMetadata(
        installed: PackageInfo, candidate: PackageInfo, packageName: String
    ): String {
        require(candidate.packageName == packageName) { "APK is not AAP2" }
        val installedVersion = versionCode(installed)
        val candidateVersion = versionCode(candidate)
        require(candidateVersion > installedVersion) {
            "Already up to date (installed $installedVersion, available $candidateVersion)"
        }
        return candidate.versionName ?: candidateVersion.toString()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /** The user must explicitly accept the OS installation confirmation. */
    fun launchSystemInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updates", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newUri(context.contentResolver, "AAP2 update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
