package io.github.zalexanninev15.magicmusicv.core

/**
 * A precomputed, static beat grid — the cached-playback counterpart to [TempoTracker].
 *
 * [TempoTracker] earns its tempo/phase estimate from a rolling window of live audio and
 * keeps re-estimating as the track plays. For a locally analysed file the estimate is
 * computed once, from the whole track, during `library/TrackAnalyzer.kt`, and is treated
 * as fixed for playback — cheap enough to evaluate on every position poll with no history
 * or autocorrelation at all.
 *
 * Operates in milliseconds rather than analysis frames, since playback position comes
 * straight from the player as milliseconds and converting both ways every poll would be
 * pure overhead for no benefit.
 */
class CachedBeatGrid(
    private val periodMs: Float,
    private val anchorMs: Float,
    val confidence: Float,
) {
    val hasLock: Boolean get() = periodMs > 0f && confidence >= TempoTracker.MIN_CONFIDENCE

    /** First predicted beat strictly after [afterMs], or null with no usable lock. */
    fun nextBeat(afterMs: Float): Float? {
        if (!hasLock) return null
        val steps = ((afterMs - anchorMs) / periodMs).toInt() + 1
        return anchorMs + steps * periodMs
    }

    /** True when [atMs] falls within [toleranceMs] of a predicted beat. */
    fun isOnBeat(atMs: Float, toleranceMs: Float): Boolean {
        if (!hasLock) return false
        val offset = ((atMs - anchorMs) % periodMs + periodMs) % periodMs
        return offset <= toleranceMs || periodMs - offset <= toleranceMs
    }
}
