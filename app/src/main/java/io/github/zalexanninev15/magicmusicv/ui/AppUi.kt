package io.github.zalexanninev15.magicmusicv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.zalexanninev15.magicmusicv.AppTheme
import io.github.zalexanninev15.magicmusicv.EngineState
import io.github.zalexanninev15.magicmusicv.Mode
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.haptics.Backend
import io.github.zalexanninev15.magicmusicv.haptics.BackendChoice
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import io.github.zalexanninev15.magicmusicv.haptics.HapticTier
import io.github.zalexanninev15.magicmusicv.haptics.MagicFeedback
import io.github.zalexanninev15.magicmusicv.haptics.resolveBackend
import io.github.zalexanninev15.magicmusicv.oem.OemSupport
import io.github.zalexanninev15.magicmusicv.oem.Vendor
import io.github.zalexanninev15.magicmusicv.settings.ProfileStore
import kotlin.math.roundToInt

private enum class Tab(val label: String) { PLAY("Play"), TUNE("Tune"), SETUP("Setup") }

@Composable
fun MagicMusicScreen(
    version: String,
    tier: HapticTier,
    report: String,
    oplusAvailable: Boolean,
    primitiveCount: Int,
    autoBackend: Backend,
    autoReason: String,
    tapCandidates: List<Pair<String, Int>>,
    onPreviewEffect: (Int, Int) -> Unit,
    onPreviewMagic: (String) -> Unit,
    onPreviewMagicBand: (String, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val theme by EngineState.theme.collectAsState()
    val colors = when (theme) {
        AppTheme.DARK -> dynamicDarkColorScheme(context)
        AppTheme.LIGHT -> dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colors) {
        var tab by remember { mutableStateOf(Tab.PLAY) }
        var showAbout by remember { mutableStateOf(false) }

        val running by EngineState.running.collectAsState()
        val error by EngineState.error.collectAsState()
        val notice by EngineState.notice.collectAsState()

        if (showAbout) AboutDialog(version = version, onDismiss = { showAbout = false })

        Scaffold { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Magic Music V", style = MaterialTheme.typography.headlineSmall)
                    // Tonal fill rather than a bare TextButton: as plain text next to a
                    // headline it read as a label, not something you could press.
                    OutlinedIconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                }

                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tab.entries.forEach { t ->
                        Chip(t.label, tab == t, true) { tab = t }
                    }
                }

                error?.let { Warning(it) }
                notice?.let { Notice(it) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    when (tab) {
                        Tab.PLAY -> PlayTab(running, onStart, onStop, onPreview)
                        Tab.TUNE -> TuneTab(
                            oplusAvailable, autoBackend, tapCandidates,
                            onPreviewEffect, onPreviewMagic, onPreviewMagicBand,
                        )
                        Tab.SETUP -> SetupTab(
                            tier, report, oplusAvailable, primitiveCount,
                            autoBackend, autoReason, running, onExport, onImport,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Play

@Composable
private fun PlayTab(
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPreview: () -> Unit,
) {
    val bpm by EngineState.bpm.collectAsState()
    val confidence by EngineState.confidence.collectAsState()
    val level by EngineState.level.collectAsState()
    val taps by EngineState.tapCount.collectAsState()
    val mode by EngineState.mode.collectAsState()
    val source by EngineState.source.collectAsState()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Text("lock ${"%.1f".format(confidence)}", style = MaterialTheme.typography.bodySmall)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { if (running) onStop() else onStart() },
            modifier = Modifier.weight(1f),
        ) { Text(if (running) "Stop" else "Start") }
        OutlinedButton(onClick = onPreview, enabled = !running) { Text("Feel it") }
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
                "Needs the screen-capture prompt. Pick the playing app rather than the whole screen. Apps that block capture (Spotify, YouTube Music) stay silent."
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
                Mode.ONSET -> "Every transient taps. Most detail, always a few ms behind."
                Mode.BEAT -> "Only the predicted beat grid. Can be scheduled ahead of the audio."
                Mode.HYBRID -> "Beat grid for the pulse plus lighter taps for snare and hats."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (OemSupport.isOplus) OplusBackgroundCard()
}

@Composable
private fun OplusBackgroundCard() {
    val context = LocalContext.current
    var batteryOk by remember { mutableStateOf(OemSupport.isBatteryUnrestricted(context)) }
    if (batteryOk) return

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

// ---------------------------------------------------------------- Tune

@Composable
private fun TuneTab(
    oplusAvailable: Boolean,
    autoBackend: Backend,
    tapCandidates: List<Pair<String, Int>>,
    onPreviewEffect: (Int, Int) -> Unit,
    onPreviewMagic: (String) -> Unit,
    onPreviewMagicBand: (String, Int) -> Unit,
) {
    val intensity by EngineState.intensity.collectAsState()
    val sensitivity by EngineState.sensitivity.collectAsState()
    val offsetMs by EngineState.offsetMs.collectAsState()
    val magicPreset by EngineState.magicPreset.collectAsState()
    val bandLow by EngineState.bandLow.collectAsState()
    val bandMid by EngineState.bandMid.collectAsState()
    val bandHigh by EngineState.bandHigh.collectAsState()
    val backendChoice by EngineState.backendChoice.collectAsState()
    val resolved = resolveBackend(backendChoice, autoBackend, oplusAvailable)

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
                "Settings - Sounds & vibration - Vibration intensity scales this on top.",
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

    if (resolved == Backend.OPLUS) {
        if (MagicFeedback.available) {
            MagicSection(magicPreset, onPreviewMagic, onPreviewMagicBand)
        }
        EffectLab(tapCandidates, onPreviewEffect)
    }
}

/**
 * The textured presets.
 *
 * Kept out of the effect lab because it answers a different question: the lab is "which
 * single effect fires per band", this is "what should the phone pretend to be".
 */
@Composable
private fun MagicSection(
    magicPreset: String,
    onPreviewMagic: (String) -> Unit,
    onPreviewMagicBand: (String, Int) -> Unit,
) {
    Section("Magical tactile feedback") {
        Text(
            "Swaps the plain graded taps for OPLUS effects that simulate physical events — " +
                "a strike landing, a switch stepping over, something bursting. Same library " +
                "the O-Haptics demo in system settings uses, driven by the music instead.",
            style = MaterialTheme.typography.bodySmall,
        )
        Chip("Off", magicPreset.isEmpty(), true) { EngineState.magicPreset.value = "" }
        MagicFeedback.presets.forEach { preset ->
            val selected = preset.id == magicPreset
            Card {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { EngineState.magicPreset.value = preset.id }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            preset.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { onPreviewMagic(preset.id) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Try ${preset.title}")
                        }
                    }
                    Text(preset.blurb, style = MaterialTheme.typography.bodySmall)
                    MagicFeedback.explain(preset).forEachIndexed { band, label ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${listOf("low ", "mid ", "high")[band]}  $label",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = { onPreviewMagicBand(preset.id, band) }) {
                                Text("Test")
                            }
                        }
                    }
                }
            }
        }
        Text(
            "These run several times longer than a tap, so each preset carries its own rate " +
                "limit and drops onsets that would overlap. Beat or Hybrid suits them better " +
                "than Onsets.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EffectLab(
    tapCandidates: List<Pair<String, Int>>,
    onPreviewEffect: (Int, Int) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(2) }
    val effectLowId by EngineState.effectLow.collectAsState()
    val effectMidId by EngineState.effectMid.collectAsState()
    val effectHighId by EngineState.effectHigh.collectAsState()

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
            LazyColumn(modifier = Modifier.height(220.dp)) {
                items(shown.size) { i ->
                    val (name, id) = shown[i]
                    val isSel = id == selected
                    Text(
                        "${if (isSel) "> " else "  "}$id  ${name.removePrefix("EFFECT_")}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = id
                                onPreviewEffect(id, 1)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPreviewEffect(selected, 0) }) { Text("Light") }
            TextButton(onClick = { onPreviewEffect(selected, 1) }) { Text("Medium") }
            TextButton(onClick = { onPreviewEffect(selected, 2) }) { Text("Strong") }
        }
        Text("Assign $selected to:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Low $effectLowId", false, true) { EngineState.effectLow.value = selected }
            Chip("Mid $effectMidId", false, true) { EngineState.effectMid.value = selected }
            Chip("High $effectHighId", false, true) { EngineState.effectHigh.value = selected }
        }
    }
}

// ---------------------------------------------------------------- Setup

@Composable
private fun SetupTab(
    tier: HapticTier,
    report: String,
    oplusAvailable: Boolean,
    primitiveCount: Int,
    autoBackend: Backend,
    autoReason: String,
    running: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val backendChoice by EngineState.backendChoice.collectAsState()
    val bypassScaling by EngineState.bypassSystemScaling.collectAsState()
    val theme by EngineState.theme.collectAsState()
    val resolved = resolveBackend(backendChoice, autoBackend, oplusAvailable)

    Section("Haptic engine") {
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Detected: " + when (resolved) {
                        Backend.OPLUS -> "O-Haptics (vendor)"
                        Backend.AOSP ->
                            if (primitiveCount > 0) "AOSP primitives" else "AOSP one-shots"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(autoReason, style = MaterialTheme.typography.bodySmall)
                Text(OemSupport.deviceLabel, style = MaterialTheme.typography.bodySmall)
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
                "Auto is detection-driven and right on every device. The overrides exist " +
                    "only to A/B the two paths where both work.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (resolved == Backend.OPLUS) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = bypassScaling,
                    onCheckedChange = { EngineState.bypassSystemScaling.value = it },
                )
                Text("Ignore system vibration intensity", style = MaterialTheme.typography.bodySmall)
            }
        }
        when (tier) {
            HapticTier.NONE -> Warning(
                "No AOSP primitives and no vendor haptic service. Taps fall back to short " +
                    "one-shots, which on a rotary ERM motor read as a buzz."
            )

            HapticTier.PARTIAL -> Warning(
                "Partial AOSP primitive set: no THUD, so kicks fall back to CLICK."
            )

            HapticTier.VENDOR_ONLY, HapticTier.FULL -> Unit
        }
    }

    Section("Theme") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Dark", theme == AppTheme.DARK, true) { EngineState.theme.value = AppTheme.DARK }
            Chip("Light", theme == AppTheme.LIGHT, true) { EngineState.theme.value = AppTheme.LIGHT }
        }
    }

    Section("Profiles") {
        var name by remember { mutableStateOf("") }
        var profiles by remember { mutableStateOf(ProfileStore.list(context)) }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Profile name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (ProfileStore.save(context, name, EngineState.snapshot())) {
                    profiles = ProfileStore.list(context)
                    EngineState.notice.value = "Saved profile \"${name.trim()}\""
                    name = ""
                } else {
                    EngineState.error.value = "Give the profile a name first"
                }
            }) { Text("Save profile") }
        }

        if (profiles.isEmpty()) {
            Text("No saved profiles yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            profiles.forEach { p ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = {
                            val s = ProfileStore.load(context, p, EngineState.DEFAULTS)
                            if (s != null) {
                                EngineState.applySnapshot(s)
                                EngineState.save(context)
                                EngineState.notice.value = "Loaded \"$p\""
                            } else {
                                EngineState.error.value = "Profile \"$p\" is unreadable"
                            }
                        }) { Text("Load") }
                        TextButton(onClick = {
                            ProfileStore.delete(context, p)
                            profiles = ProfileStore.list(context)
                        }) { Text("Delete") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onExport) { Text("Export…") }
            TextButton(onClick = onImport) { Text("Import…") }
        }
        TextButton(onClick = {
            EngineState.resetToDefaults()
            EngineState.save(context)
            EngineState.notice.value = "Settings reset to defaults"
        }) { Text("Reset to defaults") }
        Text(
            "Reset touches settings only — saved profiles are kept.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Section("Diagnostics") {
        var show by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { show = !show }) {
                Text(if (show) "Hide report" else "Haptics report")
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
        }
        if (show) {
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
    }
}

// ---------------------------------------------------------------- shared

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
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

/** Transient success message. Clears itself so it cannot pile up behind the tabs. */
@Composable
private fun Notice(text: String) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(3000)
        EngineState.notice.value = null
    }
    Card {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
