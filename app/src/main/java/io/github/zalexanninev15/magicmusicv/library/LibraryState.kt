package io.github.zalexanninev15.magicmusicv.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

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

    /** Tracks ticked in the Library list, by URI. */
    val checked = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Serialises cache writes. LibraryStore.store reads the index, adds an entry and writes it
     * back; two workers doing that at once would each overwrite the other's entry.
     */
    private val storeLock = Mutex()

    /** Analyses every track that has no fresh cache entry yet. Safe to call repeatedly. */
    fun analyzeAll(context: Context) = analyze(context, null)

    /**
     * Analyses [uris], or every unanalysed track when null.
     *
     * Several tracks run at once. Decoding and the per-hop FFT are single-threaded per file,
     * so one file at a time left most of the cores idle on a modern phone. Half the cores,
     * two to four workers: enough to use the hardware without starving the UI and the audio
     * thread if haptics are running at the same time.
     */
    fun analyze(context: Context, uris: Collection<String>?) {
        if (analyzing.value) return
        val cached = cache.value
        val pool = if (uris == null) tracks.value else tracks.value.filter { it.uri in uris }
        val pending = pool.filter { cached[it.uri] == null && LibraryStore.lookup(context, it) == null }
        if (pending.isEmpty()) return

        val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)

        analyzing.value = true
        failed.value = 0
        progress.value = 0 to pending.size
        analyzeJob = scope.launch {
            coroutineScope {
                repeat(workers) {
                    launch {
                        while (isActive) {
                            val i = next.getAndIncrement()
                            if (i >= pending.size) break
                            val track = pending[i]
                            currentlyAnalyzing.value = track.displayName
                            val result = TrackAnalyzer.analyze(context, track) { }
                            if (result != null) {
                                val stored = storeLock.withLock {
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
                                // Published per track so the list fills in as it goes. update{}
                                // is an atomic compare-and-set; a plain read-then-assign from
                                // several workers would drop entries.
                                cache.update { it + (stored.uri to stored) }
                            } else {
                                failed.update { it + 1 }
                            }
                            progress.value = done.incrementAndGet() to pending.size
                        }
                    }
                }
            }
            // Reconcile once at the end in case anything was written outside this run.
            cache.value = LibraryStore.cachedTracks(context)
            checked.update { set -> set.filterTo(HashSet()) { cache.value[it] == null } }
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
