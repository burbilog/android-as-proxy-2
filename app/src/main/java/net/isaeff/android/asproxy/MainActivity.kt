package net.isaeff.android.asproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.isaeff.android.asproxy.ui.theme.AAPTheme

/**
 * AAP2 has only one supported provisioning path: verified host fingerprint
 * followed by one-time PIN/SFTP enrollment. In particular, there is no
 * password/TOFU fallback or password saved in aap_prefs from the UI.
 * Existing Keystore/Direct Boot profiles are never modified here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Older development APKs stored a plaintext SSH password and TOFU
        // host key in this CE-only preference file. Neither is supported by
        // AAP2 enrollment. Erase them on first normal UI start; never touch
        // the device-protected verified profile or Android Keystore identity.
        deleteSharedPreferences("aap_prefs")
        setContent { AAPTheme { MainScreen() } }
    }

    companion object {
        fun aaplog(message: String) = AAPLog.append(message)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val connectionState by ConnectionStateHolder.connectionState.collectAsState()
    val traffic by ConnectionStateHolder.trafficBytes.collectAsState()
    // Returning from EnrollmentActivity does not inherently recompose this screen.
    // Refresh profile visibility on resume, without ever changing the stored profile.
    val lifecycleOwner = LocalLifecycleOwner.current
    var configured by remember { mutableStateOf(DirectBootTunnelProfile.hasProfile(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                configured = DirectBootTunnelProfile.hasProfile(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) AAPLog.append("Notification permission denied")
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun control(action: String) {
        val intent = Intent(context, SocksForegroundService::class.java).apply {
            this.action = action
        }
        try {
            if (action == "net.isaeff.android.asproxy.action.START_SERVICE" &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            AAPLog.append("Service control failed: ${e.javaClass.simpleName}")
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Android As Proxy 2") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                when (connectionState) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.DISCONNECTING -> "Disconnecting…"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Traffic: " +
                    android.text.format.Formatter.formatShortFileSize(context, traffic.first) +
                    " in / " +
                    android.text.format.Formatter.formatShortFileSize(context, traffic.second) +
                    " out"
            )
            if (!configured) {
                Text("No verified server is configured. Activate using the administrator's one-time PIN.")
                Button(
                    onClick = {
                        context.startActivity(Intent(context, EnrollmentActivity::class.java))
                    },
                    enabled = connectionState == ConnectionState.DISCONNECTED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Activate AAP2 using one-time PIN")
                }
            } else {
                Text("Server identity verified. SSH private key is held by Android Keystore.")
                when (connectionState) {
                    ConnectionState.DISCONNECTED -> Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                control("net.isaeff.android.asproxy.action.START_SERVICE")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Connect") }
                    ConnectionState.CONNECTED -> Button(
                        onClick = { control("net.isaeff.android.asproxy.action.STOP_SERVICE") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect") }
                    else -> {}
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { context.startActivity(Intent(context, LogViewActivity::class.java)) }
                ) { Text("Show log") }
            }
        }
    }
}
