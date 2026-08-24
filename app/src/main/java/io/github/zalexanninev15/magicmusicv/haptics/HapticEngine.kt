package io.github.zalexanninev15.magicmusicv.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.zalexanninev15.magicmusicv.core.Band
import kotlin.math.pow

/**
 * What the device's actuator can actually do.
 *
 * FULL is an X-axis linear motor with the whole primitive set — OnePlus flagships, realme
 * GT/Pro, most OPPO Find/Reno. PARTIAL is a Z-axis LRA that reports some primitives but
 * not THUD. NONE is a rotary ERM, which physically cannot produce a tap: it spins up and
 * coasts down over tens of milliseconds. That is common on realme C/Narzo and on OnePlus
 * Nord N models, and the app says so rather than pretending.
 */
enum class HapticTier { FULL, PARTIAL, NONE }

/** A single tap: which band fired it, how hard, and how far in the future. */
data class Tap(val band: Band, val strength: Float, val delayMs: Int = 0)

/**
 * Turns taps into vibrator calls.
 *
 * Everything here is built on [VibrationEffect.Composition] primitives rather than
 * waveforms. A primitive is a factory-tuned impulse for the device's own actuator:
 * it starts and — critically — *stops* the LRA quickly, so it lands as a knock instead
 * of the smeared buzz you get from createOneShot on an untuned duration. On hardware
 * like the OnePlus 15 that is the entire difference the app is trying to deliver.
 *
 * Scales come from the onset strength, so a kick and a hi-hat do not feel the same.
 */
class HapticEngine(context: Context) {

    private val vibrator: Vibrator =
        context.getSystemService(VibratorManager::class.java).defaultVibrator

    private val supported: BooleanArray = vibrator.arePrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )

    val hasClick get() = supported.getOrElse(0) { false }
    val hasTick get() = supported.getOrElse(1) { false }
    val hasLowTick get() = supported.getOrElse(2) { false }
    val hasThud get() = supported.getOrElse(3) { false }

    /** True when the device can do real primitives; false means we run the fallback path. */
    val primitivesAvailable: Boolean = hasClick || hasTick || hasThud

    val tier: HapticTier = when {
        hasClick && hasTick && hasThud -> HapticTier.FULL
        hasClick || hasTick -> HapticTier.PARTIAL
        else -> HapticTier.NONE
    }

    val hasAmplitudeControl: Boolean = vibrator.hasAmplitudeControl()

    /** Master intensity, 0..1, exposed to the UI. */
    @Volatile
    var intensity: Float = 0.8f

    /**
     * USAGE_MEDIA, not USAGE_TOUCH: this is part of media playback, so it should follow
     * the media path and keep working while notifications are silenced. USAGE_TOUCH is
     * suppressed by the system whenever touch feedback is off in settings, which would
     * kill the app for anyone who dislikes keyboard haptics.
     */
    private val vibrationAttrs: VibrationAttributes = VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_MEDIA)
        .build()

    private val legacyAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun primitiveFor(band: Band, strength: Float): Int = when (band) {
        // THUD has the longest, lowest-frequency body — the closest thing to a kick.
        Band.LOW -> when {
            hasThud -> VibrationEffect.Composition.PRIMITIVE_THUD
            hasClick -> VibrationEffect.Composition.PRIMITIVE_CLICK
            else -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        Band.MID -> if (hasClick) VibrationEffect.Composition.PRIMITIVE_CLICK
        else VibrationEffect.Composition.PRIMITIVE_TICK
        // Hats get LOW_TICK when available: a plain TICK at low scale is still too
        // present and turns dense hi-hat patterns into a continuous itch.
        Band.HIGH -> when {
            strength < 0.5f && hasLowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            hasTick -> VibrationEffect.Composition.PRIMITIVE_TICK
            else -> VibrationEffect.Composition.PRIMITIVE_CLICK
        }
    }

    private fun scaleFor(band: Band, strength: Float): Float {
        val bandGain = when (band) {
            Band.LOW -> 1.0f
            Band.MID -> 0.8f
            Band.HIGH -> 0.55f
        }
        // 0.6 exponent: perceived haptic magnitude is compressive, so a linear map wastes
        // most of the usable range at the top. The 0.12 floor exists because below it the
        // actuator barely breaks static friction and the tap is simply dropped.
        val s = strength.coerceIn(0f, 1f).pow(0.6f) * bandGain * intensity
        return s.coerceIn(0.12f, 1.0f)
    }

    /**
     * Fires a batch of taps as ONE composition.
     *
     * The delays are handed to the vibrator service instead of being posted on an app
     * thread. App-side timers inherit scheduler jitter measured in whole milliseconds,
     * which is enough to smear a hi-hat pattern; composition delays are applied down in
     * the vibrator HAL and hold their spacing even when the UI thread is busy.
     */
    fun play(taps: List<Tap>) {
        if (taps.isEmpty()) return
        if (!primitivesAvailable) {
            playFallback(taps.first())
            return
        }
        try {
            var composition = VibrationEffect.startComposition()
            var count = 0
            for (t in taps) {
                if (count >= MAX_PRIMITIVES) break
                composition = composition.addPrimitive(
                    primitiveFor(t.band, t.strength),
                    scaleFor(t.band, t.strength),
                    t.delayMs.coerceIn(0, 5_000),
                )
                count++
            }
            if (count == 0) return
            vibrate(composition.compose())
        } catch (_: IllegalArgumentException) {
            // A device can reject a composition that is longer than its own limit.
            playFallback(taps.first())
        }
    }

    fun play(tap: Tap) = play(listOf(tap))

    private fun playFallback(tap: Tap) {
        val ms = when (tap.band) {
            Band.LOW -> 18L
            Band.MID -> 12L
            Band.HIGH -> 8L
        }
        val effect = if (hasAmplitudeControl) {
            val amp = (scaleFor(tap.band, tap.strength) * 255f).toInt().coerceIn(1, 255)
            VibrationEffect.createOneShot(ms, amp)
        } else {
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrate(effect)
    }

    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, vibrationAttrs)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, legacyAttrs)
        }
    }

    fun cancel() = vibrator.cancel()

    /** Short demo used by the UI so the user can feel each band without music. */
    fun preview() = play(
        listOf(
            Tap(Band.LOW, 1.0f, 0),
            Tap(Band.HIGH, 0.4f, 150),
            Tap(Band.MID, 0.7f, 150),
            Tap(Band.HIGH, 0.4f, 150),
            Tap(Band.LOW, 1.0f, 150),
        )
    )

    private companion object {
        // Vibrator.getCompositionSizeMax() is not public API, and the platform minimum
        // guarantee is small, so the batch is capped conservatively and the compose()
        // call is wrapped in a try/catch above.
        const val MAX_PRIMITIVES = 8
    }
}
