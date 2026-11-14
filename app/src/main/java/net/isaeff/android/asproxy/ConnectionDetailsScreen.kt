package net.isaeff.android.asproxy

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The server's enrollment ID is the suffix of the permanent SSH account. */
internal object ConnectionIdentity {
    fun id(username: String): String {
        require(username.matches(Regex("aap2p(?:[0-9]{6}|[0-9a-f]{12})"))) {
            "Not an AAP2 permanent SSH account"
        }
        return username.removePrefix("aap2p")
    }
}

/** Non-secret snapshot, exclusively from the committed local profile and public device data. */
internal data class ConnectionDetailsInfo(
    val connectionId: String,
    val sshAccount: String,
    val server: String,
    val serverSocks: String,
    val phoneSocks: String,
    val hostFingerprint: String,
    val phoneKeyFingerprint: String,
    val device: String,
    val appVersion: String,
    val autoConnect: Boolean
) {
    companion object {
        fun fromProfile(context: Context, profile: DirectBootTunnelProfile.Config): ConnectionDetailsInfo {
            // Read ONLY the existing public key; do not create an identity or ask
            // the server to check anything while the details page is open.
            val publicFingerprint = runCatching {
                VerifiedSshHostKey.sha256Fingerprint(
                    AndroidKeystoreIdentity.getExisting(context).publicKeyBlob
                )
            }.getOrElse { "Unavailable (Keystore)" }
            val pkg = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                pkg?.longVersionCode ?: 0L
            } else {
                pkg?.versionCode?.toLong() ?: 0L
            }
            val version = if (pkg == null) "Unavailable" else {
                "${pkg.versionName ?: "unknown"} (#${versionCode})"
            }
            val phone = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
            return ConnectionDetailsInfo(
                connectionId = ConnectionIdentity.id(profile.username),
                sshAccount = profile.username,
                server = "${profile.host}:${profile.sshPort}",
                serverSocks = "127.0.0.1:${profile.remotePort}",
                phoneSocks = "127.0.0.1:11080",
                hostFingerprint = VerifiedSshHostKey.sha256Fingerprint(profile.verifiedHostKey),
                phoneKeyFingerprint = publicFingerprint,
                device = phone,
                appVersion = version,
                autoConnect = DirectBootTunnelProfile.wantsConnection(context)
            )
        }
    }
}

internal fun connectionStatusText(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.DISCONNECTED -> "Disconnected"
    ConnectionState.DISCONNECTING -> "Disconnecting"
}

/**
 * One non-scrollable screen to photograph or show to an administrator.
 * The SSH host and phone PUBLIC fingerprints are deliberately complete;
 * no activation code, PIN, private key, or log content is exposed.
 */
@Composable
internal fun ConnectionDetailsScreen(
    profile: DirectBootTunnelProfile.Config,
    state: ConnectionState,
    traffic: Pair<Long, Long>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val details = remember(context, profile) {
        ConnectionDetailsInfo.fromProfile(context, profile)
    }
    val transferred = "${Formatter.formatShortFileSize(context, traffic.first)} received / " +
        "${Formatter.formatShortFileSize(context, traffic.second)} sent"
    ConnectionDetailsContent(details, state, transferred, onClose)
}

@Composable
internal fun ConnectionDetailsContent(
    details: ConnectionDetailsInfo,
    state: ConnectionState,
    transferred: String,
    onClose: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("a1-details-screen")) {
        val compact = maxHeight < 630.dp || maxWidth < 350.dp
        val gap = if (compact) 3.dp else 7.dp
        val bodySize = if (compact) 11.sp else 13.sp
        val lineHeight = if (compact) 13.sp else 16.sp
        val fingerprintSize = if (compact) 10.sp else 12.sp

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("AAP2 · Connection details", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose, modifier = Modifier.testTag("a1-details-close")) {
                    Text("Close")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "ID: ${details.connectionId}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("a1-details-id")
                )
                Text(
                    connectionStatusText(state),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("a1-details-status")
                )
            }

            DetailsLine("Server", details.server, bodySize, lineHeight, "a1-details-server")
            DetailsLine("SSH account", details.sshAccount, bodySize, lineHeight, "a1-details-account")
            DetailsLine("Server SOCKS5", details.serverSocks, bodySize, lineHeight, "a1-details-socks")
            DetailsLine("Phone SOCKS5", details.phoneSocks, bodySize, lineHeight, "a1-details-local")
            DetailsLine("Auto-connect", if (details.autoConnect) "Enabled" else "Disabled",
                bodySize, lineHeight, "a1-details-auto")
            DetailsLine("Traffic", transferred, bodySize, lineHeight, "a1-details-traffic")
            DetailsLine("Phone", details.device, bodySize, lineHeight, "a1-details-phone")
            DetailsLine("App", details.appVersion, bodySize, lineHeight, "a1-details-version")
            Fingerprint("Server SSH host key · SHA256", details.hostFingerprint,
                fingerprintSize, "a1-details-host-fingerprint")
            Fingerprint("Phone SSH public key · SHA256", details.phoneKeyFingerprint,
                fingerprintSize, "a1-details-device-fingerprint")

            Text(
                "Public diagnostics only · no PIN or private keys",
                fontSize = if (compact) 9.sp else 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("a1-details-footer")
            )
        }
    }
}

@Composable
private fun DetailsLine(
    label: String,
    value: String,
    size: TextUnit,
    lineHeight: TextUnit,
    tag: String
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = lineHeight)
        Text(
            value,
            fontSize = size,
            lineHeight = lineHeight,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun Fingerprint(label: String, fingerprint: String, size: TextUnit, tag: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = size * 1.2f)
        Text(
            fingerprint,
            fontSize = size,
            lineHeight = size * 1.25f,
            maxLines = 3,
            // Full fingerprints are required: never visually abbreviate them.
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().testTag(tag)
        )
    }
}
