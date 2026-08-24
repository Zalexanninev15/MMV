package io.github.zalexanninev15.magicmusicv.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * One text report answering "what can this device's actuator actually do".
 *
 * Written to be read on the phone, not in logcat: OxygenOS and ColorOS suppress
 * third-party app logs unless full logging is turned on in the engineering menu, which
 * makes `adb logcat` a bad place to put anything you need to see.
 *
 * Everything past the plain AOSP calls goes through reflection on purpose. The point is to
 * find out which APIs exist on this ROM; code that has to compile against them can only be
 * written after the answer is known.
 */
object HapticsReport {

    private val primitives = linkedMapOf(
        "CLICK" to VibrationEffect.Composition.PRIMITIVE_CLICK,
        "THUD" to VibrationEffect.Composition.PRIMITIVE_THUD,
        "SPIN" to VibrationEffect.Composition.PRIMITIVE_SPIN,
        "QUICK_RISE" to VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
        "SLOW_RISE" to VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
        "QUICK_FALL" to VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        "TICK" to VibrationEffect.Composition.PRIMITIVE_TICK,
        "LOW_TICK" to VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
    )

    fun build(context: Context): String = buildString {
        val vibrator: Vibrator =
            context.getSystemService(VibratorManager::class.java).defaultVibrator

        appendLine("=== device ===")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        appendLine(Build.FINGERPRINT)
        appendLine()

        appendLine("=== AOSP vibrator ===")
        appendLine("hasVibrator ......... ${vibrator.hasVibrator()}")
        appendLine("hasAmplitudeControl . ${vibrator.hasAmplitudeControl()}")
        appendLine()

        val ids = primitives.values.toIntArray()
        val supported = runCatching { vibrator.arePrimitivesSupported(*ids) }.getOrNull()
        val durations = runCatching { vibrator.getPrimitiveDurations(*ids) }.getOrNull()
        appendLine("primitives:")
        primitives.keys.forEachIndexed { i, name ->
            val ok = supported?.getOrNull(i)
            val ms = durations?.getOrNull(i)
            appendLine("  ${name.padEnd(11)} ${ok ?: "?"}${if (ms != null && ms > 0) "  ${ms} ms" else ""}")
        }
        appendLine()

        // Android 16 added first-class envelope haptics. If OPLUS dropped its proprietary
        // path, this is almost certainly where it went — and unlike the OPLUS API it is
        // public, and it keeps the composition timing model.
        appendLine("=== envelope / frequency (Android 16+) ===")
        val interesting = vibrator.javaClass.methods
            .map { it.name }
            .filter { it.contains("Envelope", true) || it.contains("Frequency", true) }
            .distinct()
            .sorted()
        appendLine("Vibrator methods: ${interesting.ifEmpty { listOf("(none)") }.joinToString()}")

        appendLine(
            "areEnvelopeEffectsSupported: " + call(vibrator, "areEnvelopeEffectsSupported")
        )
        for (getter in listOf("getFrequencyProfile", "getFrequencyProfileHz")) {
            val profile = runCatching {
                vibrator.javaClass.getMethod(getter).invoke(vibrator)
            }.getOrNull()
            if (profile != null) {
                appendLine("$getter -> ${profile.javaClass.name}")
                profile.javaClass.methods
                    .filter { it.parameterCount == 0 && it.name.startsWith("get") }
                    .sortedBy { it.name }
                    .forEach { m ->
                        runCatching { appendLine("  ${m.name} = ${fmt(m.invoke(profile))}") }
                    }
            }
        }
        appendLine(
            "VibrationEffect nested types: " +
                VibrationEffect::class.java.declaredClasses.joinToString { it.simpleName }
        )
        appendLine()

        append(OplusHaptics.describe())
    }

    private fun call(target: Any, method: String): String = runCatching {
        fmt(target.javaClass.getMethod(method).invoke(target))
    }.getOrElse { "not present" }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is BooleanArray -> v.contentToString()
        is Array<*> -> v.contentToString()
        else -> v.toString()
    }
}
