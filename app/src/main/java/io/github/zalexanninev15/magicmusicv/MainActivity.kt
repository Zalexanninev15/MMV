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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = HapticEngine(this)
        EngineState.load(this)

        // Built once at startup. Shown in-app and written to a file, because OxygenOS and
        // ColorOS suppress third-party logcat output unless full logging is enabled in the
        // engineering menu — so logcat is the one place this must not live.
        OplusHaptics.probe(this)
        report = HapticsReport.build(this)
        Log.i("MagicMusicV", report)
        runCatching {
            File(getExternalFilesDir(null), "haptics-report.txt").writeText(report)
        }

        setContent {
            MagicMusicScreen(
                tier = engine.tier,
                report = report,
                onStart = ::requestAndStart,
                onStop = { HapticService.stop(this) },
                onPreview = {
                    engine.intensity = EngineState.intensity.value
                    engine.preview()
                },
            )
        }
    }

    override fun onPause() {
        super.onPause()
        EngineState.save(this)
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
