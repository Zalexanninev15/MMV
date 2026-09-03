package io.github.zalexanninev15.magicmusicv.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.zalexanninev15.magicmusicv.core.FluxFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Everything about "point MMV at a folder": persisting which folder, scanning it, and
 * reading/writing the per-track analysis cache.
 *
 * The cache lives in [Context.filesDir] — private, no storage permission needed, cleared
 * automatically on uninstall — never in the SAF-granted folder itself, which the app can
 * write nothing into without the user separately picking write access.
 */
object LibraryStore {

    private const val PREFS = "magicmusicv_library"
    private const val KEY_FOLDER_URI = "folderUri"
    private const val INDEX_FILE = "library-index.json"
    private const val CACHE_DIR = "flux-cache"
    private const val BLOB_MAGIC = 0x4D4D5646 // "MMVF"
    private const val BLOB_VERSION = 1

    private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "opus")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- folder

    fun folderUri(context: Context): Uri? =
        prefs(context).getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }

    /** Persists the tree permission grant and remembers which folder it points to. */
    fun setFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        prefs(context).edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
    }

    // ---------------------------------------------------------------- scan

    /** Walks the chosen folder for files with a recognised audio extension. */
    fun scan(context: Context): List<LibraryTrack> {
        val root = folderUri(context)?.let { DocumentFile.fromTreeUri(context, it) } ?: return emptyList()
        val out = ArrayList<LibraryTrack>()
        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                    continue
                }
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue
                out += LibraryTrack(
                    uri = child.uri.toString(),
                    displayName = name,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                    mimeType = child.type ?: "audio/*",
                )
            }
        }
        walk(root)
        return out.sortedBy { it.displayName.lowercase() }
    }

    // ---------------------------------------------------------------- index

    private fun indexFile(context: Context) = File(context.filesDir, INDEX_FILE)

    private fun readIndex(context: Context): MutableMap<String, CachedTrack> {
        val f = indexFile(context)
        if (!f.exists()) return LinkedHashMap()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return LinkedHashMap()
        val map = LinkedHashMap<String, CachedTrack>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: continue
            map[uri] = CachedTrack(
                uri = uri,
                displayName = o.optString("displayName"),
                sizeBytes = o.optLong("sizeBytes"),
                lastModified = o.optLong("lastModified"),
                sampleRate = o.optInt("sampleRate"),
                hopSeconds = o.optDouble("hopSeconds").toFloat(),
                durationMs = o.optLong("durationMs"),
                bpm = o.optDouble("bpm").toFloat(),
                beatAnchorMs = o.optDouble("beatAnchorMs").toFloat(),
                beatPeriodMs = o.optDouble("beatPeriodMs").toFloat(),
                beatConfidence = o.optDouble("beatConfidence").toFloat(),
                fluxFile = o.optString("fluxFile"),
            )
        }
        return map
    }

    private fun writeIndex(context: Context, map: Map<String, CachedTrack>) {
        val arr = JSONArray()
        for (t in map.values) {
            arr.put(
                JSONObject().apply {
                    put("uri", t.uri)
                    put("displayName", t.displayName)
                    put("sizeBytes", t.sizeBytes)
                    put("lastModified", t.lastModified)
                    put("sampleRate", t.sampleRate)
                    put("hopSeconds", t.hopSeconds.toDouble())
                    put("durationMs", t.durationMs)
                    put("bpm", t.bpm.toDouble())
                    put("beatAnchorMs", t.beatAnchorMs.toDouble())
                    put("beatPeriodMs", t.beatPeriodMs.toDouble())
                    put("beatConfidence", t.beatConfidence.toDouble())
                    put("fluxFile", t.fluxFile)
                }
            )
        }
        indexFile(context).writeText(arr.toString())
    }

    /** All cached entries, keyed by URI. */
    fun cachedTracks(context: Context): Map<String, CachedTrack> = readIndex(context)

    /**
     * The cache entry for [track], or null if there is none or it is stale (size or
     * modification time no longer matches what is on disk).
     */
    fun lookup(context: Context, track: LibraryTrack): CachedTrack? {
        val entry = readIndex(context)[track.uri] ?: return null
        val fresh = entry.sizeBytes == track.sizeBytes && entry.lastModified == track.lastModified
        return if (fresh) entry else null
    }

    fun deleteCache(context: Context, uri: String) {
        val map = readIndex(context)
        val entry = map.remove(uri) ?: return
        writeIndex(context, map)
        File(cacheDir(context), entry.fluxFile).delete()
    }

    fun clearAll(context: Context) {
        writeIndex(context, emptyMap())
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- flux blobs

    private fun cacheDir(context: Context): File =
        File(context.filesDir, CACHE_DIR).apply { mkdirs() }

    /** Stable filename for a track's blob, independent of its display name. */
    private fun blobName(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24) + ".flux"
    }

    /**
     * Writes [frames] as a compressed binary blob and records the [CachedTrack] entry.
     * Overwrites any previous cache for the same URI.
     */
    fun store(
        context: Context,
        track: LibraryTrack,
        sampleRate: Int,
        hopSeconds: Float,
        durationMs: Long,
        bpm: Float,
        beatAnchorMs: Float,
        beatPeriodMs: Float,
        beatConfidence: Float,
        frames: List<FluxFrame>,
    ): CachedTrack {
        val fileName = blobName(track.uri)
        val file = File(cacheDir(context), fileName)
        DataOutputStream(DeflaterOutputStream(file.outputStream())).use { out ->
            out.writeInt(BLOB_MAGIC)
            out.writeInt(BLOB_VERSION)
            out.writeInt(frames.size)
            for (f in frames) {
                out.writeFloat(f.flux[0]); out.writeFloat(f.flux[1]); out.writeFloat(f.flux[2])
                out.writeFloat(f.energy[0]); out.writeFloat(f.energy[1]); out.writeFloat(f.energy[2])
            }
        }

        val cached = CachedTrack(
            uri = track.uri,
            displayName = track.displayName,
            sizeBytes = track.sizeBytes,
            lastModified = track.lastModified,
            sampleRate = sampleRate,
            hopSeconds = hopSeconds,
            durationMs = durationMs,
            bpm = bpm,
            beatAnchorMs = beatAnchorMs,
            beatPeriodMs = beatPeriodMs,
            beatConfidence = beatConfidence,
            fluxFile = fileName,
        )
        val map = readIndex(context)
        map[track.uri] = cached
        writeIndex(context, map)
        return cached
    }

    /** Reads a track's cached flux back into memory. Null if the blob is missing or corrupt. */
    fun readFlux(context: Context, cached: CachedTrack): List<FluxFrame>? {
        val file = File(cacheDir(context), cached.fluxFile)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(InflaterInputStream(file.inputStream())).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                check(magic == BLOB_MAGIC && version == BLOB_VERSION) { "bad flux blob header" }
                val count = input.readInt()
                val out = ArrayList<FluxFrame>(count)
                repeat(count) {
                    val flux = floatArrayOf(input.readFloat(), input.readFloat(), input.readFloat())
                    val energy = floatArrayOf(input.readFloat(), input.readFloat(), input.readFloat())
                    out += FluxFrame(flux, energy)
                }
                out
            }
        }.getOrNull()
    }

    /** Total bytes currently used by the flux cache, for showing the user what it costs. */
    fun cacheSizeBytes(context: Context): Long =
        cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
