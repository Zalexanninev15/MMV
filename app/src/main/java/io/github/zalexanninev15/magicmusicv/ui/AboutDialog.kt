package io.github.zalexanninev15.magicmusicv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

                // Outlined, with a leading icon each. As bare TextButtons these were three
                // stacked lines of tinted text with no edges — they did not read as controls.
                OutlinedButton(
                    onClick = { open(UpdateChecker.REPO_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Repository")
                }

                OutlinedButton(
                    onClick = { open(UpdateChecker.MASTODON_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mastodon")
                }

                OutlinedButton(
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        checking = true
                        result = null
                        scope.launch {
                            result = UpdateChecker.check(version)
                            checking = false
                        }
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (checking) "Checking…" else "Check for updates")
                }

                result?.let { r ->
                    val line = when {
                        r.error != null -> r.error
                        r.newer -> "${r.tag} is available"
                        else -> "Up to date (latest ${r.tag})"
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    if (r.newer && r.url != null) {
                        OutlinedButton(
                            onClick = { open(r.url) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open release")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
