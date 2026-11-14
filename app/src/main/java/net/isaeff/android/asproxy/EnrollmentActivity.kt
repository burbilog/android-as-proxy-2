package net.isaeff.android.asproxy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.isaeff.android.asproxy.ui.theme.AAPTheme

/**
 * Separate enrollment screen: no PIN leaves the device before the user has
 * independently confirmed the complete SSH host fingerprint. Existing
 * Direct Boot profiles are never overwritten from this Activity.
 */
class EnrollmentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AAPTheme {
                EnrollmentScreen(
                    onCancel = { finish() },
                    onEnrolled = {
                        val intent = Intent(this, SocksForegroundService::class.java).apply {
                            action = "net.isaeff.android.asproxy.action.START_SERVICE"
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrollmentScreen(onCancel: () -> Unit, onEnrolled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { EnrollmentClient(context.applicationContext) }

    var server by remember { mutableStateOf("") }
    var setupUser by remember { mutableStateOf("") }
    // Intentionally NOT rememberSaveable: never put the PIN into Android
    // saved-instance-state Bundles, Intent extras, preferences or logcat.
    var pin by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<VerifiedSshHostKey.Probe?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var pending by remember {
        mutableStateOf(runCatching { EnrollmentPending.load(context) }.getOrNull() != null)
    }
    var confirmDiscard by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Activate AAP2") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (DirectBootTunnelProfile.hasProfile(context)) {
                Text("AAP2 is already configured. Existing host pin and SSH identity are preserved.")
                TextButton(onClick = onCancel) { Text("Close") }
            } else {
                if (candidate == null) {
                    Text("Enter server address, six-digit setup code, and ten-digit PIN. Type PIN digits without the printed dashes.")
                    OutlinedTextField(
                        value = server, onValueChange = { server = it },
                        label = { Text("Server (host[:port])") },
                        modifier = Modifier.fillMaxWidth().testTag("a1-server"), singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = setupUser, onValueChange = { setupUser = it },
                        label = { Text("Setup code (6 digits)") },
                        modifier = Modifier.fillMaxWidth().testTag("a1-setup-user"), singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it },
                        label = { Text("One-time PIN (10 digits, no dashes)") },
                        modifier = Modifier.fillMaxWidth().testTag("a1-pin"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        enabled = !busy
                    )
                    Button(enabled = !busy, onClick = {
                        error = ""
                        val address = try {
                            EnrollmentInput.setupUser(setupUser)
                            EnrollmentInput.pin(pin)
                            EnrollmentInput.endpoint(server)
                        } catch (e: IllegalArgumentException) {
                            error = e.message.orEmpty()
                            null
                        }
                        if (address != null) {
                            busy = true
                            message = "Checking server key BEFORE sending PIN…"
                            scope.launch {
                                try {
                                    candidate = withContext(Dispatchers.IO) {
                                        VerifiedSshHostKey.probe(address.host, address.port)
                                    }
                                    message = "Read the entire fingerprint to your administrator."
                                } catch (e: Exception) {
                                    error = "Host key probe failed: ${e.message.orEmpty()}"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }, modifier = Modifier.testTag("a1-probe")) { Text("Check server fingerprint") }

                    if (pending) {
                        Text("An enrollment is pending. Resume only after the administrator approved the key.")
                        Button(enabled = !busy, onClick = {
                            busy = true
                            error = ""
                            scope.launch {
                                try {
                                    client.resume { message = it }
                                    pin = ""
                                    onEnrolled()
                                } catch (e: Exception) {
                                    error = "Not approved or unavailable: ${e.javaClass.simpleName}"
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Resume approved enrollment") }
                        TextButton(enabled = !busy, onClick = { confirmDiscard = true }) {
                            Text("Discard pending enrollment")
                        }
                    }
                } else {
                    val probe = candidate!!
                    Text("Verify this key with the administrator over a separate trusted channel.")
                    Text("Server: ${probe.host}:${probe.port}")
                    Text("Host key type: ${probe.algorithm}")
                    Text("Full fingerprint:", style = MaterialTheme.typography.titleMedium)
                    Text(probe.fingerprint, modifier = Modifier.testTag("a1-fingerprint-full"))
                    Text(EnrollmentInput.groupedFingerprint(probe.fingerprint), modifier = Modifier.testTag("a1-fingerprint-grouped"))
                    Text("Do not continue unless every fingerprint character matches. The PIN has not been sent.")

                    Button(enabled = !busy, onClick = {
                        busy = true
                        error = ""
                        scope.launch {
                            try {
                                client.enroll(probe, setupUser, pin) { message = it }
                                pin = ""
                                onEnrolled()
                            } catch (e: Exception) {
                                // Never log or display the PIN or private SSH key.
                                error = "Enrollment incomplete: ${e.javaClass.simpleName}. " +
                                    "If approval happened, use Resume; otherwise ask the administrator."
                                pending = runCatching {
                                    EnrollmentPending.load(context)
                                }.getOrNull() != null
                                candidate = null
                            } finally {
                                busy = false
                            }
                        }
                    }, modifier = Modifier.testTag("a1-verified-activate")) { Text("Verified — send PIN and activate") }

                    TextButton(enabled = !busy, onClick = {
                        candidate = null
                        message = ""
                    }, modifier = Modifier.testTag("a1-cancel-verification")) { Text("Cancel fingerprint verification") }
                }

                if (message.isNotBlank()) Text(message)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = {
                    pin = ""
                    onCancel()
                }) { Text("Back") }
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard pending enrollment?") },
                text = { Text("This clears pending server details. It does not delete or replace the Android Keystore SSH key. Re-registration requires a fresh server invitation.") },
                confirmButton = {
                    TextButton(onClick = {
                        EnrollmentPending.clear(context)
                        pending = false
                        confirmDiscard = false
                    }) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") }
                }
            )
        }
    }
}
