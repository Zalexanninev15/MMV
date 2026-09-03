package io.github.zalexanninev15.magicmusicv.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Estimates tempo and beat phase from the onset envelope by autocorrelation.
 *
 * Why bother, when reactive tapping already works: a reactive tap can never be early.
 * It carries the analysis latency (~one hop plus the audio buffer), and worse, it goes
 * missing whenever the beat is implied rather than struck. A phase-locked grid lets the
 * haptic layer *schedule* the next beat ahead of time, which is the only way to place a
 * tap exactly on — or deliberately before — the musical event.
 */
class TempoTracker(private val hopSeconds: Float) {

    private val size = 1024                       // ~5.5 s of envelope history
    private val env = FloatArray(size)
    private var pos = 0
    private var total = 0L

    private val minLag = (60f / 200f / hopSeconds).roundToInt()   // 200 BPM
    private val maxLag = (60f / 60f / hopSeconds).roundToInt()    // 60 BPM

    @Volatile var bpm: Float = 0f; private set
    @Volatile var confidence: Float = 0f; private set

    private var periodFrames = 0
    private var anchorFrame = -1L                 // absolute frame of a known beat
    private var lastAnalysis = 0L

    /**
     * Anchor beat position in seconds, or null with no lock yet. Exposed so
     * `library/TrackAnalyzer.kt` can run this tracker across a whole file once and persist
     * the converged estimate — [CachedBeatGrid] replays it during playback without redoing
     * any of the autocorrelation.
     */
    val anchorSeconds: Float? get() = if (periodFrames > 0 && anchorFrame >= 0) anchorFrame * hopSeconds else null

    val periodSeconds: Float? get() = if (periodFrames > 0) periodFrames * hopSeconds else null

    fun push(flux: Float) {
        env[pos] = flux
        pos = (pos + 1) % size
        total++
        if (total > size && total - lastAnalysis >= 64) {   // re-estimate ~3x/second
            lastAnalysis = total
            analyse()
        }
    }

    /**
     * Absolute frame index of the first predicted beat strictly after [afterFrame],
     * or null when the tracker has no usable lock.
     */
    fun nextBeat(afterFrame: Long): Long? {
        val p = periodFrames
        if (p <= 0 || anchorFrame < 0 || confidence < MIN_CONFIDENCE) return null
        val steps = ((afterFrame - anchorFrame) / p) + 1
        return anchorFrame + steps * p
    }

    /** True when [frame] falls within [toleranceFrames] of a predicted beat. */
    fun isOnBeat(frame: Long, toleranceFrames: Int): Boolean {
        val p = periodFrames
        if (p <= 0 || anchorFrame < 0 || confidence < MIN_CONFIDENCE) return false
        val offset = ((frame - anchorFrame) % p + p) % p
        return offset <= toleranceFrames || p - offset <= toleranceFrames
    }

    private fun analyse() {
        // Copy the ring into chronological order once; the loops below index it a lot.
        val n = size
        val e = FloatArray(n)
        for (i in 0 until n) e[i] = env[(pos + i) % n]

        var mean = 0f
        for (v in e) mean += v
        mean /= n
        for (i in 0 until n) e[i] = (e[i] - mean).coerceAtLeast(0f)

        var bestLag = 0
        var bestScore = 0f
        var scoreSum = 0f
        var scoreCount = 0

        for (lag in minLag..maxLag) {
            var acc = 0f
            var i = lag
            while (i < n) {
                acc += e[i] * e[i - lag]
                i++
            }
            acc /= (n - lag)
            // Bias towards 100-140 BPM. Autocorrelation is inherently ambiguous between
            // a tempo and its half/double; without a prior it flips octave mid-track and
            // the taps audibly halve or double, which reads as a bug to the user.
            val period = lag * hopSeconds
            val w = 1f / (1f + 1.4f * abs(ln(period / 0.5f)))
            val score = acc * w
            scoreSum += score
            scoreCount++
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        if (bestLag == 0 || scoreCount == 0) return
        val avg = scoreSum / scoreCount
        confidence = if (avg > 0f) bestScore / avg else 0f
        periodFrames = bestLag
        bpm = 60f / (bestLag * hopSeconds)

        // Phase: slide a comb of period bestLag over the envelope and keep the offset
        // with the highest total energy.
        var bestPhase = 0
        var bestPhaseScore = -1f
        for (phase in 0 until bestLag) {
            var acc = 0f
            var i = phase
            while (i < n) {
                acc += e[i]
                i += bestLag
            }
            if (acc > bestPhaseScore) {
                bestPhaseScore = acc
                bestPhase = phase
            }
        }
        // e[0] corresponds to absolute frame (total - n).
        anchorFrame = (total - n) + bestPhase
    }

    fun reset() {
        env.fill(0f); pos = 0; total = 0; lastAnalysis = 0
        bpm = 0f; confidence = 0f; periodFrames = 0; anchorFrame = -1
    }

    companion object {
        const val MIN_CONFIDENCE = 2.0f
    }
}
