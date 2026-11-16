package net.isaeff.android.asproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal object ProjectLinks {
    const val REPOSITORY = "https://github.com/burbilog/android-as-proxy-2"
    const val ISSUES = "$REPOSITORY/issues"
    const val SETUP = "$REPOSITORY/blob/master/README.md"
    const val LICENSE = "$REPOSITORY/blob/master/LICENSE.md"

    fun open(context: Context, url: String): Boolean = runCatching {
        // Only fixed, first-party public project URLs are passed in.
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        })
    }.isSuccess
}

/** Non-destructive information page, available even before enrollment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf(false) }
    val version = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
                       else pkg.versionCode.toLong()
            "${pkg.versionName ?: "unknown"} (#$code)"
        }.getOrDefault("Unavailable")
    }
    val isGithub = context.resources.getBoolean(R.bool.aap2_builtin_update)
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                actions = {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("aap2-about-close")) {
                        Text(stringResource(R.string.about_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("aap2-about-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.about_open_source), style = MaterialTheme.typography.titleLarge)
            Text("AAP2 $version · ${stringResource(R.string.aap2_distribution)}",
                 style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_description))
            Text(stringResource(R.string.about_requires_server))
            Text(stringResource(R.string.about_license))
            Text(stringResource(R.string.about_privacy))
            Text(stringResource(R.string.about_battery))
            Text(stringResource(if (isGithub) R.string.about_github_updates
                                else R.string.about_fdroid_updates))

            fun open(url: String) {
                openError = !ProjectLinks.open(context, url)
            }
            OutlinedButton(onClick = { open(ProjectLinks.REPOSITORY) },
                           modifier = Modifier.fillMaxWidth().testTag("aap2-about-source")) {
                Text(stringResource(R.string.about_sources))
            }
            OutlinedButton(onClick = { open(ProjectLinks.SETUP) },
                           modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_install_guide))
            }
            OutlinedButton(onClick = { open(ProjectLinks.ISSUES) },
                           modifier = Modifier.fillMaxWidth().testTag("aap2-about-issues")) {
                Text(stringResource(R.string.about_issues))
            }
            OutlinedButton(onClick = { open(ProjectLinks.LICENSE) },
                           modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_gpl))
            }
            if (openError) {
                Text("No browser is available to open this link.",
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
