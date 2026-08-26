package io.github.zalexanninev15.magicmusicv.haptics

import io.github.zalexanninev15.magicmusicv.core.Band

/**
 * "O-Haptics by MMV" — an experimental layer *on top of* the O-Haptics backend.
 *
 * The first version replaced the per-band effect with a voice chosen by hit strength. That
 * was a mistake: its soft and mid voices were quieter than whatever the user had picked in
 * the effect lab, so most of the music got weaker and only hard percussive hits registered
 * at all.
 *
 * This version never substitutes anything. The base tap is exactly what plain O-Haptics
 * would fire — same effect, same strength — and MMV only adds a second, shorter effect a
 * few milliseconds behind it on the events worth marking: hard hits and predicted beats.
 * Two impulses close together read as one event with a tail, which is what gives the extra
 * body. Being purely additive, it cannot be weaker than the backend it sits on.
 *
 * Still experimental, and deliberately not what Auto picks.
 */
object MmvVoicing {

    /**
     * Gap between the base tap and its garnish.
     *
     * Below roughly 20 ms the actuator is still moving and the second impulse is swallowed;
     * beyond about 40 ms the two stop fusing and you feel a flam instead of one richer hit.
     */
    const val LAYER_DELAY_MS = 28

    /** Level above which an ordinary onset earns a garnish. */
    private const val HARD = 0.66f

    /** Deeper body behind a strong kick. */
    private val lowGarnish = listOf(
        "EFFECT_PUBG_SHORT_GUN",
        "EFFECT_CUSTOMIZED_CONFLICT",
        "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
    )

    /** Marks the predicted downbeat as a different kind of event, not just a louder one. */
    private val beatGarnish = listOf(
        "EFFECT_CUSTOMIZED_SPREAD_OUT",
        "EFFECT_OTHER_WATERRIPPLE",
        "EFFECT_PUBG_SHORT_GUN",
        "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
    )

    /** Snap on a hard snare. */
    private val midGarnish = listOf(
        "EFFECT_OTHER_ELASTICITY",
        "EFFECT_RAZER_CLICKY_PRESS",
        "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
    )

    /** Deliberately light — dense hi-hats garnished hard turn into a rattle. */
    private val highGarnish = listOf(
        "EFFECT_OTHER_KEYBOARD_WEAK",
        "EFFECT_WEAK_EMULATION_KEYBOARD_UP",
        "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
    )

    /**
     * Extra effect to layer behind the base tap, as (effect id, strength), or null when this
     * tap should be left alone.
     *
     * [level] is the already-scaled magnitude with band gain and master intensity folded in.
     */
    fun garnish(band: Band, level: Float, accent: Boolean): Pair<Int, Int>? {
        if (accent) {
            val id = OplusHaptics.pick(beatGarnish) ?: return null
            return id to OplusHaptics.strengthStrong
        }
        if (level < HARD) return null
        val names = when (band) {
            Band.LOW -> lowGarnish
            Band.MID -> midGarnish
            Band.HIGH -> highGarnish
        }
        val id = OplusHaptics.pick(names) ?: return null
        val strength = when (band) {
            Band.HIGH -> OplusHaptics.strengthLight
            Band.MID -> OplusHaptics.strengthMedium
            Band.LOW -> OplusHaptics.strengthStrong
        }
        return id to strength
    }

    /** Human-readable summary for the setup card. */
    fun describe(): List<String> {
        fun n(names: List<String>): String =
            names.firstOrNull { OplusHaptics.effectConstants.containsKey(it) }
                ?.removePrefix("EFFECT_") ?: "unavailable"
        return listOf(
            "base    your O-Haptics effects, unchanged",
            "+beat   ${n(beatGarnish)}",
            "+low    ${n(lowGarnish)}",
            "+mid    ${n(midGarnish)}",
            "+high   ${n(highGarnish)}",
        )
    }
}
