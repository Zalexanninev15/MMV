package io.github.zalexanninev15.magicmusicv.core

enum class Band { LOW, MID, HIGH }

/** One detected transient. [strength] is 0..1, [frame] is the analysis frame index. */
data class Onset(val band: Band, val strength: Float, val frame: Long)

/**
 * Per-hop flux and energy for the three bands, indexed by [Band.ordinal].
 *
 * This is the expensive half of onset detection — windowing plus an FFT. It carries no
 * threshold or gating state, which is what lets it be computed once during offline library
 * analysis, cached to disk, and replayed later through [OnsetThresholder] at a fraction of
 * the cost. See `library/TrackAnalyzer.kt`.
 */
data class FluxFrame(val flux: FloatArray, val energy: FloatArray) {
    val sum: Float get() = flux[0] + flux[1] + flux[2]
}
