package net.isaeff.android.asproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.isaeff.android.asproxy.ui.theme.AAPTheme
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

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
    val resetStatus by EnrollmentReset.status.collectAsState()
    // Returning from EnrollmentActivity does not inherently recompose this screen.
    // Refresh profile visibility on resume, without ever changing the stored profile.
    val lifecycleOwner = LocalLifecycleOwner.current
    var configured by remember { mutableStateOf(DirectBootTunnelProfile.hasProfile(context)) }
    var profile by remember {
        mutableStateOf(runCatching { DirectBootTunnelProfile.load(context) }.getOrNull())
    }
    var showDetails by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetCode by remember { mutableStateOf("") }
    var resetInput by remember { mutableStateOf("") }

    // Updates are explicitly requested by the user, not polled or downloaded
    // in the background. The existing SSH foreground service keeps running
    // while an APK is downloaded and validated.
    val updateScope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var waitingForInstallPermission by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(resetStatus) {
        when (resetStatus) {
            EnrollmentReset.Status.DONE -> {
                configured = DirectBootTunnelProfile.hasProfile(context)
                profile = runCatching { DirectBootTunnelProfile.load(context) }.getOrNull()
                showResetDialog = false
                showDetails = false
                resetInput = ""
                resetCode = ""
                waitingForInstallPermission = null
                updateMessage = "Local enrollment erased. Ask your administrator to revoke the old server account."
                EnrollmentReset.dismissResult()
            }
            EnrollmentReset.Status.FAILED -> {
                updateMessage = "Reset failed. Existing connection was stopped; retry Reset enrollment."
            }
            else -> Unit
        }
    }
    val unknownSourcesSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = waitingForInstallPermission
        waitingForInstallPermission = null
        if (apk != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls())) {
            runCatching { ApkUpdater.launchSystemInstaller(context, apk) }
                .onFailure { updateMessage = "Cannot open Android installer: ${it.javaClass.simpleName}" }
        } else {
            updateMessage = "Install permission not granted. Tap Update to try again."
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val previouslyEnrolled = profile != null
                val currentProfile = runCatching {
                    DirectBootTunnelProfile.load(context)
                }.getOrNull()
                configured = DirectBootTunnelProfile.hasProfile(context)
                profile = currentProfile
                // A successful activation after Reset must not keep showing
                // the previous enrollment's "erased" notice (or other stale
                // status). Do not clear errors on unrelated Activity resumes.
                if (!previouslyEnrolled && currentProfile != null) {
                    updateMessage = ""
                }
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

    // Explicit destructive confirmation. No one-tap reset, including when
    // a connection is running. This affects only AAP2's own local identity.
    if (showResetDialog && profile != null) {
        val resetting = resetStatus == EnrollmentReset.Status.RUNNING
        AlertDialog(
            onDismissRequest = {
                if (!resetting) {
                    showResetDialog = false
                    resetInput = ""
                    resetCode = ""
                }
            },
            modifier = Modifier.testTag("a1-reset-dialog"),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            title = {
                Text(
                    "DANGER: ERASE ENROLLMENT",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                // Never put the challenge or its input inside a scrollable
                // warning. Both must be immediately visible on a small phone.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Enter these 4 digits to erase this enrollment:")
                    Text(
                        resetCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("a1-reset-code")
                    )
                    OutlinedTextField(
                        value = resetInput,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all { it in '0'..'9' }) {
                                resetInput = value
                            }
                        },
                        label = { Text("Enter code") },
                        singleLine = true,
                        enabled = !resetting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("a1-reset-input")
                    )
                    Text(
                        "Disconnects AAP2 and deletes its SSH key. " +
                            "A new activation is required.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("a1-reset-warning")
                    )
                    Text(
                        "Server: ${profile.host} · ID: " +
                            ConnectionIdentity.id(profile.username),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Old server access is NOT revoked. Ask its admin to revoke this ID.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (resetStatus == EnrollmentReset.Status.FAILED) {
                        Text("Reset failed; try again.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        EnrollmentReset.begin()
                        try {
                            context.startService(
                                Intent(context, SocksForegroundService::class.java).apply {
                                    action = SocksForegroundService.ACTION_RESET
                                }
                            )
                        } catch (e: Exception) {
                            EnrollmentReset.failed()
                            AAPLog.append("Local reset request failed: ${e.javaClass.simpleName}")
                        }
                    },
                    enabled = resetCode.length == 4 && resetInput == resetCode && !resetting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("a1-reset-confirm")
                ) { Text(if (resetting) "Erasing…" else "ERASE AAP2 ENROLLMENT") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        resetInput = ""
                        resetCode = ""
                    },
                    enabled = !resetting,
                    modifier = Modifier.testTag("a1-reset-cancel")
                ) { Text("Keep current connection") }
            }
        )
    }

    BackHandler(enabled = showDetails) { showDetails = false }
    val detailsProfile = profile
    if (showDetails && detailsProfile != null) {
        ConnectionDetailsScreen(
            profile = detailsProfile,
            state = connectionState,
            traffic = traffic,
            onClose = { showDetails = false }
        )
    } else {
    Scaffold(topBar = { TopAppBar(title = { Text("Android As Proxy 2") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Everyday actions scroll independently. Destructive reset lives
            // in a separate, permanently bottom-aligned footer.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            Text(
                when (connectionState) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.RETRYING -> "Reconnecting…"
                    ConnectionState.DISCONNECTING -> "Disconnecting…"
                },
                modifier = Modifier.testTag("a1-main-status"),
                style = MaterialTheme.typography.titleMedium
            )
            if (profile != null) {
                Text(
                    "Connection ID: ${ConnectionIdentity.id(profile!!.username)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("a1-connection-id")
                )
            }
            Text(
                "Traffic: " +
                    android.text.format.Formatter.formatShortFileSize(context, traffic.first) +
                    " in / " +
                    android.text.format.Formatter.formatShortFileSize(context, traffic.second) +
                    " out"
            )
            if (!configured) {
                Text("No verified server is configured. Activate using the code from your administrator.")
                Button(
                    onClick = {
                        context.startActivity(Intent(context, EnrollmentActivity::class.java))
                    },
                    enabled = connectionState == ConnectionState.DISCONNECTED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Activate AAP2")
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
                        modifier = Modifier.fillMaxWidth().testTag("a1-connect")
                    ) { Text("Connect") }
                    ConnectionState.CONNECTED, ConnectionState.CONNECTING,
                    ConnectionState.RETRYING -> Button(
                        onClick = { control("net.isaeff.android.asproxy.action.STOP_SERVICE") },
                        modifier = Modifier.fillMaxWidth().testTag("a1-disconnect")
                    ) { Text("Disconnect") }
                    else -> {}
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, LogViewActivity::class.java)) }
                ) { Text("Show log") }
                if (profile != null) {
                    OutlinedButton(
                        onClick = { showDetails = true },
                        modifier = Modifier.testTag("a1-open-details")
                    ) { Text("Details") }
                    OutlinedButton(
                        onClick = {
                            updateBusy = true
                            updateMessage = "Downloading update…"
                            updateScope.launch {
                                try {
                                    val ready = ApkUpdater.download(context.applicationContext)
                                    updateMessage = "Ready to install: ${ready.versionName}"
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                        !context.packageManager.canRequestPackageInstalls()) {
                                        waitingForInstallPermission = ready.file
                                        updateMessage = "Allow installs from AAP2, then return."
                                        unknownSourcesSettings.launch(
                                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:${context.packageName}"))
                                        )
                                    } else {
                                        ApkUpdater.launchSystemInstaller(context, ready.file)
                                    }
                                } catch (e: Exception) {
                                    updateMessage = "Update failed: ${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    updateBusy = false
                                }
                            }
                        },
                        enabled = !updateBusy,
                        modifier = Modifier.testTag("a1-update")
                    ) { Text(if (updateBusy) "Loading…" else "Update") }
                }
            }
                if (updateMessage.isNotBlank()) {
                    Text(updateMessage, modifier = Modifier.testTag("a1-update-status"))
                }
            }
            if (profile != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .testTag("a1-reset-footer"),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        onClick = {
                            resetInput = ""
                            // Generate a new code on EVERY opening, not on recomposition.
                            // It is a local confirmation challenge, not authentication.
                            resetCode = SecureRandom().nextInt(10_000).toString().padStart(4, '0')
                            EnrollmentReset.dismissResult()
                            showResetDialog = true
                        },
                        enabled = !updateBusy &&
                            resetStatus != EnrollmentReset.Status.RUNNING,
                        modifier = Modifier.testTag("a1-reset-open"),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Reset enrollment / Change server") }
                }
            }
        }
    }
    }
}
