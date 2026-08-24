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
import io.github.zalexanninev15.magicmusicv.haptics.OplusHaptics
import io.github.zalexanninev15.magicmusicv.service.HapticService
import io.github.zalexanninev15.magicmusicv.ui.MagicMusicScreen

class MainActivity : ComponentActivity() {

    private lateinit var engine: HapticEngine

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

        // Runs once, costs a few reflection lookups, and is the only way to find out what
        // this particular ColorOS build exposes. `adb logcat -s OplusHaptics` prints it.
        OplusHaptics.probe(this)
        Log.i("MagicMusicV", OplusHaptics.describe())

        setContent {
            MagicMusicScreen(
                tier = engine.tier,
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
