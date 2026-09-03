package io.github.zalexanninev15.magicmusicv.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide local-library state, on the same pattern as `EngineState`: a plain object
 * of `MutableStateFlow`s the UI collects directly, rather than a ViewModel — the app has
 * exactly one of these and no navigation graph to scope a ViewModel against.
 */
object LibraryState {

    val tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    val cache = MutableStateFlow<Map<String, CachedTrack>>(emptyMap())

    val analyzing = MutableStateFlow(false)
    /** (done, total) while [analyzing] is true. */
    val progress = MutableStateFlow(0 to 0)
    val currentlyAnalyzing = MutableStateFlow<String?>(null)
    /** Tracks the decoder could not handle in the current run. */
    val failed = MutableStateFlow(0)

    val selectedTrackUri = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var analyzeJob: Job? = null

    fun refresh(context: Context) {
        tracks.value = LibraryStore.scan(context)
        cache.value = LibraryStore.cachedTracks(context)
    }

    /** Analyses every track that has no fresh cache entry yet. Safe to call repeatedly. */
    fun analyzeAll(context: Context) {
        if (analyzing.value) return
        val pending = tracks.value.filter { LibraryStore.lookup(context, it) == null }
        if (pending.isEmpty()) return

        analyzing.value = true
        failed.value = 0
        progress.value = 0 to pending.size
        analyzeJob = scope.launch {
            for ((i, track) in pending.withIndex()) {
                // Cancellation is cooperative and TrackAnalyzer.analyze is a long blocking
                // call, so the only place the job can stop is between tracks.
                if (!isActive) break

                currentlyAnalyzing.value = track.displayName
                val result = TrackAnalyzer.analyze(context, track) { }
                if (result != null) {
                    val stored = LibraryStore.store(
                        context = context,
                        track = track,
                        sampleRate = result.sampleRate,
                        hopSeconds = result.hopSeconds,
                        durationMs = result.durationMs,
                        bpm = result.bpm,
                        beatAnchorMs = result.beatAnchorMs,
                        beatPeriodMs = result.beatPeriodMs,
                        beatConfidence = result.beatConfidence,
                        frames = result.frames,
                    )
                    // Publish after every track, not once at the end. Emitting only on
                    // completion left the list and the counters reading zero for the whole
                    // run — on a 360-file folder that is many minutes of the UI claiming
                    // nothing had been done. Merging into the existing map keeps this O(1);
                    // re-reading the index from disk per track would be O(n^2).
                    cache.value = cache.value + (stored.uri to stored)
                } else {
                    failed.value += 1
                }
                progress.value = (i + 1) to pending.size
            }
            // Reconcile once at the end in case anything was written outside this loop.
            cache.value = LibraryStore.cachedTracks(context)
            currentlyAnalyzing.value = null
            analyzing.value = false
        }
    }

    fun cancelAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = null
        analyzing.value = false
        currentlyAnalyzing.value = null
    }
}
