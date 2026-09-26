@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zalexanninev15.magicmusicv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.AppTheme
import io.github.zalexanninev15.magicmusicv.EngineState
import io.github.zalexanninev15.magicmusicv.Mode
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.haptics.Backend
import io.github.zalexanninev15.magicmusicv.haptics.BackendChoice
import io.github.zalexanninev15.magicmusicv.haptics.HapticTier
import io.github.zalexanninev15.magicmusicv.haptics.MagicFeedback
import io.github.zalexanninev15.magicmusicv.haptics.MmvVoicing
import io.github.zalexanninev15.magicmusicv.haptics.OplusHaptics
import io.github.zalexanninev15.magicmusicv.library.LibraryState
import io.github.zalexanninev15.magicmusicv.library.LibraryStore
import io.github.zalexanninev15.magicmusicv.haptics.resolveBackend
import io.github.zalexanninev15.magicmusicv.oem.OemSupport
import io.github.zalexanninev15.magicmusicv.oem.Vendor
import io.github.zalexanninev15.magicmusicv.settings.ProfileStore
import kotlin.math.roundToInt

/**
 * MD3 spacing. The spec works on a 4dp grid with an 8dp system for layout; naming the three
 * values the app actually uses keeps them from drifting into arbitrary numbers.
 */
private val ScreenMargin = 16.dp
private val SectionGap = 24.dp
private val ItemGap = 8.dp

private enum class Dest(val label: String) {
    PLAY("Play"), TUNE("Tune"), LIBRARY("Library"), SETUP("Setup"), ABOUT("About")
}

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
    onUiTick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onExportProfile: (String) -> Unit,
    onImport: () -> Unit,
    onPickLibraryFolder: () -> Unit,
    onAnalyzeLibrary: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onDeleteTrackCache: (String) -> Unit,
) {
    var dest by remember { mutableStateOf(Dest.PLAY) }

    val running by EngineState.running.collectAsState()
    val error by EngineState.error.collectAsState()
    val notice by EngineState.notice.collectAsState()

    // Transient messages belong in a snackbar, not in cards wedged into the layout where they
    // shifted everything below them every time one appeared.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            EngineState.error.value = null
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            EngineState.notice.value = null
        }
    }


    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Magic Music V") },
            )
        },
        bottomBar = {
            // Three destinations on a compact screen is the navigation bar case in MD3.
            // Tabs were doing this job before, which is the two-destination pattern.
            val navLabels by EngineState.navLabels.collectAsState()
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = dest == d,
                        onClick = { dest = d },
                        icon = {
                            Icon(
                                when (d) {
                                    Dest.PLAY -> Icons.Filled.PlayArrow
                                    Dest.TUNE -> Icons.Filled.Build
                                    Dest.LIBRARY -> Icons.Filled.List
                                    Dest.SETUP -> Icons.Filled.Settings
                                    Dest.ABOUT -> Icons.Filled.Info
                                },
                                contentDescription = null,
                            )
                        },
                        // Icons alone when the setting says so; labels stay out of the way.
                        label = if (navLabels) {
                            { Text(d.label) }
                        } else {
                            null
                        },
                        alwaysShowLabel = navLabels,
                    )
                }
            }
        },
        // Centred, sitting just above the navigation bar: the primary action gets the most
        // reachable spot on the screen for either thumb.
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (running) onStop() else onStart() },
                icon = {
                    if (running) {
                        // A filled square is the universal stop glyph. Drawn directly rather
                        // than pulled from Icons.Filled.Stop, which lives in
                        // material-icons-extended — a few-MB artifact this app doesn't
                        // otherwise depend on, not worth adding for one icon.
                        Box(
                            Modifier
                                .size(18.dp)
                                .background(LocalContentColor.current, RoundedCornerShape(3.dp)),
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                },
                text = { Text(if (running) "Stop" else "Start") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            Spacer(Modifier.height(ItemGap))

            when (dest) {
                Dest.PLAY -> PlayTab(onPreview)
                Dest.TUNE -> TuneTab(
                    oplusAvailable, autoBackend, tapCandidates,
                    onPreviewEffect, onPreviewMagic, onPreviewMagicBand, onUiTick,
                )

                Dest.LIBRARY -> LibraryTab(
                    onPickFolder = onPickLibraryFolder,
                    onAnalyze = onAnalyzeLibrary,
                    onSelectTrack = onSelectTrack,
                    onDeleteCache = onDeleteTrackCache,
                )

                Dest.SETUP -> SetupTab(
                    tier, report, oplusAvailable, primitiveCount, autoBackend, autoReason,
                    running, onExport, onExportProfile, onImport,
                )

                Dest.ABOUT -> AboutTab(version, report)
            }

            // Clears the FAB and the navigation bar.
            Spacer(Modifier.height(88.dp))
        }
    }
}

// ---------------------------------------------------------------- Play

@Composable
private fun PlayTab(onPreview: () -> Unit) {
    val running by EngineState.running.collectAsState()
    val bpm by EngineState.bpm.collectAsState()
    val confidence by EngineState.confidence.collectAsState()
    val level by EngineState.level.collectAsState()
    val taps by EngineState.tapCount.collectAsState()
    val mode by EngineState.mode.collectAsState()
    val source by EngineState.source.collectAsState()
    val showBand by EngineState.showBand.collectAsState()

    // BPM lives in exactly one place. With the band on it is the band's title; with the band
    // off it moves into this card, so the reading is never duplicated and never missing.
    if (showBand) {
        PixelBand(title = if (bpm > 0f) "${bpm.roundToInt()} BPM" else "Magic Music V")
    }

    // Outlined rather than filled: this is a readout, not a surface you act on, and an
    // outline keeps it from competing with the tonal cards below.
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(ItemGap),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (showBand) {
                    Text("$taps taps", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text(
                        if (bpm > 0f) "${bpm.roundToInt()}" else "--",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text("BPM", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "$taps taps",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "beat lock ${"%.1f".format(confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Section("Source", "Where the audio comes from") {
        Choice(
            options = listOf("System", "Mic", "Library", "Namida"),
            selectedIndex = when (source) {
                SourceKind.PLAYBACK_CAPTURE -> 0
                SourceKind.MICROPHONE -> 1
                SourceKind.LOCAL_LIBRARY -> 2
                SourceKind.NAMIDA -> 3
            },
            enabled = !running,
        ) {
            EngineState.source.value = when (it) {
                0 -> SourceKind.PLAYBACK_CAPTURE
                1 -> SourceKind.MICROPHONE
                2 -> SourceKind.LOCAL_LIBRARY
                else -> SourceKind.NAMIDA
            }
        }
        Supporting(
            when (source) {
                SourceKind.PLAYBACK_CAPTURE ->
                    "Needs the screen-capture prompt. Apps that block audio capture, such as " +
                        "Spotify and YouTube Music, stay silent here."
                SourceKind.MICROPHONE ->
                    "Works with anything audible on speakers. Useless on headphones."
                SourceKind.LOCAL_LIBRARY ->
                    "Plays a track MMV has already analysed — no live capture, no FFT while " +
                        "it plays. Pick a track in the Library tab first."
                SourceKind.NAMIDA ->
                    "Follows Namida through its media session and taps from MMV's cached " +
                        "analysis. No capture, no root. Start Namida first; tracks must be " +
                        "analysed in the Library tab."
            }
        )
    }

    Section("Mode", "How taps are placed against the music") {
        Choice(
            options = listOf("Onsets", "Beat", "Hybrid"),
            selectedIndex = Mode.entries.indexOf(mode),
        ) { EngineState.mode.value = Mode.entries[it] }
        Supporting(
            when (mode) {
                Mode.ONSET -> "Every transient taps. Most detail, always a few ms behind."
                Mode.BEAT -> "Only the predicted grid. Can be scheduled ahead of the audio."
                Mode.HYBRID -> "Grid for the pulse, lighter taps for snare and hats."
            }
        )
    }

    OutlinedButton(onClick = onPreview, enabled = !running) { Text("Feel a test pattern") }
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
    onUiTick: () -> Unit,
) {
    val intensity by EngineState.intensity.collectAsState()
    val sensitivity by EngineState.sensitivity.collectAsState()
    val offsetMs by EngineState.offsetMs.collectAsState()
    val bandLow by EngineState.bandLow.collectAsState()
    val bandMid by EngineState.bandMid.collectAsState()
    val bandHigh by EngineState.bandHigh.collectAsState()
    val magicPreset by EngineState.magicPreset.collectAsState()
    val backendChoice by EngineState.backendChoice.collectAsState()
    val resolved = resolveBackend(backendChoice, autoBackend, oplusAvailable)

    Section("Bands", "Which parts of the spectrum tap") {
        Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            Toggle("Low", bandLow) { EngineState.bandLow.value = it }
            Toggle("Mid", bandMid) { EngineState.bandMid.value = it }
            Toggle("High", bandHigh) { EngineState.bandHigh.value = it }
        }
    }

    // One card, label and value on a line, the slider right under it. Three full sections
    // with display-size headings took most of a screen for three numbers.
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 5% steps from 20% to 100%.
            TickSlider(
                label = "Intensity",
                valueText = "${(intensity * 100).roundToInt()}%",
                value = intensity,
                range = 0.2f..1f,
                steps = 15,
                onChange = { EngineState.intensity.value = it },
                onTick = onUiTick,
            )
            // 0.1 steps from 1.1 to 3.0.
            TickSlider(
                label = "Sensitivity",
                valueText = "%.1f".format(sensitivity),
                value = sensitivity,
                range = 1.1f..3f,
                steps = 18,
                onChange = { EngineState.sensitivity.value = it },
                onTick = onUiTick,
                hint = "Lower catches more; too low and taps run together",
            )
            // 5 ms steps from -60 to +60.
            TickSlider(
                label = "Timing offset",
                valueText = "$offsetMs ms",
                value = offsetMs.toFloat(),
                range = -60f..60f,
                steps = 23,
                onChange = { EngineState.offsetMs.value = it.roundToInt() },
                onTick = onUiTick,
                hint = "Negative values only apply in Beat and Hybrid",
            )
        }
    }

    if (resolved == Backend.OPLUS || resolved == Backend.OPLUS_MMV) {
        HorizontalDivider()
        EffectLab(tapCandidates, onPreviewEffect)

        if (MagicFeedback.available) {
            MagicSection(magicPreset, onPreviewMagic, onPreviewMagicBand)
        }
    }
}

@Composable
private fun EffectLab(
    tapCandidates: List<Pair<String, Int>>,
    onPreviewEffect: (Int, Int) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(2) }
    val effectLow by EngineState.effectLow.collectAsState()
    val effectMid by EngineState.effectMid.collectAsState()
    val effectHigh by EngineState.effectHigh.collectAsState()

    Section("Effect lab", "Audition vendor effects and assign them to bands") {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter ${tapCandidates.size} effects") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val shown = remember(filter, tapCandidates) {
            if (filter.isBlank()) tapCandidates
            else tapCandidates.filter { it.first.contains(filter, true) }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(shown.size) { i ->
                    val (name, id) = shown[i]
                    CompactRow(
                        title = name.removePrefix("EFFECT_"),
                        subtitle = "id $id",
                        selected = id == selected,
                        onClick = {
                            selected = id
                            onPreviewEffect(id, 1)
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = { onPreviewEffect(selected, 0) },
            ) { Text("Light") }
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = { onPreviewEffect(selected, 1) },
            ) { Text("Medium") }
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = { onPreviewEffect(selected, 2) },
            ) { Text("Strong") }
        }
        Supporting("Assign effect $selected to a band:")
        Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { EngineState.effectLow.value = selected },
            ) { Text("Low $effectLow") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { EngineState.effectMid.value = selected },
            ) { Text("Mid $effectMid") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { EngineState.effectHigh.value = selected },
            ) { Text("High $effectHigh") }
        }
    }
}

@Composable
private fun MagicSection(
    magicPreset: String,
    onPreviewMagic: (String) -> Unit,
    onPreviewMagicBand: (String, Int) -> Unit,
) {
    val active = MagicFeedback.parse(magicPreset)
    var expanded by remember { mutableStateOf(active.isNotEmpty()) }

    // The header states what is on in words, so the section never needs opening just to find
    // out. It replaces the old "Off" button, which was always visible and said nothing about
    // whether anything was actually selected.
    ListItem(
        headlineContent = { Text("Advanced textures") },
        supportingContent = {
            Text(
                when (active.size) {
                    0 -> "Off - experimental"
                    1 -> "On: ${active[0].title}"
                    else -> "On: ${active.size} textures, rotating per hit"
                }
            )
        },
        trailingContent = {
            Switch(
                checked = active.isNotEmpty(),
                onCheckedChange = { on ->
                    // Turning on with nothing chosen picks the first preset, so the switch
                    // always does something visible instead of silently staying off.
                    EngineState.magicPreset.value =
                        if (on) MagicFeedback.presets.first().id else ""
                    expanded = on || expanded
                },
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { expanded = !expanded },
    )

    if (!expanded) return

    Supporting(
        "Pick one or several. With several, each band rotates through them hit by hit. " +
            "They replace the backend's voicing, and several effects are longer than a beat."
    )

    MagicFeedback.presets.forEach { preset ->
        val on = active.any { it.id == preset.id }
        // Selected presets get a filled tonal card; unselected an outline. The state is
        // readable at a glance instead of from a text colour.
        val cardColors = if (on) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        }
        Card(
            colors = cardColors,
            border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        EngineState.magicPreset.value = MagicFeedback.toggle(magicPreset, preset.id)
                    }
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = on,
                    onCheckedChange = {
                        EngineState.magicPreset.value = MagicFeedback.toggle(magicPreset, preset.id)
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(preset.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        preset.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = { onPreviewMagic(preset.id) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Try ${preset.title}")
                }
            }
            Column(
                Modifier.padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
            ) {
                MagicFeedback.explain(preset).forEachIndexed { band, label ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${listOf("low", "mid", "high")[band]}  $label",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onPreviewMagicBand(preset.id, band) }) {
                            Text("Test")
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Library

@Composable
private fun LibraryTab(
    onPickFolder: () -> Unit,
    onAnalyze: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onDeleteCache: (String) -> Unit,
) {
    val context = LocalContext.current
    val tracks by LibraryState.tracks.collectAsState()
    val cache by LibraryState.cache.collectAsState()
    val analyzing by LibraryState.analyzing.collectAsState()
    val progress by LibraryState.progress.collectAsState()
    val current by LibraryState.currentlyAnalyzing.collectAsState()
    val failed by LibraryState.failed.collectAsState()
    val selected by LibraryState.selectedTrackUri.collectAsState()

    Section("Local library", "FLAC, MP3, M4A, Opus — analysed once, cached on device") {
        Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            FilledTonalButton(onClick = onPickFolder) {
                Text(if (tracks.isEmpty()) "Choose folder" else "Change folder")
            }
            val pending = tracks.count { cache[it.uri] == null }
            Button(
                enabled = !analyzing && pending > 0,
                onClick = onAnalyze,
            ) { Text(if (pending > 0) "Analyse ($pending)" else "All analysed") }
        }
        if (analyzing) {
            val (done, total) = progress
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Supporting("Analysing ${current ?: "…"} ($done/$total)")
        }
        if (tracks.isNotEmpty()) {
            val cachedBytes = remember(cache.size) { LibraryStore.cacheSizeBytes(context) }
            Supporting(
                "${tracks.size} files, ${cache.size} analysed, " +
                    "${"%.1f".format(cachedBytes / 1_048_576f)} MB cached on device"
            )
            if (failed > 0) {
                Supporting("$failed could not be decoded and were skipped.")
            }
        }
    }

    if (tracks.isEmpty()) {
        Supporting(
            "No folder chosen yet. Analysis decodes each file once and caches the result — " +
                "the first pass takes a while for a big folder, playback afterwards costs no " +
                "live analysis at all."
        )
        return
    }

    var query by remember { mutableStateOf("") }
    val checked by LibraryState.checked.collectAsState()

    // Every word typed must appear somewhere in the name, in any order and as a fragment:
    // "sukiga" finds nothing, but "suki koito" finds "07 - 好き… - 小糸 侑", and so does "flac 07".
    val words = remember(query) {
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val shown = remember(tracks, words) {
        if (words.isEmpty()) tracks
        else tracks.filter { t -> val n = t.displayName.lowercase(); words.all { it in n } }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search ${tracks.size} tracks") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(ItemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pendingChecked = checked.count { cache[it] == null }
        Button(
            enabled = !analyzing && pendingChecked > 0,
            onClick = { LibraryState.analyze(context, checked) },
        ) { Text("Analyse selected ($pendingChecked)") }
        OutlinedButton(
            onClick = {
                LibraryState.checked.value =
                    if (shown.all { it.uri in checked }) checked - shown.map { it.uri }.toSet()
                    else checked + shown.map { it.uri }
            },
        ) { Text(if (shown.isNotEmpty() && shown.all { it.uri in checked }) "Unselect" else "Select shown") }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(shown.size) { i ->
                val track = shown[i]
                val cached = cache[track.uri]
                CompactRow(
                    title = track.displayName,
                    subtitle = when {
                        cached == null -> "Not analysed"
                        cached.bpm > 0f -> "${cached.bpm.roundToInt()} BPM · " +
                            "${cached.durationMs / 1000 / 60}:" +
                            "${(cached.durationMs / 1000 % 60).toString().padStart(2, '0')}"
                        else -> "Analysed, no steady tempo found"
                    },
                    selected = track.uri == selected,
                    // Tapping an analysed row picks it for playback; the checkbox is for
                    // choosing what to analyse, so the two never fight over one gesture.
                    onClick = if (cached != null) {
                        { onSelectTrack(track.uri) }
                    } else {
                        null
                    },
                    leading = {
                        Checkbox(
                            checked = track.uri in checked,
                            onCheckedChange = { on ->
                                LibraryState.checked.value =
                                    if (on) checked + track.uri else checked - track.uri
                            },
                        )
                    },
                    trailing = if (cached != null) {
                        {
                            IconButton(onClick = { onDeleteCache(track.uri) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove cache")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
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
    onExportProfile: (String) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val backendChoice by EngineState.backendChoice.collectAsState()
    val bypassScaling by EngineState.bypassSystemScaling.collectAsState()
    val theme by EngineState.theme.collectAsState()
    val dynamicColor by EngineState.dynamicColor.collectAsState()
    val character by EngineState.character.collectAsState()
    val instrument by EngineState.instrument.collectAsState()
    val showBand by EngineState.showBand.collectAsState()
    val bandPalette by EngineState.bandPalette.collectAsState()
    val enhancedAnimations by EngineState.enhancedAnimations.collectAsState()
    val navLabels by EngineState.navLabels.collectAsState()
    val resolved = resolveBackend(backendChoice, autoBackend, oplusAvailable)

    Section("Haptic engine", OemSupport.deviceLabel) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(ScreenMargin),
                verticalArrangement = Arrangement.spacedBy(ItemGap),
            ) {
                Text(
                    when (resolved) {
                        Backend.OPLUS_MMV -> "O-Haptics by MMV (experimental)"
                        Backend.OPLUS -> "O-Haptics"
                        Backend.AOSP ->
                            if (primitiveCount > 0) "AOSP primitives" else "AOSP one-shots"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Supporting(autoReason)
                if (resolved == Backend.OPLUS_MMV) {
                    MmvVoicing.describe().forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        when (tier) {
            HapticTier.NONE -> Supporting(
                "No AOSP primitives and no vendor engine. Taps fall back to short one-shots, " +
                    "which on a rotary motor read as a buzz."
            )

            HapticTier.PARTIAL -> Supporting("No THUD primitive, so kicks fall back to CLICK.")
            else -> Unit
        }

        if (oplusAvailable) {
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                listOf(
                    "Auto" to BackendChoice.AUTO,
                    "AOSP" to BackendChoice.AOSP,
                    "O-Haptics" to BackendChoice.OPLUS,
                    "MMV *" to BackendChoice.OPLUS_MMV,
                ).forEach { (label, choice) ->
                    FilterChip(
                        selected = backendChoice == choice,
                        onClick = { EngineState.backendChoice.value = choice },
                        enabled = !running,
                        label = { Text(label) },
                    )
                }
            }
            Supporting(
                "* MMV is experimental. It keeps the O-Haptics taps exactly as they are and " +
                    "layers a second effect behind hard hits and beats. Auto never selects it."
            )
            SwitchRow(
                "Ignore system vibration intensity",
                "Detaches effects from the slider in system settings",
                bypassScaling,
            ) { EngineState.bypassSystemScaling.value = it }
        }
    }

    if (OemSupport.isOplus && !OemSupport.isBatteryUnrestricted(context)) {
        Section(
            when (OemSupport.vendor) {
                Vendor.ONEPLUS -> "OxygenOS will freeze this app"
                Vendor.REALME -> "realme UI will freeze this app"
                else -> "ColorOS will freeze this app"
            },
            "Both switches are needed",
        ) {
            Supporting(
                "Taps stop a few minutes after the screen goes off unless the app is " +
                    "unrestricted and allowed to auto-start."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                FilledTonalButton(
                    onClick = { OemSupport.requestBatteryUnrestricted(context) },
                ) { Text("Battery") }
                FilledTonalButton(
                    onClick = { OemSupport.openAutoStartSettings(context) },
                ) { Text("Auto-start") }
            }
        }
    }

    Section("Appearance") {
        Choice(
            options = listOf("System", "Dark", "Light"),
            selectedIndex = AppTheme.entries.indexOf(theme),
        ) { EngineState.theme.value = AppTheme.entries[it] }
        SwitchRow(
            "Beat visualiser",
            if (showBand) "Animated band on the Play tab"
            else "Off — BPM is shown in the meters card instead",
            showBand,
        ) { EngineState.showBand.value = it }

        // Character and instrument only mean anything while the band is drawn.
        if (showBand) {
            SwitchRow(
                "Enhanced animations",
                if (enhancedAnimations) "Squash, leans, expressions and power-chord sparks"
                else "Simple sway and nod",
                enhancedAnimations,
            ) { EngineState.enhancedAnimations.value = it }


            Supporting("Band colours")
            Choice(
                options = BandPalette.entries.map { it.title },
                selectedIndex = BandPalette.entries.indexOf(bandPalette),
            ) { EngineState.bandPalette.value = BandPalette.entries[it] }

            Supporting("Band character")
            Choice(
                options = PixelCharacter.entries.map { it.title },
                selectedIndex = PixelCharacter.entries.indexOf(character),
            ) { EngineState.character.value = PixelCharacter.entries[it] }

            Supporting("Instrument")
            Choice(
                options = PixelInstrument.entries.map { it.title },
                selectedIndex = PixelInstrument.entries.indexOf(instrument),
            ) { EngineState.instrument.value = PixelInstrument.entries[it] }
        }

        SwitchRow(
            "Navigation labels",
            if (navLabels) "Icons with labels" else "Icons only",
            navLabels,
        ) { EngineState.navLabels.value = it }

        SwitchRow(
            "Material You colours",
            if (dynamicColor) "Palette from your wallpaper" else "The app's own palette",
            dynamicColor,
        ) { EngineState.dynamicColor.value = it }
    }

    ProfilesSection(onExport, onExportProfile, onImport)
}

@Composable
private fun ProfilesSection(
    onExport: () -> Unit,
    onExportProfile: (String) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(ProfileStore.list(context)) }
    var activeProfile by remember { mutableStateOf<String?>(null) }

    Section("Profiles", "Saved separately from the live settings") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Profile name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (ProfileStore.save(context, name, EngineState.snapshot())) {
                    profiles = ProfileStore.list(context)
                    activeProfile = name.trim()
                    EngineState.notice.value = "Saved \"${name.trim()}\""
                    name = ""
                } else {
                    EngineState.error.value = "Give the profile a name first"
                }
            },
        ) { Text("Save current settings") }

        if (profiles.isEmpty()) {
            Supporting("No saved profiles yet.")
        } else {
            // Same shape as the effect lab: an outlined container holding a scrollable list.
            // A filled card with tonal buttons inside put surfaceContainerHighest next to
            // secondaryContainer, one tonal step apart, and the buttons disappeared into it.
            OutlinedCard(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(profiles.size) { i ->
                        val p = profiles[i]
                        val active = p == activeProfile
                        CompactRow(
                            title = p,
                            subtitle = if (active) "Active" else null,
                            selected = active,
                            onClick = {
                                val snap = ProfileStore.load(context, p, EngineState.DEFAULTS)
                                if (snap != null) {
                                    EngineState.applySnapshot(snap)
                                    EngineState.save(context)
                                    activeProfile = p
                                    EngineState.notice.value = "Loaded \"$p\""
                                } else {
                                    EngineState.error.value = "\"$p\" is unreadable"
                                }
                            },
                            trailing = {
                                Row {
                                    IconButton(onClick = { onExportProfile(p) }) {
                                        Icon(
                                            Icons.Filled.Share,
                                            contentDescription = "Export $p",
                                        )
                                    }
                                    IconButton(onClick = {
                                        ProfileStore.delete(context, p)
                                        profiles = ProfileStore.list(context)
                                        if (activeProfile == p) activeProfile = null
                                        EngineState.notice.value = "Deleted \"$p\""
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete $p",
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            FilledTonalButton(onClick = onExport) { Text("Export all") }
            FilledTonalButton(onClick = onImport) { Text("Import") }
        }
        OutlinedButton(
            onClick = {
                EngineState.resetToDefaults()
                EngineState.save(context)
                EngineState.notice.value = "Settings reset to defaults"
            },
        ) { Text("Reset to defaults") }
    }
}

// ---------------------------------------------------------------- building blocks

/**
 * A labelled slider with visible divisions and a haptic knock on each one.
 *
 * Snapping is done here rather than trusted to the Slider: whether its callback reports the
 * snapped value or the raw drag position has varied across Material versions, and a raw
 * position would knock on every pixel of movement instead of once per division.
 */
@Composable
private fun TickSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onTick: () -> Unit,
    hint: String? = null,
) {
    val intervals = steps + 1
    val span = range.endInclusive - range.start
    fun indexOf(v: Float) = (((v - range.start) / span) * intervals).roundToInt().coerceIn(0, intervals)
    var lastIndex by remember { mutableIntStateOf(indexOf(value)) }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { v ->
                val i = indexOf(v)
                if (i != lastIndex) {
                    lastIndex = i
                    onTick()
                }
                onChange(range.start + span * i / intervals)
            },
            valueRange = range,
            steps = steps,
            // Divisions visible but quiet, so they guide without cluttering the track.
            colors = SliderDefaults.colors(
                activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
                inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A dense list row for the scrolling lists.
 *
 * ListItem is sized for navigation lists — 56 to 72 dp per row with generous padding — which
 * in a 360-track library meant a thumb-width of scrolling per handful of songs.
 */
@Composable
private fun CompactRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(start = if (leading != null) 4.dp else 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Section header plus its content, on the MD3 title/body pairing. */
@Composable
private fun Section(
    title: String,
    supporting: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (supporting != null) Supporting(supporting)
        content()
    }
}

@Composable
private fun Supporting(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Mutually exclusive options as a segmented button — the MD3 control for this, where the app
 * previously used filter chips, which are for non-exclusive filtering.
 */
@Composable
private fun Choice(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
