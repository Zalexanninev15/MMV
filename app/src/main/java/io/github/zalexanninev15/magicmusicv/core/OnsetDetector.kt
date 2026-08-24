package io.github.zalexanninev15.magicmusicv.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Band { LOW, MID, HIGH }

/** One detected transient. [strength] is 0..1, [frame] is the analysis frame index. */
data class Onset(val band: Band, val strength: Float, val frame: Long)

/**
 * Band-split spectral flux with a per-band adaptive (median) threshold.
 *
 * Three bands rather than one broadband detector, because the haptic layer needs to
 * know *what* hit, not only *that* something hit: a kick and a hi-hat must map to
 * different primitives and different scales, otherwise every tap feels identical and
 * the whole point of the app is lost.
 *
 * frame = 1024 @ 48 kHz (21.3 ms) with hop = 256 (5.3 ms). The hop sets the timing
 * resolution of every tap, so it is kept short; the frame stays long enough that the
 * low band still has usable bins (46.9 Hz each).
 */
class OnsetDetector(
    sampleRate: Int = 48_000,
    private val frameSize: Int = 1024,
    val hopSize: Int = 256,
) {
    val hopSeconds: Float = hopSize.toFloat() / sampleRate

    private val bins = frameSize / 2
    private val fft = Fft(frameSize)
    private val window = FloatArray(frameSize) {
        (0.5 - 0.5 * cos(2.0 * Math.PI * it / (frameSize - 1))).toFloat()
    }

    private val buf = FloatArray(frameSize)
    private val re = FloatArray(frameSize)
    private val im = FloatArray(frameSize)
    private val mag = FloatArray(bins)
    private val prevMag = FloatArray(bins)

    private val binHz = sampleRate.toFloat() / frameSize
    private fun bin(hz: Float) = (hz / binHz).toInt().coerceIn(1, bins - 1)

    // 30-190 Hz kick/bass, 190-1000 Hz snare body and low mids, 2-9 kHz hats and clicks.
    private val ranges = arrayOf(
        bin(30f)..bin(190f),
        bin(190f)..bin(1000f),
        bin(2000f)..bin(9000f),
    )

    /** Minimum gap between two taps of the same band, in frames (~80 / 55 / 40 ms). */
    private val minGap = intArrayOf(15, 10, 8)

    private val histLen = 48 // ~256 ms of flux history feeding the median threshold
    private val hist = Array(3) { FloatArray(histLen) }
    private val histScratch = FloatArray(histLen)
    private var histPos = 0
    private var histFilled = 0
    private val lastOnsetFrame = LongArray(3) { Long.MIN_VALUE / 4 }

    private var frame = 0L
    /** Index of the most recently analysed frame. Used by the beat scheduler. */
    val currentFrame: Long get() = frame

    var lastFluxSum = 0f
        private set

    /** Sensitivity multiplier applied to the adaptive threshold. Lower = more taps. */
    @Volatile
    var sensitivity: Float = 1.5f

    /** Per-band enable flags, indexed by [Band.ordinal]. */
    @Volatile
    var bandEnabled: BooleanArray = booleanArrayOf(true, true, true)

    /**
     * Feeds exactly [hopSize] mono samples and returns the onsets found in this hop.
     * Called from the audio thread; allocates only the (usually empty) result list.
     */
    fun process(hop: FloatArray): List<Onset> {
        System.arraycopy(buf, hopSize, buf, 0, frameSize - hopSize)
        System.arraycopy(hop, 0, buf, frameSize - hopSize, hopSize)
        frame++

        for (i in 0 until frameSize) {
            re[i] = buf[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)

        var fluxSum = 0f
        var result: MutableList<Onset>? = null

        for (b in 0..2) {
            var flux = 0f
            var energy = 0f
            for (k in ranges[b]) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                mag[k] = m
                energy += m
                val d = m - prevMag[k]
                if (d > 0f) flux += d
            }
            fluxSum += flux

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

        for (k in ranges[0].first..ranges[2].last) prevMag[k] = mag[k]

        histPos = (histPos + 1) % histLen
        if (histFilled < histLen) histFilled++
        lastFluxSum = fluxSum

        return result ?: emptyList()
    }

    private fun median(b: Int): Float {
        System.arraycopy(hist[b], 0, histScratch, 0, histLen)
        histScratch.sort()
        return histScratch[histLen / 2]
    }

    fun reset() {
        buf.fill(0f); prevMag.fill(0f); mag.fill(0f)
        hist.forEach { it.fill(0f) }
        histPos = 0; histFilled = 0; frame = 0
        lastOnsetFrame.fill(Long.MIN_VALUE / 4)
    }
}
