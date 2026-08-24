package io.github.zalexanninev15.magicmusicv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
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

                TextButton(onClick = { open(UpdateChecker.REPO_URL) }) { Text("Repository") }
                TextButton(onClick = { open(UpdateChecker.MASTODON_URL) }) { Text("Mastodon") }

                TextButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        result = null
                        scope.launch {
                            result = UpdateChecker.check(version)
                            checking = false
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check for updates") }

                result?.let { r ->
                    val line = when {
                        r.error != null -> r.error
                        r.newer -> "${r.tag} is available"
                        else -> "Up to date (latest ${r.tag})"
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    if (r.newer && r.url != null) {
                        TextButton(onClick = { open(r.url) }) { Text("Open release") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
