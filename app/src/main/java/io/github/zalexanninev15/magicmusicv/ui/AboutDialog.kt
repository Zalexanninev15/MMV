package io.github.zalexanninev15.magicmusicv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.R
import io.github.zalexanninev15.magicmusicv.update.UpdateChecker
import kotlinx.coroutines.launch

@Composable
fun AboutDialog(version: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }

    fun open(url: String) = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        },
        title = { Text("Magic Music V") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Version $version", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Taps the vibration motor in time with the music. " +
                        "Built for OnePlus and realme first.",
                    style = MaterialTheme.typography.bodySmall,
                )

                // Three icons on one line. Stacked full-width buttons pushed the version
                // and description off the top of the dialog on a phone screen.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalIconButton(onClick = { open(UpdateChecker.REPO_URL) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Repository")
                    }
                    FilledTonalIconButton(onClick = { open(UpdateChecker.MASTODON_URL) }) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Mastodon")
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
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Repo", style = MaterialTheme.typography.labelSmall)
                    Text("Mastodon", style = MaterialTheme.typography.labelSmall)
                    Text(if (checking) "Checking" else "Updates", style = MaterialTheme.typography.labelSmall)
                }

                result?.let { r ->
                    val line = when {
                        r.error != null -> r.error
                        r.newer -> "${r.tag} is available"
                        else -> "Up to date (latest ${r.tag})"
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    if (r.newer && r.url != null) {
                        Button(onClick = { open(r.url) }) { Text("Open release") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
    )
}
