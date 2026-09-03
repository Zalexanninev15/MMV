package io.github.zalexanninev15.magicmusicv.library

/** One audio file found under the chosen folder, not yet necessarily analysed. */
data class LibraryTrack(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String,
)

/**
 * What offline analysis produced for one track.
 *
 * [sizeBytes]/[lastModified] are carried over from the source [LibraryTrack] purely to
 * detect a stale cache — if a file at the same URI now reports different values, the
 * cached flux no longer corresponds to what is on disk and gets re-analysed. [fluxFile] is
 * a filename inside the app's private cache directory, not a full path, so the cache
 * survives the app's data directory moving (e.g. on some device-to-device transfers).
 */
data class CachedTrack(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val sampleRate: Int,
    val hopSeconds: Float,
    val durationMs: Long,
    val bpm: Float,
    val beatAnchorMs: Float,
    val beatPeriodMs: Float,
    val beatConfidence: Float,
    val fluxFile: String,
)
