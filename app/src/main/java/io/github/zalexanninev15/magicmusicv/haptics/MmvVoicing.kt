package io.github.zalexanninev15.magicmusicv.haptics

import io.github.zalexanninev15.magicmusicv.core.Band

/**
 * "O-Haptics by MMV" — the whole vendor library used together instead of one effect per band.
 *
 * Plain O-Haptics fires the same effect for a band no matter how hard the hit was, and only
 * varies OPLUS's three strength steps. That flattens everything: a ghost note and a downbeat
 * are the same event at different volumes.
 *
 * Here each band has three *voices* chosen by how hard the onset was, plus a separate voice
 * for taps that came from the predicted beat grid. A quiet hi-hat is a faint tick, a loud one
 * is a mechanical key press, and the downbeat is a different effect entirely — so the phone
 * plays a kit rather than a single pad.
 *
 * Every list is candidates in preference order. Effects are looked up by constant name and
 * ids the ROM has already refused are skipped, so an unavailable effect costs the voice its
 * first choice and nothing more. The last entry of each list is one of the three plain graded
 * taps, which are the most widely supported effects on any OPLUS device.
 */
object MmvVoicing {

    /** effect-name candidates, and which of OPLUS's three strength steps to fire it at. */
    private data class Voice(val names: List<String>, val tier: Int)

    private val lowSoft = Voice(
        listOf("EFFECT_WEAK_SHORT_VIBRATE_ONCE", "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE"), 1
    )
    private val lowMid = Voice(
        listOf("EFFECT_MODERATE_SHORT_VIBRATE_ONCE", "EFFECT_WEAK_SHORT_VIBRATE_ONCE"), 2
    )
    private val lowHard = Voice(
        listOf(
            "EFFECT_PUBG_SHORT_GUN",
            "EFFECT_CUSTOMIZED_CONFLICT",
            "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
        ), 2
    )
    private val lowAccent = Voice(
        listOf(
            "EFFECT_CUSTOMIZED_SPREAD_OUT",
            "EFFECT_OTHER_WATERRIPPLE",
            "EFFECT_PUBG_SHORT_GUN",
            "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
        ), 2
    )

    private val midSoft = Voice(
        listOf("EFFECT_WEAKEST_SHORT_VIBRATE_ONCE", "EFFECT_WEAK_SHORT_VIBRATE_ONCE"), 1
    )
    private val midMid = Voice(
        listOf(
            "EFFECT_OTHER_KEYBOARD_STRONG",
            "EFFECT_RAZER_LINEAR_PRESS",
            "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
        ), 1
    )
    private val midHard = Voice(
        listOf(
            "EFFECT_OTHER_ELASTICITY",
            "EFFECT_RAZER_CLICKY_PRESS",
            "EFFECT_CUSTOMIZED_CONVERGE",
            "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
        ), 2
    )

    private val highSoft = Voice(
        listOf("EFFECT_WEAKEST_SHORT_VIBRATE_ONCE"), 0
    )
    private val highMid = Voice(
        listOf(
            "EFFECT_OTHER_KEYBOARD_WEAK",
            "EFFECT_WEAK_EMULATION_KEYBOARD_UP",
            "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
        ), 0
    )
    private val highHard = Voice(
        listOf(
            "EFFECT_RAZER_CLICKY_PRESS",
            "EFFECT_OTHER_KEYBOARD_STRONG",
            "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
        ), 1
    )

    /**
     * Per-band minimum gap in ms. Tighter than the Magic presets because these voices are
     * short effects, but not zero — the hard voices are textured and will overlap.
     */
    val gaps = intArrayOf(70, 55, 40)

    /**
     * Picks effect id and strength for one tap.
     *
     * [level] is the already-scaled 0..1 magnitude (band gain and master intensity folded in),
     * so lowering intensity moves taps down to softer voices rather than only quieting them.
     */
    fun voice(band: Band, level: Float, accent: Boolean): Pair<Int, Int>? {
        val v = when (band) {
            Band.LOW -> when {
                accent -> lowAccent
                level >= 0.66f -> lowHard
                level >= 0.36f -> lowMid
                else -> lowSoft
            }
            Band.MID -> when {
                level >= 0.66f -> midHard
                level >= 0.36f -> midMid
                else -> midSoft
            }
            Band.HIGH -> when {
                level >= 0.66f -> highHard
                level >= 0.36f -> highMid
                else -> highSoft
            }
        }
        val id = OplusHaptics.pick(v.names) ?: return null
        return id to OplusHaptics.strengthOf(v.tier)
    }

    /** Human-readable summary for the setup card. */
    fun describe(): List<String> {
        fun n(v: Voice): String =
            v.names.firstOrNull { OplusHaptics.effectConstants.containsKey(it) }
                ?.removePrefix("EFFECT_") ?: "unavailable"
        return listOf(
            "low   ${n(lowSoft)} / ${n(lowMid)} / ${n(lowHard)}",
            "beat  ${n(lowAccent)}",
            "mid   ${n(midSoft)} / ${n(midMid)} / ${n(midHard)}",
            "high  ${n(highSoft)} / ${n(highMid)} / ${n(highHard)}",
        )
    }
}
