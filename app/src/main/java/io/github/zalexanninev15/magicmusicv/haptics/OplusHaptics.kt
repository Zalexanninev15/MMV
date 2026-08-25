package io.github.zalexanninev15.magicmusicv.haptics

import android.content.Context
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * OPLUS `LinearmotorVibrator` backend.
 *
 * On a OnePlus 15 (OxygenOS 16) this is not an optional extra — it is the only working
 * path. `Vibrator.arePrimitivesSupported()` returns false for all eight AOSP primitives
 * and `areEnvelopeEffectsSupported()` is false, because OPLUS never implemented the AOSP
 * compose HAL. Their whole haptic library lives behind `com.oplus.os.WaveformEffect`
 * instead, keyed by a few hundred integer effect ids.
 *
 * What that costs: an OPLUS effect fires immediately and there is no equivalent of
 * `VibrationEffect.Composition`'s per-primitive delay, so scheduled taps have to be timed
 * in-process. See [HapticEngine] for how that is handled.
 */
object OplusHaptics {

    private const val TAG = "OplusHaptics"

    private var service: Any? = null
    private var serviceName: String? = null
    private var builderClass: Class<*>? = null
    private var effectClass: Class<*>? = null

    private var setEffectType: Method? = null
    private var setEffectStrength: Method? = null
    private var setAsynchronous: Method? = null
    private var setStrengthSettingEnabled: Method? = null
    private var setUsageHint: Method? = null
    private var build: Method? = null
    private var vibrateMethod: Method? = null

    private val notes = mutableListOf<String>()

    @Volatile
    var available: Boolean = false
        private set

    /** All int constants the ROM exposes, name to value. */
    var effectConstants: Map<String, Int> = emptyMap()
        private set

    /** Only the ones plausibly usable as a single short tap, in report order. */
    var tapCandidates: List<Pair<String, Int>> = emptyList()
        private set

    var strengthLight = 0; private set
    var strengthMedium = 1; private set
    var strengthStrong = 2; private set

    private val serviceNames = listOf(
        "linearmotor", "oplus_linearmotor", "linearmotor_vibrator", "LinearmotorVibratorService",
    )
    private val vibratorClassNames = listOf(
        "com.oplus.os.LinearmotorVibrator",
        "android.os.LinearmotorVibrator",
        "com.oplus.os.OplusLinearmotorVibrator",
    )
    private val effectClassNames = listOf(
        "com.oplus.os.WaveformEffect",
        "android.os.WaveformEffect",
        "com.oplus.os.OplusWaveformEffect",
    )

    /**
     * Effect families that are single short impulses. Everything named after a ringtone,
     * alarm or notification tune is a multi-second pattern choreographed to a melody —
     * firing one of those per onset would overlap itself into mush.
     */
    private val tapPrefixes = listOf(
        "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE",
        "EFFECT_WEAK_SHORT_VIBRATE_ONCE",
        "EFFECT_MODERATE_SHORT_VIBRATE_ONCE",
        "EFFECT_OTHER_KEYBOARD_",
        "EFFECT_RAZER_",
        "EFFECT_EMULATION_KEYBOARD_",
        "EFFECT_WEAK_EMULATION_KEYBOARD_",
        "EFFECT_GAME_CUSTOM_VIBRATION_",
        "EFFECT_PUBG_",
        "EFFECT_CUSTOMIZED_WEAK_GRANULAR",
        "EFFECT_CUSTOMIZED_STRONG_GRANULAR",
        "EFFECT_OTHER_BIG_SCALE",
        "EFFECT_OTHER_SMALL_SCALE",
        "EFFECT_VIRTUAL_KEY_FEEDBACK",
        "EFFECT_RECENT_TASK_FEEDBACK",
        "EFFECT_OTHER_COMPLETE",
        "EFFECT_OTHER_ELASTICITY",
        "EFFECT_OTHER_WATERRIPPLE",
    )

    fun probe(context: Context): Boolean {
        notes.clear()
        available = false
        service = null; serviceName = null
        effectClass = null; builderClass = null
        effectConstants = emptyMap(); tapCandidates = emptyList()

        notes += "device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val loaders = listOfNotNull(
            context.classLoader, javaClass.classLoader, ClassLoader.getSystemClassLoader(),
        )

        vibratorClassNames.firstOrNull { findClass(it, loaders) != null }
            ?.let { notes += "vibrator class: $it" }

        effectClass = effectClassNames.firstNotNullOfOrNull { name ->
            findClass(name, loaders)?.also { notes += "effect class: $name" }
        }
        if (effectClass == null) notes += "effect class: NOT FOUND"

        effectClass?.let { effect ->
            builderClass = effect.declaredClasses.firstOrNull { it.simpleName == "Builder" }
                ?: findClass("${effect.name}\$Builder", loaders)

            effectConstants = effect.fields
                .filter { it.type == Int::class.javaPrimitiveType }
                .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
                .toMap()

            strengthLight = effectConstants["STRENGTH_LIGHT"] ?: 0
            strengthMedium = effectConstants["STRENGTH_MEDIUM"] ?: 1
            strengthStrong = effectConstants["STRENGTH_STRONG"] ?: 2

            tapCandidates = effectConstants
                .filter { (k, _) -> k.startsWith("EFFECT_") && tapPrefixes.any { k.startsWith(it) } }
                .toList()
                .sortedBy { it.second }
            notes += "constants: ${effectConstants.size}, tap candidates: ${tapCandidates.size}"
        }

        builderClass?.let { b ->
            setEffectType = one(b, "setEffectType")
            setEffectStrength = one(b, "setEffectStrength")
            setAsynchronous = one(b, "setAsynchronous")
            setStrengthSettingEnabled = one(b, "setStrengthSettingEnabled")
            setUsageHint = one(b, "setUsageHint")
            build = b.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }
            notes += "builder signatures:"
            b.declaredMethods.sortedBy { it.name }.forEach { m ->
                if (m.name.startsWith("set") || m.name == "build") {
                    notes += "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
                }
            }
        }

        for (name in serviceNames) {
            val svc = runCatching { context.getSystemService(name) }.getOrNull() ?: continue
            service = svc; serviceName = name
            notes += "service '$name' -> ${svc.javaClass.name}"
            notes += "service signatures:"
            svc.javaClass.declaredMethods.sortedBy { it.name }.forEach { m ->
                notes += "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) : ${m.returnType.simpleName}"
            }
            break
        }
        if (service == null) notes += "service: NOT FOUND"

        val svc = service
        val effect = effectClass
        if (svc != null && effect != null) {
            vibrateMethod = svc.javaClass.methods.firstOrNull {
                it.name == "vibrate" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(effect)
            }
        }

        available = setEffectType != null && build != null &&
            vibrateMethod != null && builderClass != null
        notes += "available: $available"
        Log.i(TAG, "probe -> available=$available")
        return available
    }

    private fun one(c: Class<*>, name: String): Method? =
        c.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }

    /**
     * Fires one effect.
     *
     * [bypassSystemScaling] maps to `setStrengthSettingEnabled(false)`, which on this ROM
     * appears to detach the effect from Settings > Sounds & vibration > Vibration
     * intensity. Worth having, because otherwise the app's own intensity slider is silently
     * multiplied by a system slider the user forgot about.
     */
    /**
     * Effect ids this ROM refused at runtime.
     *
     * Resolution by constant name only proves an id exists, not that the vendor service will
     * act on it — some families appear to be reserved for system callers. Once an id has
     * failed, presets skip it and fall through to their next candidate, so a preset repairs
     * itself instead of staying dead.
     */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    fun isKnownBad(effectId: Int): Boolean = failed.contains(effectId)

    fun forgetFailures() = failed.clear()

    /** Why the last [vibrate] attempt failed, or null. Surfaced in the UI. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Fires one effect.
     *
     * Tried as a ladder rather than a single call. `build()` throws on this ROM for some
     * effect families when an optional setter is present that the effect does not accept —
     * and because every setter is invoked reflectively, one rejected setter takes the whole
     * effect down silently. Dropping the optional setters one at a time recovers those.
     *
     * A note on what this cannot detect: the vendor service may accept the call and simply
     * not vibrate, for effects it reserves for system callers. That returns true here.
     * If a preset resolves and reports no error but you feel nothing, that is the case.
     */
    fun vibrate(effectType: Int, strength: Int? = null, bypassSystemScaling: Boolean = false): Boolean {
        if (!available) {
            lastError = "vendor engine unavailable"
            return false
        }
        val attempts = listOf(
            Attempt(strength, async = true, bypass = bypassSystemScaling),
            Attempt(strength, async = true, bypass = false),
            Attempt(strength, async = false, bypass = false),
            Attempt(null, async = true, bypass = false),
            Attempt(null, async = false, bypass = false),
        )
        var last: Throwable? = null
        for (a in attempts) {
            val r = runCatching {
                val b = builderClass!!.getDeclaredConstructor().newInstance()
                setEffectType!!.invoke(b, effectType)
                a.strength?.let { setEffectStrength?.invoke(b, it) }
                if (a.async) setAsynchronous?.invoke(b, true)
                if (a.bypass) setStrengthSettingEnabled?.invoke(b, false)
                vibrateMethod!!.invoke(service, build!!.invoke(b))
            }
            if (r.isSuccess) {
                lastError = null
                return true
            }
            last = r.exceptionOrNull()
        }
        failed.add(effectType)
        val cause = generateSequence(last) { it.cause }.last()
        lastError = "effect $effectType rejected: ${cause.javaClass.simpleName}: ${cause.message}"
        Log.w(TAG, "vibrate($effectType) failed after ${attempts.size} attempts", last)
        return false
    }

    private data class Attempt(val strength: Int?, val async: Boolean, val bypass: Boolean)

    fun cancel() {
        if (!available) return
        runCatching { service!!.javaClass.getMethod("cancelVibrate").invoke(service) }
    }

    fun describe(): String = buildString {
        appendLine("=== O-Haptics probe ===")
        notes.forEach { appendLine(it) }
        appendLine()
        appendLine("resolved:")
        appendLine("  service       ${service?.javaClass?.name ?: "-"} (as '${serviceName ?: "-"}')")
        appendLine("  effect        ${effectClass?.name ?: "-"}")
        appendLine("  builder       ${builderClass?.name ?: "-"}")
        appendLine("  setEffectType ${setEffectType != null}")
        appendLine("  setStrength   ${setEffectStrength != null}")
        appendLine("  bypassScaling ${setStrengthSettingEnabled != null}")
        appendLine("  usageHint     ${setUsageHint != null}")
        appendLine("  vibrate       ${vibrateMethod != null}")
        appendLine("  available     $available")
    }

    private fun findClass(name: String, loaders: List<ClassLoader>): Class<*>? {
        for (l in loaders) runCatching { Class.forName(name, false, l) }.getOrNull()?.let { return it }
        return runCatching { Class.forName(name) }.getOrNull()
    }
}
