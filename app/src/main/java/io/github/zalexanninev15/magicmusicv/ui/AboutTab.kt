package io.github.zalexanninev15.magicmusicv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.EngineState
import io.github.zalexanninev15.magicmusicv.R
import io.github.zalexanninev15.magicmusicv.haptics.OplusHaptics
import io.github.zalexanninev15.magicmusicv.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * About, as a destination rather than a dialog.
 *
 * Diagnostics live here, hidden: tap the version three times. They are for tracking down
 * device-specific haptic problems, not something to look at day to day, and in Setup they
 * sat between the settings people actually change.
 */
@Composable
fun AboutTab(version: String, report: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var versionTaps by remember { mutableIntStateOf(0) }

    fun open(url: String) = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Text("Magic Music V", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Version $version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable {
                    versionTaps += 1
                    if (versionTaps == 3) EngineState.notice.value = "Diagnostics unlocked"
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            "Taps the vibration motor in time with the music. Built for OnePlus and realme first.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(onClick = { open(UpdateChecker.REPO_URL) }) {
                Icon(GitHubIcon, contentDescription = "GitHub")
            }
            FilledTonalIconButton(onClick = { open(UpdateChecker.MASTODON_URL) }) {
                Icon(MastodonIcon, contentDescription = "Mastodon")
            }
            FilledTonalIconButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    result = null
                    scope.launch {
                        result = UpdateChecker.check(version)
                        checking = false
                    }
                },
            ) {
                Icon(UpdateIcon, contentDescription = "Check for updates")
            }
            FilledTonalIconButton(onClick = { open(UpdateChecker.DONATE_URL) }) {
                Icon(Icons.Filled.Favorite, contentDescription = "Donate")
            }
        }

        if (checking) {
            Text("Checking for updates…", style = MaterialTheme.typography.bodySmall)
        }
        result?.let { r ->
            Text(
                when {
                    r.error != null -> r.error
                    r.newer -> "${r.tag} is available"
                    else -> "Up to date (latest ${r.tag})"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (r.newer && r.url != null) {
                Button(onClick = { open(r.url) }) { Text("Open release") }
            }
        }
    }

    if (versionTaps >= 3) Diagnostics(report)
}

@Composable
private fun Diagnostics(report: String) {
    var show by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        // Content-sized buttons, left-aligned — not stretched across the screen.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { show = !show }) {
                Text(if (show) "Hide report" else "Haptics report")
            }
            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                Text("Copy")
            }
        }
        if (OplusHaptics.available) {
            OutlinedButton(onClick = { OplusHaptics.forgetFailures() }) {
                Text("Retry refused effects")
            }
        }
        if (show) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        report,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
