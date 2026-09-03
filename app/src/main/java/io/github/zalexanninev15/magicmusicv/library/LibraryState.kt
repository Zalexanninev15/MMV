package io.github.zalexanninev15.magicmusicv.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        progress.value = 0 to pending.size
        analyzeJob = scope.launch {
            for ((i, track) in pending.withIndex()) {
                currentlyAnalyzing.value = track.displayName
                val result = TrackAnalyzer.analyze(context, track) { }
                if (result != null) {
                    LibraryStore.store(
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
                }
                progress.value = (i + 1) to pending.size
            }
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
