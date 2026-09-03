package io.github.zalexanninev15.magicmusicv.core

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Windowing + FFT + band-split spectral flux. Stateful across hops (needs the previous
 * frame's magnitudes and a rolling sample buffer) but holds no threshold, gating, or
 * history state — that split is what lets [OnsetThresholder] replay a cached flux track
 * without re-running the FFT.
 *
 * frame = 1024 by default, hop = 256. At 48 kHz that's 21.3 ms / 5.3 ms; at another
 * sample rate (a locally analysed file, say 44.1 kHz) the ratios shift slightly and
 * [hopSeconds] reflects the real value — nothing downstream assumes 48 kHz.
 */
class FluxExtractor(
    sampleRate: Int,
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

    private val outFlux = FloatArray(3)
    private val outEnergy = FloatArray(3)

    /**
     * Feeds exactly [hopSize] mono samples and returns this hop's per-band flux/energy.
     * The returned [FluxFrame] owns fresh arrays — safe to retain in a list, unlike the
     * internal scratch buffers.
     */
    fun extract(hop: FloatArray): FluxFrame {
        System.arraycopy(buf, hopSize, buf, 0, frameSize - hopSize)
        System.arraycopy(hop, 0, buf, frameSize - hopSize, hopSize)

        for (i in 0 until frameSize) {
            re[i] = buf[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)

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
            outFlux[b] = flux
            outEnergy[b] = energy
        }

        for (k in ranges[0].first..ranges[2].last) prevMag[k] = mag[k]

        return FluxFrame(outFlux.copyOf(), outEnergy.copyOf())
    }

    fun reset() {
        buf.fill(0f); prevMag.fill(0f); mag.fill(0f)
    }
}
