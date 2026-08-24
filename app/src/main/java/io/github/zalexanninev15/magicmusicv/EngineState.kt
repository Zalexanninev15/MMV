package io.github.zalexanninev15.magicmusicv

import android.content.Context
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow

enum class Mode {
    /** Every detected transient taps. Busy, expressive, always slightly late. */
    ONSET,

    /** Only the predicted beat grid taps. Clean and locked, ignores fills. */
    BEAT,

    /** Beat grid for the pulse, mid/high onsets on top at lower scale. */
    HYBRID,
}

/**
 * One process-wide state holder shared by the UI and the service.
 *
 * A bound service with a Binder would be the textbook answer, but the UI and the engine
 * live in the same process and the payload is a handful of scalars; binding buys
 * lifecycle boilerplate and nothing else here.
 */
object EngineState {

    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val bpm = MutableStateFlow(0f)
    val confidence = MutableStateFlow(0f)
    val level = MutableStateFlow(0f)
    val tapCount = MutableStateFlow(0L)

    val mode = MutableStateFlow(Mode.HYBRID)
    val source = MutableStateFlow(SourceKind.PLAYBACK_CAPTURE)
    val intensity = MutableStateFlow(0.8f)
    val sensitivity = MutableStateFlow(1.5f)
    val offsetMs = MutableStateFlow(0)
    val bandLow = MutableStateFlow(true)
    val bandMid = MutableStateFlow(true)
    val bandHigh = MutableStateFlow(true)

    fun load(context: Context) {
        val p = context.getSharedPreferences("magicmusicv", Context.MODE_PRIVATE)
        mode.value = runCatching { Mode.valueOf(p.getString("mode", Mode.HYBRID.name)!!) }
            .getOrDefault(Mode.HYBRID)
        source.value = runCatching {
            SourceKind.valueOf(p.getString("source", SourceKind.PLAYBACK_CAPTURE.name)!!)
        }.getOrDefault(SourceKind.PLAYBACK_CAPTURE)
        intensity.value = p.getFloat("intensity", 0.8f)
        sensitivity.value = p.getFloat("sensitivity", 1.5f)
        offsetMs.value = p.getInt("offsetMs", 0)
        bandLow.value = p.getBoolean("bandLow", true)
        bandMid.value = p.getBoolean("bandMid", true)
        bandHigh.value = p.getBoolean("bandHigh", true)
    }

    fun save(context: Context) {
        context.getSharedPreferences("magicmusicv", Context.MODE_PRIVATE).edit().apply {
            putString("mode", mode.value.name)
            putString("source", source.value.name)
            putFloat("intensity", intensity.value)
            putFloat("sensitivity", sensitivity.value)
            putInt("offsetMs", offsetMs.value)
            putBoolean("bandLow", bandLow.value)
            putBoolean("bandMid", bandMid.value)
            putBoolean("bandHigh", bandHigh.value)
        }.apply()
    }
}
