package io.github.zalexanninev15.magicmusicv.core

import kotlin.math.max
import kotlin.math.min

/**
 * Per-band adaptive (median) threshold, gating, and onset firing.
 *
 * Split out of what used to be a single `OnsetDetector` class so the exact same decision
 * logic runs whether the [FluxFrame] came live from [FluxExtractor] or was read back from
 * a track's cached flux during local-library playback. One algorithm, not two copies that
 * could drift apart — which matters here specifically because sensitivity, band on/off,
 * and mode all have to keep meaning the same thing on a cached track as on a live capture.
 */
class OnsetThresholder {

    /** Minimum gap between two taps of the same band, in frames (~80 / 55 / 40 ms at a
     *  256-sample hop — the size this was tuned against). */
    private val minGap = intArrayOf(15, 10, 8)

    private val histLen = 48 // ~256 ms of flux history feeding the median threshold
    private val hist = Array(3) { FloatArray(histLen) }
    private val histScratch = FloatArray(histLen)
    private var histPos = 0
    private var histFilled = 0
    private val lastOnsetFrame = LongArray(3) { Long.MIN_VALUE / 4 }

    private var frame = 0L
    val currentFrame: Long get() = frame

    /** Sensitivity multiplier applied to the adaptive threshold. Lower = more taps. */
    @Volatile
    var sensitivity: Float = 1.5f

    /** Per-band enable flags, indexed by [Band.ordinal]. */
    @Volatile
    var bandEnabled: BooleanArray = booleanArrayOf(true, true, true)

    /**
     * Feeds one hop's flux/energy and returns the onsets it fires, if any. Callers must
     * call this once per hop, in order — the internal frame counter and the median
     * history both depend on that.
     */
    fun accept(f: FluxFrame): List<Onset> {
        frame++
        var result: MutableList<Onset>? = null

        for (b in 0..2) {
            val flux = f.flux[b]
            val energy = f.energy[b]

            val threshold = median(b) * sensitivity + 1e-3f
            val gapOk = frame - lastOnsetFrame[b] >= minGap[b]
            // Energy gate: silence and fade-outs produce a tiny median, which would
            // otherwise let noise cross the threshold and tap through quiet passages.
            val loudEnough = energy > 0.02f

            if (histFilled >= histLen && gapOk && loudEnough && flux > threshold && bandEnabled[b]) {
                val over = (flux / threshold - 1f) / 2.5f
                val strength = min(1f, max(0.05f, over))
                lastOnsetFrame[b] = frame
                val list = result ?: ArrayList<Onset>(2).also { result = it }
                list.add(Onset(Band.entries[b], strength, frame))
            }

            hist[b][histPos] = flux
        }

        histPos = (histPos + 1) % histLen
        if (histFilled < histLen) histFilled++

        return result ?: emptyList()
    }

    private fun median(b: Int): Float {
        System.arraycopy(hist[b], 0, histScratch, 0, histLen)
        histScratch.sort()
        return histScratch[histLen / 2]
    }

    fun reset() {
        hist.forEach { it.fill(0f) }
        histPos = 0; histFilled = 0; frame = 0
        lastOnsetFrame.fill(Long.MIN_VALUE / 4)
    }
}
