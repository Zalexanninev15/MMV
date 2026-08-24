package io.github.zalexanninev15.magicmusicv.haptics

import android.content.Context
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * Optional backend that talks to OPLUS's own haptic service instead of AOSP's Vibrator.
 *
 * "O-Haptics" at the API level is a `LinearmotorVibrator` system service plus a
 * `WaveformEffect` type living in the `oplus` shared library — alongside, not inside, the
 * AOSP framework. Names and signatures have moved between ColorOS releases, so nothing is
 * hardcoded: [probe] discovers what exists and records why each step failed.
 *
 * Every step is independent on purpose. An earlier version returned as soon as the system
 * service came back null, which meant the report said nothing on exactly the devices where
 * you most need to know what went wrong.
 *
 * Trade-off before switching this on: the OPLUS API fires one prebaked effect at a time and
 * has no equivalent of `VibrationEffect.Composition`'s per-primitive delay, so the batched
 * HAL-timed scheduling behind Beat and Hybrid modes is lost. It suits Onsets mode.
 */
object OplusHaptics {

    private const val TAG = "OplusHaptics"

    private var service: Any? = null
    private var serviceName: String? = null
    private var builderClass: Class<*>? = null
    private var effectClass: Class<*>? = null
    private var setEffectType: Method? = null
    private var setStrength: Method? = null
    private var setAsynchronous: Method? = null
    private var build: Method? = null
    private var vibrateMethod: Method? = null

    private val notes = mutableListOf<String>()

    @Volatile
    var available: Boolean = false
        private set

    /** Effect constants this ROM exposes, name to value. Empty until [probe] runs. */
    var effectConstants: Map<String, Int> = emptyMap()
        private set

    private val serviceNames = listOf(
        "linearmotor",
        "oplus_linearmotor",
        "linearmotor_vibrator",
        "LinearmotorVibratorService",
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

    /** Safe on any device; leaves the object inert on anything that is not an OPLUS ROM. */
    fun probe(context: Context): Boolean {
        notes.clear()
        available = false
        service = null
        serviceName = null
        effectClass = null
        builderClass = null
        effectConstants = emptyMap()

        notes += "device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        notes += "fingerprint: ${Build.FINGERPRINT}"

        // Is the oplus shared library even visible to this process?
        val libs = runCatching { context.packageManager.systemSharedLibraryNames?.toList() }
            .getOrNull()
            .orEmpty()
        notes += "shared libs containing 'oplus': " +
            (libs.filter { it.contains("oplus", true) }.ifEmpty { listOf("(none)") }.joinToString())

        // Class lookup runs regardless of whether the service resolves — the constants are
        // useful on their own, and a class-not-found is a different problem than a
        // service-not-found.
        val loaders = listOfNotNull(
            context.classLoader,
            javaClass.classLoader,
            ClassLoader.getSystemClassLoader(),
        )

        for (name in vibratorClassNames) {
            val c = findClass(name, loaders)
            if (c != null) {
                notes += "vibrator class found: $name"
                break
            }
        }

        effectClass = effectClassNames.firstNotNullOfOrNull { name ->
            findClass(name, loaders)?.also { notes += "effect class found: $name" }
        }
        if (effectClass == null) notes += "effect class: NOT FOUND (tried ${effectClassNames.joinToString()})"

        effectClass?.let { effect ->
            builderClass = effect.declaredClasses.firstOrNull { it.simpleName == "Builder" }
                ?: findClass("${effect.name}\$Builder", loaders)
            notes += if (builderClass != null) "builder: ${builderClass!!.name}" else "builder: NOT FOUND"

            effectConstants = effect.fields
                .filter {
                    it.type == Int::class.javaPrimitiveType &&
                        (it.name.startsWith("EFFECT_") || it.name.startsWith("STRENGTH_") ||
                            it.name.startsWith("TYPE_"))
                }
                .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
                .toMap()
            notes += "int constants harvested: ${effectConstants.size}"
        }

        builderClass?.let { b ->
            setEffectType = b.methods.firstOrNull {
                it.name == "setEffectType" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            setStrength = b.methods.firstOrNull { it.name == "setStrength" && it.parameterTypes.size == 1 }
            setAsynchronous = b.methods.firstOrNull { it.name == "setAsynchronous" && it.parameterTypes.size == 1 }
            build = b.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }
            notes += "builder methods: " + b.methods.map { it.name }.distinct().sorted().joinToString()
        }

        for (name in serviceNames) {
            val svc = runCatching { context.getSystemService(name) }.getOrNull()
            if (svc != null) {
                service = svc
                serviceName = name
                notes += "system service '$name' -> ${svc.javaClass.name}"
                notes += "service methods: " +
                    svc.javaClass.methods.map { it.name }.distinct().sorted().joinToString()
                break
            }
        }
        if (service == null) notes += "system service: NOT FOUND (tried ${serviceNames.joinToString()})"

        val svc = service
        val effect = effectClass
        if (svc != null && effect != null) {
            vibrateMethod = svc.javaClass.methods.firstOrNull {
                it.name == "vibrate" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(effect)
            }
            if (vibrateMethod == null) notes += "no vibrate(WaveformEffect) overload on the service"
        }

        available = setEffectType != null && build != null && vibrateMethod != null &&
            builderClass != null
        notes += "available: $available"
        Log.i(TAG, "probe -> available=$available")
        return available
    }

    private fun findClass(name: String, loaders: List<ClassLoader>): Class<*>? {
        for (l in loaders) {
            runCatching { Class.forName(name, false, l) }.getOrNull()?.let { return it }
        }
        return runCatching { Class.forName(name) }.getOrNull()
    }

    /**
     * Fires one prebaked OPLUS effect. [effectType] must come from [effectConstants] — the
     * numbering is not stable across ColorOS releases, so don't hardcode integers copied
     * from a forum post.
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

    /** Full report. Shown in the app and written to a file — logcat is not required. */
    fun describe(): String = buildString {
        appendLine("=== Magic Music V / O-Haptics probe ===")
        notes.forEach { appendLine(it) }
        appendLine()
        appendLine("resolved:")
        appendLine("  service        ${service?.javaClass?.name ?: "-"} (as '${serviceName ?: "-"}')")
        appendLine("  effect class   ${effectClass?.name ?: "-"}")
        appendLine("  builder class  ${builderClass?.name ?: "-"}")
        appendLine("  setEffectType  ${setEffectType != null}")
        appendLine("  setStrength    ${setStrength != null}")
        appendLine("  setAsync       ${setAsynchronous != null}")
        appendLine("  vibrate        ${vibrateMethod != null}")
        appendLine()
        appendLine("constants (${effectConstants.size}):")
        if (effectConstants.isEmpty()) {
            appendLine("  (none)")
        } else {
            effectConstants.entries.sortedBy { it.value }
                .forEach { (k, v) -> appendLine("  $k = $v") }
        }
    }
}
