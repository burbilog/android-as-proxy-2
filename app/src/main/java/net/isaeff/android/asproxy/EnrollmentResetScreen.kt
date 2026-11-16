package net.isaeff.android.asproxy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen destructive confirmation, deliberately WITHOUT any TextField or
 * Android IME. The internal numeric pad and final confirmation stay anchored
 * at the bottom while shorter warnings can scroll on compact devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnrollmentResetScreen(
    profile: DirectBootTunnelProfile.Config,
    challenge: String,
    entered: String,
    resetting: Boolean,
    failed: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    BackHandler(enabled = !resetting, onBack = onCancel)
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("a1-reset-screen"),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        topBar = {
            TopAppBar(
                title = { Text("Erase enrollment", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(
                        onClick = onCancel, enabled = !resetting,
                        modifier = Modifier.testTag("a1-reset-cancel")
                    ) { Text("Cancel") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Enter this code to erase the enrollment:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        challenge,
                        modifier = Modifier.fillMaxWidth().testTag("a1-reset-code"),
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Entered: " + entered.padEnd(4, '–'),
                            modifier = Modifier.testTag("a1-reset-input"),
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(
                            onClick = onBackspace,
                            enabled = entered.isNotEmpty() && !resetting,
                            modifier = Modifier.testTag("a1-reset-backspace")
                        ) { Text("Delete") }
                    }
                    // A deliberately compact 2x5 keypad works on short and
                    // narrow rugged Android terminals. No soft keyboard can
                    // cover the pinned destructive confirmation button.
                    listOf("12345", "67890").forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            line.forEach { digit ->
                                OutlinedButton(
                                    onClick = { onDigit(digit) },
                                    modifier = Modifier.weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("a1-reset-digit-$digit"),
                                    enabled = !resetting && entered.length < 4,
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(digit.toString(), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Button(
                        onClick = onConfirm,
                        enabled = !resetting && challenge.length == 4 && entered == challenge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("a1-reset-confirm"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) { Text(if (resetting) "Erasing…" else "ERASE AAP2 ENROLLMENT") }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "DANGER: THIS CANNOT BE UNDONE",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Stops the connection and deletes this phone's AAP2 SSH key. " +
                    "A new activation and administrator approval will be needed."
            )
            Text(
                "Server: ${profile.host} · ID: ${ConnectionIdentity.id(profile.username)}",
                fontWeight = FontWeight.Bold
            )
            Text(
                "The old server is NOT revoked. Ask its administrator to revoke this ID.",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            if (failed) {
                Text("Reset failed. Try again.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
