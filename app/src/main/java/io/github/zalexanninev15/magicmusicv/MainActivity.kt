package io.github.zalexanninev15.magicmusicv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.haptics.HapticEngine
import io.github.zalexanninev15.magicmusicv.haptics.HapticsReport
import io.github.zalexanninev15.magicmusicv.haptics.OplusHaptics
import io.github.zalexanninev15.magicmusicv.service.HapticService
import io.github.zalexanninev15.magicmusicv.settings.ProfileStore
import io.github.zalexanninev15.magicmusicv.settings.SettingsCodec
import io.github.zalexanninev15.magicmusicv.ui.MagicMusicScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var engine: HapticEngine
    private var report: String = ""

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            HapticService.start(this, result.resultCode, result.data)
        } else {
            EngineState.error.value = "Capture permission was denied"
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            launchEngine()
        } else {
            EngineState.error.value = "Audio permission is required"
        }
    }

    // Storage Access Framework rather than a path in external storage: no permission is
    // needed, the user picks where the file goes, and it survives scoped-storage rules.
    /**
     * Which profile the pending export refers to, or null for the live settings.
     *
     * The SAF contract gives no way to carry a payload through to the callback, so the
     * choice has to be parked here between launching the picker and the result arriving.
     */
    private var exportProfileName: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val profile = exportProfileName
        exportProfileName = null
        if (uri == null) return@registerForActivityResult
        runCatching {
            val snapshot = if (profile == null) {
                EngineState.snapshot()
            } else {
                ProfileStore.load(this, profile, EngineState.DEFAULTS)
                    ?: error("Profile \"$profile\" is unreadable")
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(SettingsCodec.encode(snapshot).toByteArray())
            } ?: error("Could not open the file for writing")
            EngineState.notice.value =
                if (profile == null) "Settings exported" else "Exported \"$profile\""
        }.onFailure { EngineState.error.value = "Export failed: ${it.message}" }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read the file")
            val snapshot = SettingsCodec.decode(text, EngineState.DEFAULTS)
                ?: error("Not a Magic Music V settings file")
            EngineState.applySnapshot(snapshot)
            EngineState.save(this)
            EngineState.notice.value = "Settings imported"
        }.onFailure { EngineState.error.value = "Import failed: ${it.message}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Probe before the engine is constructed: HapticEngine decides its tier and default
        // backend from whether the OPLUS path resolved.
        OplusHaptics.probe(this)
        engine = HapticEngine(this)
        EngineState.load(this)

        // Built once at startup. Shown in-app and written to a file, because OxygenOS and
        // ColorOS suppress third-party logcat output unless full logging is enabled in the
        // engineering menu — so logcat is the one place this must not live.
        report = HapticsReport.build(this)
        Log.i("MagicMusicV", report)
        runCatching {
            File(getExternalFilesDir(null), "haptics-report.txt").writeText(report)
        }

        val version = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

        setContent {
            MagicMusicScreen(
                version = version,
                tier = engine.tier,
                report = report,
                oplusAvailable = OplusHaptics.available,
                primitiveCount = engine.primitiveCount,
                autoBackend = engine.autoBackend,
                autoReason = engine.autoReason,
                tapCandidates = OplusHaptics.tapCandidates,
                onPreviewEffect = { id, strength ->
                    engine.bypassSystemScaling = EngineState.bypassSystemScaling.value
                    engine.previewOplus(id, strength)
                },
                onPreviewMagic = { id ->
                    engine.bypassSystemScaling = EngineState.bypassSystemScaling.value
                    if (!engine.previewMagic(id)) {
                        EngineState.notice.value =
                            OplusHaptics.lastError ?: "Preset could not be played"
                    }
                },
                onPreviewMagicBand = { id, band ->
                    engine.bypassSystemScaling = EngineState.bypassSystemScaling.value
                    EngineState.notice.value = if (engine.previewMagicBand(id, band)) {
                        // Accepted by the service. If nothing was felt, the ROM took the call
                        // and declined to act on it — which is itself the answer.
                        null
                    } else {
                        OplusHaptics.lastError ?: "Effect could not be played"
                    }
                },
                onStart = ::requestAndStart,
                onStop = { HapticService.stop(this) },
                onPreview = {
                    engine.intensity = EngineState.intensity.value
                    engine.applyChoice(EngineState.backendChoice.value)
                    engine.oplusEffects = intArrayOf(
                        EngineState.effectLow.value,
                        EngineState.effectMid.value,
                        EngineState.effectHigh.value,
                    )
                    engine.bypassSystemScaling = EngineState.bypassSystemScaling.value
                    engine.preview()
                },
                onExport = {
                    exportProfileName = null
                    exportLauncher.launch("magic-music-v-settings.json")
                },
                onExportProfile = { name ->
                    exportProfileName = name
                    exportLauncher.launch("${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
                },
                // Some file managers hand JSON back as octet-stream, so the filter stays wide
                // and the format check happens on the contents instead.
                onImport = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
    }

    override fun onPause() {
        super.onPause()
        EngineState.save(this)
    }

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    private fun requestAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            launchEngine()
        }
    }

    private fun launchEngine() {
        EngineState.error.value = null
        if (EngineState.source.value == SourceKind.PLAYBACK_CAPTURE) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            HapticService.start(this, 0, null)
        }
    }
}
