package io.github.zalexanninev15.magicmusicv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.EngineState
import io.github.zalexanninev15.magicmusicv.Mode
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.haptics.Backend
import io.github.zalexanninev15.magicmusicv.haptics.BackendChoice
import io.github.zalexanninev15.magicmusicv.haptics.HapticTier
import io.github.zalexanninev15.magicmusicv.haptics.resolveBackend
import io.github.zalexanninev15.magicmusicv.oem.OemSupport
import io.github.zalexanninev15.magicmusicv.oem.Vendor
import kotlin.math.roundToInt

@Composable
fun MagicMusicScreen(
    tier: HapticTier,
    report: String,
    oplusAvailable: Boolean,
    primitiveCount: Int,
    autoBackend: Backend,
    autoReason: String,
    tapCandidates: List<Pair<String, Int>>,
    onPreviewEffect: (Int, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
    else dynamicLightColorScheme(context)

    MaterialTheme(colorScheme = colors) {
        val running by EngineState.running.collectAsState()
        val error by EngineState.error.collectAsState()
        val bpm by EngineState.bpm.collectAsState()
        val confidence by EngineState.confidence.collectAsState()
        val level by EngineState.level.collectAsState()
        val taps by EngineState.tapCount.collectAsState()

        val mode by EngineState.mode.collectAsState()
        val source by EngineState.source.collectAsState()
        val intensity by EngineState.intensity.collectAsState()
        val sensitivity by EngineState.sensitivity.collectAsState()
        val offsetMs by EngineState.offsetMs.collectAsState()
        val bandLow by EngineState.bandLow.collectAsState()
        val bandMid by EngineState.bandMid.collectAsState()
        val bandHigh by EngineState.bandHigh.collectAsState()
        val backendChoice by EngineState.backendChoice.collectAsState()
        val resolvedBackend = resolveBackend(backendChoice, autoBackend, oplusAvailable)
        val bypassScaling by EngineState.bypassSystemScaling.collectAsState()
        val effectLowId by EngineState.effectLow.collectAsState()
        val effectMidId by EngineState.effectMid.collectAsState()
        val effectHighId by EngineState.effectHigh.collectAsState()

        var batteryOk by remember { mutableStateOf(OemSupport.isBatteryUnrestricted(context)) }
        var showReport by remember { mutableStateOf(false) }
        var filter by remember { mutableStateOf("") }
        var selectedEffect by remember { mutableIntStateOf(2) }
        val clipboard = LocalClipboardManager.current

        Scaffold { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Magic Music V", style = MaterialTheme.typography.headlineMedium)
                Text(
                    OemSupport.deviceLabel + "  -  " + when (tier) {
                        HapticTier.FULL -> "linear motor, full AOSP primitive set"
                        HapticTier.PARTIAL -> "limited AOSP primitive set"
                        HapticTier.VENDOR_ONLY -> "vendor haptics (O-Haptics)"
                        HapticTier.NONE -> "no usable haptic engine"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                when (tier) {
                    HapticTier.NONE -> Warning(
                        "No AOSP primitives and no vendor haptic service. Taps fall back to short " +
                            "one-shots, which on a rotary ERM motor read as a buzz rather than a knock."
                    )

                    HapticTier.PARTIAL -> Warning(
                        "Partial AOSP primitive set: no THUD, so kicks fall back to CLICK and feel thinner."
                    )

                    // Not a downgrade. OPLUS never wired up the AOSP compose HAL, so a
                    // flagship X-axis LRA still reports zero primitives.
                    HapticTier.VENDOR_ONLY, HapticTier.FULL -> Unit
                }

                error?.let { Warning(it) }

                if (OemSupport.isOplus && !batteryOk) {
                    Card {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                when (OemSupport.vendor) {
                                    Vendor.ONEPLUS -> "OxygenOS will freeze this app"
                                    Vendor.REALME -> "realme UI will freeze this app"
                                    else -> "ColorOS will freeze this app"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Taps stop a few minutes after the screen goes off unless the app is " +
                                    "unrestricted and allowed to auto-start. Both switches are needed.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    OemSupport.requestBatteryUnrestricted(context)
                                    batteryOk = OemSupport.isBatteryUnrestricted(context)
                                }) { Text("Unrestrict battery") }
                                TextButton(onClick = { OemSupport.openAutoStartSettings(context) }) {
                                    Text("Auto-start")
                                }
                            }
                        }
                    }
                }

                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (bpm > 0f) "${bpm.roundToInt()} BPM" else "-- BPM",
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                            )
                            Text("$taps taps", style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(
                            progress = { level },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                        Text(
                            "lock ${"%.1f".format(confidence)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Section("Source") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("System audio", source == SourceKind.PLAYBACK_CAPTURE, !running) {
                            EngineState.source.value = SourceKind.PLAYBACK_CAPTURE
                        }
                        Chip("Microphone", source == SourceKind.MICROPHONE, !running) {
                            EngineState.source.value = SourceKind.MICROPHONE
                        }
                    }
                    Text(
                        if (source == SourceKind.PLAYBACK_CAPTURE)
                            "Needs the screen-capture prompt. Apps that block audio capture (Spotify, YouTube Music) stay silent here."
                        else
                            "Works with anything audible on speakers. Useless on headphones.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Section("Mode") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Onsets", mode == Mode.ONSET, true) { EngineState.mode.value = Mode.ONSET }
                        Chip("Beat", mode == Mode.BEAT, true) { EngineState.mode.value = Mode.BEAT }
                        Chip("Hybrid", mode == Mode.HYBRID, true) { EngineState.mode.value = Mode.HYBRID }
                    }
                    Text(
                        when (mode) {
                            Mode.ONSET -> "Every transient taps. Most detail, always a few ms behind the audio."
                            Mode.BEAT -> "Only the predicted beat grid. Can be scheduled ahead of the audio."
                            Mode.HYBRID -> "Beat grid for the pulse plus lighter taps for snare and hats."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Section("Bands") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Low", bandLow, true) { EngineState.bandLow.value = !bandLow }
                        Chip("Mid", bandMid, true) { EngineState.bandMid.value = !bandMid }
                        Chip("High", bandHigh, true) { EngineState.bandHigh.value = !bandHigh }
                    }
                }

                Section("Intensity  ${(intensity * 100).roundToInt()}%") {
                    Slider(
                        value = intensity,
                        onValueChange = { EngineState.intensity.value = it },
                        valueRange = 0.2f..1f,
                    )
                    if (OemSupport.isOplus) {
                        Text(
                            "Settings - Sounds & vibration - Vibration intensity scales this on top. Set it high.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Section("Sensitivity  ${"%.2f".format(sensitivity)}") {
                    Slider(
                        value = sensitivity,
                        onValueChange = { EngineState.sensitivity.value = it },
                        valueRange = 1.05f..3f,
                    )
                    Text(
                        "Lower catches more; too low and the taps run together.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Section("Timing offset  $offsetMs ms") {
                    Slider(
                        value = offsetMs.toFloat(),
                        onValueChange = { EngineState.offsetMs.value = it.roundToInt() },
                        valueRange = -60f..60f,
                    )
                    Text(
                        "Negative values only bite in Beat and Hybrid, where the beat is predicted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (running) onStop() else onStart() },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (running) "Stop" else "Start") }
                    OutlinedButton(onClick = onPreview, enabled = !running) { Text("Feel it") }
                }

                Section("Haptic engine") {
                    Card {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Detected: " + when (resolvedBackend) {
                                    Backend.OPLUS -> "O-Haptics (vendor)"
                                    Backend.AOSP -> if (primitiveCount > 0) "AOSP primitives"
                                    else "AOSP one-shots"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(autoReason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (oplusAvailable) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Auto", backendChoice == BackendChoice.AUTO, !running) {
                                EngineState.backendChoice.value = BackendChoice.AUTO
                            }
                            Chip("AOSP", backendChoice == BackendChoice.AOSP, !running) {
                                EngineState.backendChoice.value = BackendChoice.AOSP
                            }
                            Chip("O-Haptics", backendChoice == BackendChoice.OPLUS, !running) {
                                EngineState.backendChoice.value = BackendChoice.OPLUS
                            }
                        }
                        Text(
                            "Auto is detection-driven and the right setting on every device. " +
                                "The overrides exist only to A/B the two paths where both work.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (resolvedBackend == Backend.OPLUS) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = bypassScaling,
                                onCheckedChange = { EngineState.bypassSystemScaling.value = it },
                            )
                            Text(
                                "Ignore system vibration intensity",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (resolvedBackend == Backend.OPLUS) {
                    Section("Effect lab") {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Filter ${tapCandidates.size} tap effects") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val shown = remember(filter, tapCandidates) {
                            if (filter.isBlank()) tapCandidates
                            else tapCandidates.filter { it.first.contains(filter, true) }
                        }
                        Card {
                            LazyColumn(modifier = Modifier.height(240.dp)) {
                                items(shown.size) { i ->
                                    val (name, id) = shown[i]
                                    val selected = id == selectedEffect
                                    Text(
                                        "${if (selected) "> " else "  "}$id  ${name.removePrefix("EFFECT_")}",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedEffect = id
                                                onPreviewEffect(id, 1)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onPreviewEffect(selectedEffect, 0) }) { Text("Light") }
                            TextButton(onClick = { onPreviewEffect(selectedEffect, 1) }) { Text("Medium") }
                            TextButton(onClick = { onPreviewEffect(selectedEffect, 2) }) { Text("Strong") }
                        }
                        Text("Assign effect $selectedEffect to:", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Low $effectLowId", false, true) {
                                EngineState.effectLow.value = selectedEffect
                            }
                            Chip("Mid $effectMidId", false, true) {
                                EngineState.effectMid.value = selectedEffect
                            }
                            Chip("High $effectHighId", false, true) {
                                EngineState.effectHigh.value = selectedEffect
                            }
                        }
                    }
                }

                Section("Diagnostics") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showReport = !showReport }) {
                            Text(if (showReport) "Hide report" else "Haptics report")
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(report))
                        }) { Text("Copy") }
                    }
                    if (showReport) {
                        Card {
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
                    Text(
                        "Also written to Android/data/<package>/files/haptics-report.txt",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun Warning(text: String) {
    Card {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
