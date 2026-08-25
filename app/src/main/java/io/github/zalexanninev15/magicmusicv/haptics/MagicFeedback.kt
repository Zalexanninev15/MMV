package io.github.zalexanninev15.magicmusicv.haptics

import io.github.zalexanninev15.magicmusicv.oem.OemSupport
import io.github.zalexanninev15.magicmusicv.oem.Vendor

/**
 * One curated mapping of the three bands onto OPLUS's *textured* effects.
 *
 * The default mapping uses the three graded short taps, which are clean and precise and
 * feel like nothing in particular. OPLUS also ships effects that simulate physical events —
 * a strike, a detent stepping over, something bursting, a surface still rippling after
 * impact. Those are what the O-Haptics demo in OxygenOS settings shows off, and they are
 * what makes the motor feel like an object rather than a buzzer.
 *
 * Effects are referenced **by constant name, not by id.** Ids are not stable across ColorOS
 * releases — the same integer means different things on different ROMs — so each band lists
 * candidates in preference order and the first one this device actually exposes wins. The
 * last entry in every list is one of the plain graded taps, so a preset always resolves to
 * something rather than failing.
 */
data class MagicPreset(
    val id: String,
    val title: String,
    val blurb: String,
    val low: List<String>,
    val mid: List<String>,
    val high: List<String>,
    /** Minimum gap per band in ms. Textured effects are long; see [MagicFeedback]. */
    val gapLow: Int,
    val gapMid: Int,
    val gapHigh: Int,
)

object MagicFeedback {

    val presets: List<MagicPreset> = listOf(
        MagicPreset(
            id = "impact",
            title = "Hammer & ball",
            blurb = "A heavy strike through the back of the phone, then the weight rolling off it.",
            low = listOf(
                "EFFECT_PUBG_SHORT_GUN",
                "EFFECT_GAME_CUSTOM_VIBRATION_STRONG_ATTACK",
                "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
            ),
            mid = listOf(
                "EFFECT_OTHER_ELASTICITY",
                "EFFECT_GAME_CUSTOM_VIBRATION_ATTACK",
                "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
            ),
            high = listOf(
                "EFFECT_OTHER_WATERRIPPLE",
                "EFFECT_CUSTOMIZED_WEAK_GRANULAR",
                "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
            ),
            gapLow = 150, gapMid = 110, gapHigh = 75,
        ),
        MagicPreset(
            id = "detent",
            title = "Detent switch",
            blurb = "Every hit is a mechanical click, like a rotary switch stepping through positions.",
            low = listOf(
                "EFFECT_RAZER_CLICKY_PRESS",
                "EFFECT_EMULATION_KEYBOARD_DOWN",
                "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
            ),
            mid = listOf(
                "EFFECT_OTHER_KEYBOARD_STRONG",
                "EFFECT_RAZER_LINEAR_PRESS",
                "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
            ),
            high = listOf(
                "EFFECT_WEAK_EMULATION_KEYBOARD_UP",
                "EFFECT_OTHER_KEYBOARD_WEAK",
                "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
            ),
            // The tightest preset: key-press effects are short by design, so this one
            // survives dense material where the others turn to mush.
            gapLow = 90, gapMid = 70, gapHigh = 50,
        ),
        MagicPreset(
            id = "burst",
            title = "Burst",
            blurb = "A balloon going: sharp break, then the shell spreading away from it.",
            low = listOf(
                "EFFECT_CUSTOMIZED_CONFLICT",
                "EFFECT_PUBG_RIFLE",
                "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
            ),
            mid = listOf(
                "EFFECT_CUSTOMIZED_SPREAD_OUT",
                "EFFECT_OTHER_BIG_SCALE",
                "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
            ),
            high = listOf(
                "EFFECT_CUSTOMIZED_CONVERGE",
                "EFFECT_OTHER_SMALL_SCALE",
                "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
            ),
            gapLow = 190, gapMid = 140, gapHigh = 95,
        ),
        MagicPreset(
            id = "ripple",
            title = "Ripple",
            blurb = "Something dropped into water. The surface keeps moving after the hit lands.",
            low = listOf(
                "EFFECT_GAME_CUSTOM_VIBRATION_MICROWAVE_RIPPLES",
                "EFFECT_OTHER_WATERRIPPLE",
                "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
            ),
            mid = listOf(
                "EFFECT_OTHER_WATERRIPPLE",
                "EFFECT_CUSTOMIZED_BREATHE_SPREAD_OUT",
                "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
            ),
            high = listOf(
                "EFFECT_CUSTOMIZED_WEAK_GRANULAR",
                "EFFECT_OTHER_SMALL_SCALE",
                "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
            ),
            // Slowest of the four. Ripples are long; anything tighter overlaps itself.
            gapLow = 230, gapMid = 170, gapHigh = 110,
        ),
        MagicPreset(
            id = "grain",
            title = "Grain",
            blurb = "Coarse texture, like dragging a fingernail across a ridged surface.",
            low = listOf(
                "EFFECT_CUSTOMIZED_STRONG_GRANULAR",
                "EFFECT_GAME_CUSTOM_VIBRATION_STEPS",
                "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
            ),
            mid = listOf(
                "EFFECT_CUSTOMIZED_WEAK_GRANULAR",
                "EFFECT_GAME_CUSTOM_VIBRATION_CRISP",
                "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
            ),
            high = listOf(
                "EFFECT_OTHER_KEYBOARD_WEAK",
                "EFFECT_GAME_CUSTOM_VIBRATION_WEAK",
                "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
            ),
            gapLow = 130, gapMid = 95, gapHigh = 65,
        ),
    )

    fun byId(id: String?): MagicPreset? = presets.firstOrNull { it.id == id }

    /**
     * Resolves a preset to concrete effect ids for [low, mid, high], or null if the vendor
     * engine is not up.
     */
    fun resolve(preset: MagicPreset): IntArray? {
        if (!OplusHaptics.available) return null
        val c = OplusHaptics.effectConstants
        fun pick(names: List<String>): Int? = names.firstNotNullOfOrNull { c[it] }
        val lo = pick(preset.low) ?: return null
        val mi = pick(preset.mid) ?: lo
        val hi = pick(preset.high) ?: mi
        return intArrayOf(lo, mi, hi)
    }

    /**
     * Which named effect each band actually landed on, with its id, for showing in the UI.
     * The id is included so a silent preset can be cross-checked in the effect lab.
     */
    fun explain(preset: MagicPreset): List<String> {
        val c = OplusHaptics.effectConstants
        fun name(names: List<String>): String {
            val n = names.firstOrNull { c.containsKey(it) } ?: return "unavailable"
            return "${n.removePrefix("EFFECT_")} ${c[n]}"
        }
        return listOf(name(preset.low), name(preset.mid), name(preset.high))
    }

    /**
     * Gated to OnePlus deliberately.
     *
     * The effect library is shared across the whole OPLUS family, so realme and OPPO would
     * very likely work too — but these particular textures were picked and named against a
     * OnePlus 15's constant dump, and nothing has been felt on any other device. Widening
     * this is one line, once someone has actually held a realme and confirmed.
     *
     * The vendor check is capability-based rather than a Build string comparison, because a
     * rooted phone can claim to be anything.
     */
    val available: Boolean
        get() = OplusHaptics.available &&
            OemSupport.vendor == Vendor.ONEPLUS &&
            presets.any { resolve(it) != null }
}
