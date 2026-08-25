package io.github.zalexanninev15.magicmusicv.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.zalexanninev15.magicmusicv.core.Band
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * What this device's actuator can actually be driven with.
 *
 * VENDOR_ONLY is the OPLUS case and it is not a downgrade: OnePlus/OPPO never implemented
 * the AOSP compose HAL, so `arePrimitivesSupported()` is false across the board even on a
 * flagship X-axis LRA. Their effect library lives behind LinearmotorVibrator instead.
 * Treating that as "no primitives, must be a rotor" is wrong — the hardware is fine, the
 * AOSP path simply is not wired up.
 */
enum class HapticTier { FULL, PARTIAL, VENDOR_ONLY, NONE }

enum class Backend { AOSP, OPLUS, OPLUS_MMV }

/**
 * Single source of truth for turning a choice into a backend, shared by the engine, the
 * service and the UI so they can never disagree about which path is running.
 */
fun resolveBackend(choice: BackendChoice, autoBackend: Backend, oplusAvailable: Boolean): Backend =
    when (choice) {
        BackendChoice.AUTO -> autoBackend
        BackendChoice.AOSP -> Backend.AOSP
        BackendChoice.OPLUS -> if (oplusAvailable) Backend.OPLUS else Backend.AOSP
        BackendChoice.OPLUS_MMV -> if (oplusAvailable) Backend.OPLUS_MMV else Backend.AOSP
    }

/**
 * What the user picked, as opposed to what actually gets used.
 *
 * AUTO resolves from probed capability only. Deliberately not from [android.os.Build]
 * strings: manufacturer and model are trivially rewritten on a rooted phone, and a device
 * reporting itself as a Galaxy while running OxygenOS would pick the wrong path every
 * time. The actuator's own answers cannot be spoofed by a build.prop edit.
 */
enum class BackendChoice { AUTO, AOSP, OPLUS, OPLUS_MMV }

/** A single tap: which band fired it, how hard, and how far in the future. */
data class Tap(
    val band: Band,
    val strength: Float,
    val delayMs: Int = 0,
    /** True when this tap came from the predicted beat grid rather than a detected onset. */
    val accent: Boolean = false,
)

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

    val primitivesAvailable: Boolean = hasClick || hasTick || hasThud
    val hasAmplitudeControl: Boolean = vibrator.hasAmplitudeControl()

    /** How many of the eight AOSP primitives this actuator reports, for the setup screen. */
    val primitiveCount: Int = runCatching {
        vibrator.arePrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_SPIN,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        ).count { it }
    }.getOrDefault(0)

    val tier: HapticTier = when {
        hasClick && hasTick && hasThud -> HapticTier.FULL
        hasClick || hasTick -> HapticTier.PARTIAL
        OplusHaptics.available -> HapticTier.VENDOR_ONLY
        else -> HapticTier.NONE
    }

    /**
     * AOSP stays the default wherever it works. The vendor path is a fallback for devices
     * that never wired up the compose HAL, not an upgrade — it loses HAL-side timing.
     */
    val autoBackend: Backend =
        if (!primitivesAvailable && OplusHaptics.available) Backend.OPLUS_MMV else Backend.AOSP

    /** Why [autoBackend] came out the way it did, shown on the setup card. */
    val autoReason: String = when {
        primitivesAvailable -> "$primitiveCount AOSP primitives supported"
        OplusHaptics.available -> "no AOSP primitives; vendor engine found, using the full MMV voicing"
        else -> "no primitives and no vendor engine; short one-shots only"
    }

    // Declared after autoBackend on purpose: Kotlin runs property initialisers in source
    // order, so initialising this above would read autoBackend before it exists.
    /** The path actually in use. Set via [applyChoice]. */
    @Volatile
    var backend: Backend = autoBackend
        private set

    fun resolve(choice: BackendChoice): Backend =
        resolveBackend(choice, autoBackend, OplusHaptics.available)

    fun applyChoice(choice: BackendChoice) {
        backend = resolve(choice)
    }

    @Volatile var intensity: Float = 0.8f

    /** OPLUS effect id per band, indexed by [Band.ordinal]. */
    @Volatile
    var oplusEffects: IntArray = intArrayOf(2, 1, 0)

    /**
     * Active MagicFeedback preset id, or null for the plain graded taps.
     *
     * When set it overrides [oplusEffects] and brings its own per-band rate limits: the
     * textured effects are several times longer than a short tap, and at onset rate they
     * overlap into exactly the continuous buzz this app exists to avoid.
     */
    @Volatile
    var magicPresetId: String? = null

    /** Detach OPLUS effects from the system vibration-intensity slider. */
    @Volatile
    var bypassSystemScaling: Boolean = false

    /**
     * Scheduling thread for the OPLUS path only.
     *
     * The AOSP path hands delays to the vibrator service inside one composition and the HAL
     * keeps the spacing. OPLUS has no such call — one effect, fired now — so predicted beats
     * have to be timed here. A dedicated max-priority executor is the closest available
     * substitute; it costs a millisecond or two of jitter, which is the real price of the
     * vendor backend and is worth knowing about before blaming the beat tracker.
     */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mmv-haptic").apply { priority = Thread.MAX_PRIORITY }
        }

    private val vibrationAttrs: VibrationAttributes = VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_MEDIA)
        .build()

    private val legacyAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ---------------- public API ----------------

    fun play(taps: List<Tap>) {
        if (taps.isEmpty()) return
        when (backend) {
            Backend.OPLUS, Backend.OPLUS_MMV -> playOplus(taps)
            Backend.AOSP -> playAosp(taps)
        }
    }

    fun play(tap: Tap) = play(listOf(tap))

    fun cancel() {
        runCatching { vibrator.cancel() }
        OplusHaptics.cancel()
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }

    /** Fires one raw OPLUS effect id — used by the effect browser in the UI. */
    fun previewOplus(effectId: Int, strength: Int) =
        OplusHaptics.vibrate(effectId, strength, bypassSystemScaling)

    /** Demo for the MMV voicing: soft, hard, accent, so the three voices are audible. */
    fun previewMmv() = play(
        listOf(
            Tap(Band.LOW, 1.0f, 0, accent = true),
            Tap(Band.HIGH, 0.3f, 140),
            Tap(Band.MID, 0.9f, 140),
            Tap(Band.HIGH, 0.8f, 140),
            Tap(Band.LOW, 0.5f, 140),
        )
    )

    fun preview() = play(
        listOf(
            Tap(Band.LOW, 1.0f, 0),
            Tap(Band.HIGH, 0.4f, 150),
            Tap(Band.MID, 0.7f, 150),
            Tap(Band.HIGH, 0.4f, 150),
            Tap(Band.LOW, 1.0f, 150),
        )
    )

    // ---------------- OPLUS path ----------------

    /** Last fire time per band, for the magic-preset rate limit. Audio thread only. */
    private val lastFireMs = longArrayOf(0, 0, 0)

    private fun playOplus(taps: List<Tap>) {
        val mmv = backend == Backend.OPLUS_MMV
        val preset = MagicFeedback.byId(magicPresetId)
        val magicIds = preset?.let { MagicFeedback.resolve(it) }
        val gaps = when {
            preset != null -> intArrayOf(preset.gapLow, preset.gapMid, preset.gapHigh)
            mmv -> MmvVoicing.gaps
            else -> null
        }
        val now = System.currentTimeMillis()

        var cumulative = 0
        for (t in taps) {
            cumulative += t.delayMs.coerceIn(0, 5_000)
            val band = t.band.ordinal

            if (gaps != null) {
                // Gate on the moment the tap will actually fire, not on now — a scheduled
                // beat 200 ms out must not be dropped because of a tap that just played.
                val at = now + cumulative
                if (at - lastFireMs[band] < gaps[band]) continue
                lastFireMs[band] = at
            }

            val level = scaleFor(t.band, t.strength)
            val id: Int
            val strength: Int
            val mmvVoice = if (mmv && magicIds == null) {
                MmvVoicing.voice(t.band, level, t.accent)
            } else {
                null
            }
            if (mmvVoice != null) {
                id = mmvVoice.first
                strength = mmvVoice.second
            } else {
                val table = magicIds ?: oplusEffects
                id = table.getOrElse(band) { table.firstOrNull() ?: 0 }
                strength = quantise(level)
            }
            if (cumulative <= 0) {
                OplusHaptics.vibrate(id, strength, bypassSystemScaling)
            } else {
                scheduler.schedule(
                    { OplusHaptics.vibrate(id, strength, bypassSystemScaling) },
                    cumulative.toLong(), TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    /**
     * Fires one band of a preset on its own, at full strength.
     *
     * The per-band test exists because a preset that stays silent gives no clue which of its
     * three effects the ROM refused; firing them individually narrows it in one tap.
     */
    fun previewMagicBand(presetId: String, band: Int): Boolean {
        val preset = MagicFeedback.byId(presetId) ?: return false
        val ids = MagicFeedback.resolve(preset) ?: return false
        val id = ids.getOrNull(band) ?: return false
        if (OplusHaptics.vibrate(id, OplusHaptics.strengthStrong, bypassSystemScaling)) return true
        // Same self-repair as previewMagic: the failure is now recorded, so re-resolve.
        val retry = MagicFeedback.resolve(preset)?.getOrNull(band) ?: return false
        if (retry == id) return false
        return OplusHaptics.vibrate(retry, OplusHaptics.strengthStrong, bypassSystemScaling)
    }

    /** Fires a short demo of a preset: kick, hat, snare, hat, kick. */
    fun previewMagic(presetId: String): Boolean {
        val preset = MagicFeedback.byId(presetId) ?: return false
        val ids = MagicFeedback.resolve(preset) ?: return false
        val steps = listOf(
            Triple(ids[0], OplusHaptics.strengthStrong, 0),
            Triple(ids[2], OplusHaptics.strengthLight, preset.gapHigh + 120),
            Triple(ids[1], OplusHaptics.strengthMedium, preset.gapMid + 120),
            Triple(ids[2], OplusHaptics.strengthLight, preset.gapHigh + 120),
            Triple(ids[0], OplusHaptics.strengthStrong, preset.gapLow + 120),
        )
        // If the leading effect is refused, OplusHaptics has now recorded it and re-resolving
        // picks the preset's next candidate — so the demo repairs itself within one tap
        // instead of reporting a dead preset.
        if (!OplusHaptics.vibrate(ids[0], OplusHaptics.strengthStrong, bypassSystemScaling)) {
            val retry = MagicFeedback.resolve(preset) ?: return false
            if (!retry.contentEquals(ids)) return previewMagic(presetId)
            return false
        }

        var at = 0L
        var firstOk = true
        for ((id, strength, delay) in steps) {
            at += delay
            if (at == 0L) {
                // Already fired above as the probe for this preset.
                continue
            } else {
                scheduler.schedule(
                    { OplusHaptics.vibrate(id, strength, bypassSystemScaling) },
                    at, TimeUnit.MILLISECONDS,
                )
            }
        }
        return firstOk
    }

    /**
     * OPLUS exposes three discrete strengths, not a float scale, so the continuous onset
     * strength is quantised. Band gain and the master intensity fold into the same number
     * before quantising — otherwise a hi-hat and a kick both round to STRONG and every tap
     * feels identical, which is the failure mode this whole app exists to avoid.
     */
    private fun quantise(s: Float): Int {
        return when {
            s >= 0.66f -> OplusHaptics.strengthStrong
            s >= 0.36f -> OplusHaptics.strengthMedium
            else -> OplusHaptics.strengthLight
        }
    }

    // ---------------- AOSP path ----------------

    private fun primitiveFor(band: Band, strength: Float): Int = when (band) {
        Band.LOW -> when {
            hasThud -> VibrationEffect.Composition.PRIMITIVE_THUD
            hasClick -> VibrationEffect.Composition.PRIMITIVE_CLICK
            else -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        Band.MID -> if (hasClick) VibrationEffect.Composition.PRIMITIVE_CLICK
        else VibrationEffect.Composition.PRIMITIVE_TICK
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
        // the top of the range. 0.12 floor because below it the actuator barely moves.
        val s = strength.coerceIn(0f, 1f).pow(0.6f) * bandGain * intensity
        return s.coerceIn(0.12f, 1.0f)
    }

    private fun playAosp(taps: List<Tap>) {
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
            playFallback(taps.first())
        }
    }

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

    private companion object {
        const val MAX_PRIMITIVES = 8
    }
}
