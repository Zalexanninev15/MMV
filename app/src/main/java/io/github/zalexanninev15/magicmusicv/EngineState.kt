package io.github.zalexanninev15.magicmusicv

import android.content.Context
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.haptics.BackendChoice
import io.github.zalexanninev15.magicmusicv.ui.BandPalette
import io.github.zalexanninev15.magicmusicv.ui.PixelCharacter
import io.github.zalexanninev15.magicmusicv.ui.PixelInstrument
import io.github.zalexanninev15.magicmusicv.settings.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

enum class Mode {
    /** Every detected transient taps. Busy, expressive, always slightly late. */
    ONSET,

    /** Only the predicted beat grid taps. Clean and locked, ignores fills. */
    BEAT,

    /** Beat grid for the pulse, mid/high onsets on top at lower scale. */
    HYBRID,
}

/** SYSTEM follows the device setting; all three use Material You dynamic colour. */
enum class AppTheme { SYSTEM, DARK, LIGHT }

/**
 * One tap, published for the visualiser at the moment it is handed to the haptic engine.
 *
 * Deliberately the same event that drives the motor rather than a second analysis pass —
 * if these came from different places the picture and the vibration would drift apart.
 */
data class Pulse(val at: Long, val band: Int, val strength: Float, val accent: Boolean)

/**
 * One process-wide state holder shared by the UI and the service.
 *
 * A bound service with a Binder would be the textbook answer, but the UI and the engine
 * live in the same process and the payload is a handful of scalars; binding buys
 * lifecycle boilerplate and nothing else here.
 */
object EngineState {

    private const val PREFS = "magicmusicv"

    /**
     * The one place defaults live. Reset, first launch and a failed settings import all
     * read from here, so they cannot drift apart.
     *
     * Declared first, and it has to stay first: every MutableStateFlow below is initialised
     * from it, and Kotlin runs property initialisers in source order. Moved to the bottom of
     * the object — where it reads more naturally — each of those flows sees an uninitialised
     * DEFAULTS and the build fails.
     */
    val DEFAULTS = SettingsSnapshot(
        mode = Mode.HYBRID,
        source = SourceKind.PLAYBACK_CAPTURE,
        intensity = 0.8f,
        sensitivity = 1.5f,
        offsetMs = 0,
        bandLow = true,
        bandMid = true,
        bandHigh = true,
        effectLow = 2,      // EFFECT_MODERATE_SHORT_VIBRATE_ONCE
        effectMid = 1,      // EFFECT_WEAK_SHORT_VIBRATE_ONCE
        effectHigh = 0,     // EFFECT_WEAKEST_SHORT_VIBRATE_ONCE
        bypassSystemScaling = false,
        backendChoice = BackendChoice.AUTO,
        theme = AppTheme.SYSTEM,
        dynamicColor = true,
        showBand = true,
        bandPalette = BandPalette.STANDARD,
        enhancedAnimations = true,
        heroMilestones = true,
        character = PixelCharacter.ROADIE,
        instrument = PixelInstrument.GUITAR,
        magicPreset = "",
    )

    // --- live engine readings, never persisted ---
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)

    val bpm = MutableStateFlow(0f)
    val confidence = MutableStateFlow(0f)
    val level = MutableStateFlow(0f)
    val tapCount = MutableStateFlow(0L)

    // --- persisted settings ---
    val mode = MutableStateFlow(DEFAULTS.mode)
    val source = MutableStateFlow(DEFAULTS.source)
    val intensity = MutableStateFlow(DEFAULTS.intensity)
    val sensitivity = MutableStateFlow(DEFAULTS.sensitivity)
    val offsetMs = MutableStateFlow(DEFAULTS.offsetMs)
    val bandLow = MutableStateFlow(DEFAULTS.bandLow)
    val bandMid = MutableStateFlow(DEFAULTS.bandMid)
    val bandHigh = MutableStateFlow(DEFAULTS.bandHigh)

    /** OPLUS effect ids per band. Defaults are the three graded short single taps. */
    val effectLow = MutableStateFlow(DEFAULTS.effectLow)
    val effectMid = MutableStateFlow(DEFAULTS.effectMid)
    val effectHigh = MutableStateFlow(DEFAULTS.effectHigh)

    val bypassSystemScaling = MutableStateFlow(DEFAULTS.bypassSystemScaling)
    val backendChoice = MutableStateFlow(DEFAULTS.backendChoice)
    val theme = MutableStateFlow(DEFAULTS.theme)

    /** Material You colour from the wallpaper; off falls back to the app's own palette. */
    val dynamicColor = MutableStateFlow(DEFAULTS.dynamicColor)

    /** The animated pixel band on the Play tab. */
    val showBand = MutableStateFlow(DEFAULTS.showBand)

    /** Fixed video palette, or the app's Material You scheme. */
    val bandPalette = MutableStateFlow(DEFAULTS.bandPalette)

    /** Squash and stretch, leans, eye expressions and power-chord sparks on the character. */
    val enhancedAnimations = MutableStateFlow(DEFAULTS.enhancedAnimations)

    /** Hero X takes over the band for five seconds at every thousandth tap. */
    val heroMilestones = MutableStateFlow(DEFAULTS.heroMilestones)

    val character = MutableStateFlow(DEFAULTS.character)
    val instrument = MutableStateFlow(DEFAULTS.instrument)

    /** Latest tap, for the visualiser. Not persisted. */
    val pulse = MutableStateFlow<Pulse?>(null)

    /** MagicFeedback preset id, or "" for off. */
    val magicPreset = MutableStateFlow(DEFAULTS.magicPreset)

    fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        mode = mode.value,
        source = source.value,
        intensity = intensity.value,
        sensitivity = sensitivity.value,
        offsetMs = offsetMs.value,
        bandLow = bandLow.value,
        bandMid = bandMid.value,
        bandHigh = bandHigh.value,
        effectLow = effectLow.value,
        effectMid = effectMid.value,
        effectHigh = effectHigh.value,
        bypassSystemScaling = bypassSystemScaling.value,
        backendChoice = backendChoice.value,
        theme = theme.value,
        dynamicColor = dynamicColor.value,
        showBand = showBand.value,
        bandPalette = bandPalette.value,
        enhancedAnimations = enhancedAnimations.value,
        heroMilestones = heroMilestones.value,
        character = character.value,
        instrument = instrument.value,
        magicPreset = magicPreset.value,
    )

    fun applySnapshot(s: SettingsSnapshot) {
        mode.value = s.mode
        source.value = s.source
        intensity.value = s.intensity
        sensitivity.value = s.sensitivity
        offsetMs.value = s.offsetMs
        bandLow.value = s.bandLow
        bandMid.value = s.bandMid
        bandHigh.value = s.bandHigh
        effectLow.value = s.effectLow
        effectMid.value = s.effectMid
        effectHigh.value = s.effectHigh
        bypassSystemScaling.value = s.bypassSystemScaling
        backendChoice.value = s.backendChoice
        theme.value = s.theme
        dynamicColor.value = s.dynamicColor
        showBand.value = s.showBand
        bandPalette.value = s.bandPalette
        enhancedAnimations.value = s.enhancedAnimations
        heroMilestones.value = s.heroMilestones
        character.value = s.character
        instrument.value = s.instrument
        magicPreset.value = s.magicPreset
    }

    fun resetToDefaults() = applySnapshot(DEFAULTS)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode.value = enumOr(p.getString("mode", null), Mode.entries, DEFAULTS.mode)
        source.value = enumOr(p.getString("source", null), SourceKind.entries, DEFAULTS.source)
        intensity.value = p.getFloat("intensity", DEFAULTS.intensity)
        sensitivity.value = p.getFloat("sensitivity", DEFAULTS.sensitivity)
        offsetMs.value = p.getInt("offsetMs", DEFAULTS.offsetMs)
        bandLow.value = p.getBoolean("bandLow", DEFAULTS.bandLow)
        bandMid.value = p.getBoolean("bandMid", DEFAULTS.bandMid)
        bandHigh.value = p.getBoolean("bandHigh", DEFAULTS.bandHigh)
        effectLow.value = p.getInt("effectLow", DEFAULTS.effectLow)
        effectMid.value = p.getInt("effectMid", DEFAULTS.effectMid)
        effectHigh.value = p.getInt("effectHigh", DEFAULTS.effectHigh)
        bypassSystemScaling.value =
            p.getBoolean("bypassSystemScaling", DEFAULTS.bypassSystemScaling)
        backendChoice.value =
            enumOr(p.getString("backendChoice", null), BackendChoice.entries, DEFAULTS.backendChoice)
        theme.value = enumOr(p.getString("theme", null), AppTheme.entries, DEFAULTS.theme)
        magicPreset.value = p.getString("magicPreset", DEFAULTS.magicPreset) ?: DEFAULTS.magicPreset
        dynamicColor.value = p.getBoolean("dynamicColor", DEFAULTS.dynamicColor)
        showBand.value = p.getBoolean("showBand", DEFAULTS.showBand)
        bandPalette.value = enumOr(p.getString("bandPalette", null), BandPalette.entries, DEFAULTS.bandPalette)
        enhancedAnimations.value = p.getBoolean("enhancedAnimations", DEFAULTS.enhancedAnimations)
        heroMilestones.value = p.getBoolean("heroMilestones", DEFAULTS.heroMilestones)
        character.value = enumOr(p.getString("character", null), PixelCharacter.entries, DEFAULTS.character)
        instrument.value = enumOr(p.getString("instrument", null), PixelInstrument.entries, DEFAULTS.instrument)
    }

    fun save(context: Context) {
        val s = snapshot()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("mode", s.mode.name)
            putString("source", s.source.name)
            putFloat("intensity", s.intensity)
            putFloat("sensitivity", s.sensitivity)
            putInt("offsetMs", s.offsetMs)
            putBoolean("bandLow", s.bandLow)
            putBoolean("bandMid", s.bandMid)
            putBoolean("bandHigh", s.bandHigh)
            putInt("effectLow", s.effectLow)
            putInt("effectMid", s.effectMid)
            putInt("effectHigh", s.effectHigh)
            putBoolean("bypassSystemScaling", s.bypassSystemScaling)
            putString("backendChoice", s.backendChoice.name)
            putString("theme", s.theme.name)
            putString("magicPreset", s.magicPreset)
            putBoolean("dynamicColor", s.dynamicColor)
            putBoolean("showBand", s.showBand)
            putString("bandPalette", s.bandPalette.name)
            putBoolean("enhancedAnimations", s.enhancedAnimations)
            putBoolean("heroMilestones", s.heroMilestones)
            putString("character", s.character.name)
            putString("instrument", s.instrument.name)
        }.apply()
    }

    private fun <T : Enum<T>> enumOr(raw: String?, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback
}
