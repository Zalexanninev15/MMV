package io.github.zalexanninev15.magicmusicv.core

/**
 * Live onset detection: [FluxExtractor] feeding [OnsetThresholder] every hop.
 *
 * Kept as a thin facade with the pre-split public surface (hopSize, hopSeconds,
 * currentFrame, lastFluxSum, sensitivity, bandEnabled, process(), reset()) specifically so
 * every existing caller — the audio thread in `HapticService`, `TempoTracker`'s
 * construction from `hopSeconds` — needed zero changes when flux extraction and
 * thresholding were split apart. See [FluxExtractor] and [OnsetThresholder] for why they
 * were split, and `library/TrackAnalyzer.kt` for the other consumer of [FluxExtractor]
 * this split exists for: offline analysis of local files.
 */
class OnsetDetector(
    sampleRate: Int = 48_000,
    frameSize: Int = 1024,
    val hopSize: Int = 256,
) {
    val hopSeconds: Float = hopSize.toFloat() / sampleRate

    private val extractor = FluxExtractor(sampleRate, frameSize, hopSize)
    private val thresholder = OnsetThresholder()

    val currentFrame: Long get() = thresholder.currentFrame

    var lastFluxSum: Float = 0f
        private set

    var sensitivity: Float
        get() = thresholder.sensitivity
        set(value) { thresholder.sensitivity = value }

    var bandEnabled: BooleanArray
        get() = thresholder.bandEnabled
        set(value) { thresholder.bandEnabled = value }

    /**
     * Feeds exactly [hopSize] mono samples and returns the onsets found in this hop.
     * Called from the audio thread; allocates only the (usually empty) result list plus
     * the two small arrays inside the returned [FluxFrame].
     */
    fun process(hop: FloatArray): List<Onset> {
        val frame = extractor.extract(hop)
        lastFluxSum = frame.sum
        return thresholder.accept(frame)
    }

    fun reset() {
        extractor.reset()
        thresholder.reset()
    }
}
