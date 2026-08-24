package io.github.zalexanninev15.magicmusicv.haptics

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Optional backend that talks to OPLUS's own haptic service instead of AOSP's Vibrator.
 *
 * This is what people mean by "O-Haptics" at the API level: ColorOS / OxygenOS / realme UI
 * ship a `LinearmotorVibrator` system service and a `WaveformEffect` type in the `oplus`
 * shared library, alongside — not inside — the AOSP framework. The class name, package and
 * method signatures have moved between ColorOS releases, so nothing here is hardcoded
 * against a version: [probe] discovers what actually exists on this device and the object
 * disables itself if anything is missing.
 *
 * Read [describe] output before trusting it. It prints the effect constants this ROM
 * exposes, which is the only reliable source for the numbers to pass to [vibrate].
 *
 * Trade-off worth understanding before switching this on: the OPLUS API fires one prebaked
 * effect at a time. It has no equivalent of `VibrationEffect.Composition`'s per-primitive
 * delay, so the batched, HAL-timed scheduling that Beat and Hybrid modes rely on is lost.
 * Use it in Onsets mode, where every tap is immediate anyway.
 */
object OplusHaptics {

    private const val TAG = "OplusHaptics"

    private var service: Any? = null
    private var builderClass: Class<*>? = null
    private var effectClass: Class<*>? = null
    private var setEffectType: Method? = null
    private var setStrength: Method? = null
    private var setAsynchronous: Method? = null
    private var build: Method? = null
    private var vibrateMethod: Method? = null

    @Volatile
    var available: Boolean = false
        private set

    /** Effect constants this ROM exposes, name to value. Empty until [probe] runs. */
    var effectConstants: Map<String, Int> = emptyMap()
        private set

    private val serviceClassNames = listOf(
        "com.oplus.os.LinearmotorVibrator",
        "android.os.LinearmotorVibrator",
    )
    private val effectClassNames = listOf(
        "com.oplus.os.WaveformEffect",
        "android.os.WaveformEffect",
    )

    /**
     * Attempts to bind to the OPLUS vibrator. Safe to call on any device; returns false
     * and leaves the object inert on anything that is not an OPLUS ROM.
     */
    fun probe(context: Context): Boolean {
        available = false
        val svc = runCatching { context.getSystemService("linearmotor") }.getOrNull()
        if (svc == null) {
            Log.i(TAG, "no linearmotor system service on this device")
            return false
        }
        service = svc

        effectClass = effectClassNames.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name) }.getOrNull()
        }
        val effect = effectClass ?: run {
            Log.w(TAG, "linearmotor service present but WaveformEffect class not found")
            return false
        }

        builderClass = effect.declaredClasses.firstOrNull { it.simpleName == "Builder" }
            ?: runCatching { Class.forName("${effect.name}\$Builder") }.getOrNull()
        val builder = builderClass ?: run {
            Log.w(TAG, "WaveformEffect.Builder not found")
            return false
        }

        setEffectType = builder.methods.firstOrNull {
            it.name == "setEffectType" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        setStrength = builder.methods.firstOrNull {
            it.name == "setStrength" && it.parameterTypes.size == 1
        }
        setAsynchronous = builder.methods.firstOrNull {
            it.name == "setAsynchronous" && it.parameterTypes.size == 1
        }
        build = builder.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }

        vibrateMethod = svc.javaClass.methods.firstOrNull {
            it.name == "vibrate" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(effect)
        }

        effectConstants = effect.fields
            .filter {
                it.type == Int::class.javaPrimitiveType &&
                    (it.name.startsWith("EFFECT_") || it.name.startsWith("STRENGTH_"))
            }
            .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
            .toMap()

        available = setEffectType != null && build != null && vibrateMethod != null
        Log.i(TAG, "probe -> available=$available, ${effectConstants.size} constants")
        return available
    }

    /**
     * Fires one prebaked OPLUS effect. [effectType] must be a value from
     * [effectConstants] — the numbering is not stable across ColorOS releases, so do not
     * hardcode integers copied from a blog post.
     */
    fun vibrate(effectType: Int, strength: Int? = null, async: Boolean = true): Boolean {
        if (!available) return false
        return runCatching {
            val b = builderClass!!.getDeclaredConstructor().newInstance()
            setEffectType!!.invoke(b, effectType)
            strength?.let { s -> setStrength?.invoke(b, s) }
            setAsynchronous?.invoke(b, async)
            val effect = build!!.invoke(b)
            vibrateMethod!!.invoke(service, effect)
            true
        }.getOrElse {
            Log.w(TAG, "vibrate failed, disabling backend", it)
            available = false
            false
        }
    }

    /**
     * Human-readable dump of what was discovered. Print this from the app (or logcat) on
     * the target device before wiring any effect constant into the tap mapping.
     */
    fun describe(): String = buildString {
        appendLine("service: ${service?.javaClass?.name ?: "none"}")
        appendLine("effect:  ${effectClass?.name ?: "none"}")
        appendLine("builder: ${builderClass?.name ?: "none"}")
        appendLine("setEffectType: ${setEffectType != null}")
        appendLine("setStrength:   ${setStrength != null}")
        appendLine("setAsync:      ${setAsynchronous != null}")
        appendLine("vibrate:       ${vibrateMethod != null}")
        appendLine("available: $available")
        appendLine("--- constants (${effectConstants.size}) ---")
        effectConstants.entries.sortedBy { it.value }.forEach { (k, v) -> appendLine("$k = $v") }
    }
}
