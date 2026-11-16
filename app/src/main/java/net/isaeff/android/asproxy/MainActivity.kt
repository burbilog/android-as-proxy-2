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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    val problem by ConnectionStateHolder.problem.collectAsState()
    val resetStatus by EnrollmentReset.status.collectAsState()
    // Returning from EnrollmentActivity does not inherently recompose this screen.
    // Refresh profile visibility on resume, without ever changing the stored profile.
    val lifecycleOwner = LocalLifecycleOwner.current
    var configured by remember { mutableStateOf(DirectBootTunnelProfile.hasProfile(context)) }
    var profile by remember {
        mutableStateOf(runCatching { DirectBootTunnelProfile.load(context) }.getOrNull())
    }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ConnectionIssueStore.load(context) }
    var showAbout by remember { mutableStateOf(false) }
    val hasGithubUpdater = context.resources.getBoolean(R.bool.aap2_builtin_update)
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
                ConnectionIssueStore.load(context)
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
        if (!granted) {
            AAPLog.append("Notification permission denied")
            updateMessage = "Notifications are disabled. Allow AAP2 notifications in Android settings to see its connection status."
        }
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
            updateMessage = "Unable to change connection: ${e.javaClass.simpleName}. See Show log."
        }
    }

    // Explicit destructive confirmation, with its own numeric keypad: no
    // Android IME and no AlertDialog/keyboard/back dismissal interaction.
    val resetProfile = profile
    val onCancelReset = {
        showResetDialog = false
        resetInput = ""
        resetCode = ""
    }

    BackHandler(enabled = showDetails && !showResetDialog && !showAbout) { showDetails = false }
    val detailsProfile = profile
    if (showResetDialog && resetProfile != null) {
        EnrollmentResetScreen(
            profile = resetProfile,
            challenge = resetCode,
            entered = resetInput,
            resetting = resetStatus == EnrollmentReset.Status.RUNNING,
            failed = resetStatus == EnrollmentReset.Status.FAILED,
            onDigit = { digit ->
                if (resetInput.length < 4 && digit in '0'..'9') {
                    resetInput += digit
                }
            },
            onBackspace = { resetInput = resetInput.dropLast(1) },
            onCancel = onCancelReset,
            onConfirm = {
                if (resetCode.length == 4 && resetInput == resetCode &&
                    resetStatus != EnrollmentReset.Status.RUNNING) {
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
                }
            }
        )
    } else if (showAbout) {
        AboutScreen(onClose = { showAbout = false })
    } else if (showDetails && detailsProfile != null) {
        ConnectionDetailsScreen(
            profile = detailsProfile,
            state = connectionState,
            traffic = traffic,
            onClose = { showDetails = false }
        )
    } else {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Android As Proxy 2") },
            actions = {
                TextButton(
                    onClick = { showAbout = true },
                    modifier = Modifier.testTag("aap2-open-about")
                ) { Text("About") }
            }
        )
    }) { padding ->
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
            if (problem != null) {
                Text(problem!!, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("a1-connection-problem"))
            }
            if (connectionState == ConnectionState.CONNECTED) {
                Text("SSH tunnel connected. This does not verify Internet access through SOCKS5.")
            }
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
                Text("To activate, ask your SSH server administrator for an 18-digit code. AAP2 is not a VPN service and needs a compatible server.")
                TextButton(onClick = {
                    if (!ProjectLinks.open(context, ProjectLinks.SETUP)) {
                        updateMessage = "Cannot open the setup guide: no browser found."
                    }
                }) { Text("How to set up AAP2") }
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
                }
                if (hasGithubUpdater) {
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
