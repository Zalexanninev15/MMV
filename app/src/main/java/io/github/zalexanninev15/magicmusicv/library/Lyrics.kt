package io.github.zalexanninev15.magicmusicv.library

import android.content.Context
import android.net.Uri

/**
 * Synced lyrics, used to know when somebody is actually singing.
 *
 * Detecting a singing voice from the audio alone was measured before this was written, on a
 * vocal song against solo piano and a string orchestra. The obvious cheap features point the
 * wrong way across genres — piano and strings sit in the vocal band more than a band does —
 * and the best physical cue, vocals being mixed dead centre, still confuses orchestral
 * recordings. A .lrc file says exactly when each line is sung, so where one exists it is used
 * and nothing is guessed.
 */
class SingingTimeline(private val starts: LongArray, private val ends: LongArray) {

    /** Index of the line being sung at [posMs], or -1 between lines. */
    fun lineAt(posMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= posMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0 && posMs < ends[found]) found else -1
    }

    val lineCount: Int get() = starts.size
}

object Lyrics {

    /** A sung line is assumed over after this long even if the next timestamp is later. */
    private const val MAX_LINE_MS = 9_000L

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun load(context: Context, uri: String): SingingTimeline? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            parse(input.readBytes().toString(Charsets.UTF_8))
        }
    }.getOrNull()

    /**
     * Parses LRC, including several timestamps on one line and enhanced-LRC word tags.
     *
     * A timestamp with no words — or only a musical-note glyph — marks an instrumental gap,
     * which is exactly where the notes should disappear.
     */
    fun parse(text: String): SingingTimeline? {
        var offset = 0L
        val lines = ArrayList<Pair<Long, Boolean>>()
        for (raw in text.lineSequence()) {
            OFFSET_TAG.find(raw)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
            val tags = TIME_TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val words = TIME_TAG.replace(raw, "").let { WORD_TAG.replace(it, "") }.trim()
            val sung = words.isNotEmpty() && words.any { it.isLetterOrDigit() }
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                // The LRC offset tag shifts lyrics later when positive.
                lines += (min * 60_000 + sec * 1000 + ms - offset) to sung
            }
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.first }

        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        for (i in lines.indices) {
            val (start, sung) = lines[i]
            if (!sung) continue
            val next = if (i + 1 < lines.size) lines[i + 1].first else start + MAX_LINE_MS
            starts += start
            ends += minOf(next, start + MAX_LINE_MS)
        }
        if (starts.isEmpty()) return null
        return SingingTimeline(starts.toLongArray(), ends.toLongArray())
    }
}
