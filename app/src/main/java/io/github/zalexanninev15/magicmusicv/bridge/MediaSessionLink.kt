package io.github.zalexanninev15.magicmusicv.bridge

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.zalexanninev15.magicmusicv.library.CachedTrack
import io.github.zalexanninev15.magicmusicv.library.LibraryStore
import kotlin.math.abs

/**
 * Follows Namida's playback through the standard Android media-session interface.
 *
 * Why this and not an LSPosed module: Namida already publishes everything MMV needs through
 * a public API. Its AudioService is an exported MediaBrowserService (it is how Android Auto
 * and Bluetooth controls see it), the session's media id is the track's full file path
 * (`toMediaItemId() => track.path` in Namida's audio handler), and PlaybackState carries
 * position, speed and the timestamp needed to extrapolate between updates.
 *
 * So there is nothing to hook. No root, no module, no patch to re-apply after every Namida
 * release — this is a documented platform interface, not Namida's internals, and it keeps
 * working as long as Namida keeps a media session, which any music player has to.
 *
 * With the path and a precise position, MMV plays from its own cached analysis: no FFT, no
 * screen-capture consent, and nothing from other apps' audio leaking in.
 */
object MediaSessionLink {

    const val NAMIDA_PACKAGE = "com.msob7y.namida"
    private const val AUDIO_SERVICE = "com.ryanheise.audioservice.AudioService"

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var connected = false
        private set

    @Volatile
    private var state: PlaybackState? = null

    /** Namida's media id — the full file path for local tracks. */
    @Volatile
    var mediaId: String? = null
        private set

    @Volatile
    var durationMs: Long = 0
        private set

    @Volatile
    var title: String? = null
        private set

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            state = s
        }

        override fun onMetadataChanged(m: MediaMetadata?) {
            readMetadata(m)
        }

        override fun onSessionDestroyed() {
            connected = false
            state = null
        }
    }

    /**
     * Connects to Namida's media browser service. Must be called on the main thread — the
     * platform MediaBrowser requires a Looper and delivers its callbacks on it.
     *
     * Binding auto-creates the service, so connecting while Namida is closed can start it in
     * the background. Start Namida first.
     */
    fun connect(context: Context, pkg: String, onResult: (Boolean, String?) -> Unit) {
        disconnect()
        val app = context.applicationContext
        lateinit var b: MediaBrowser
        b = MediaBrowser(
            app,
            ComponentName(pkg, AUDIO_SERVICE),
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val c = try {
                        MediaController(app, b.sessionToken)
                    } catch (e: Exception) {
                        onResult(false, "Namida session unavailable: ${e.message}")
                        return
                    }
                    c.registerCallback(callback, main)
                    controller = c
                    state = c.playbackState
                    readMetadata(c.metadata)
                    connected = true
                    onResult(true, null)
                }

                override fun onConnectionFailed() {
                    connected = false
                    onResult(false, "Could not reach Namida — is it installed and running?")
                }

                override fun onConnectionSuspended() {
                    connected = false
                }
            },
            null,
        )
        browser = b
        try {
            b.connect()
        } catch (e: Exception) {
            onResult(false, "Could not reach Namida: ${e.message}")
        }
    }

    fun disconnect() {
        controller?.let { runCatching { it.unregisterCallback(callback) } }
        controller = null
        browser?.let { runCatching { it.disconnect() } }
        browser = null
        connected = false
        state = null
        mediaId = null
        durationMs = 0
        title = null
    }

    private fun readMetadata(m: MediaMetadata?) {
        mediaId = m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
    }

    /**
     * Current playback position, or null when not playing.
     *
     * PlaybackState is only pushed on changes — play, pause, seek, speed — so between
     * updates the position is extrapolated from the update timestamp and speed. This is the
     * method the platform itself documents, and what Bluetooth and system UI rely on.
     */
    fun positionNowMs(): Float? {
        val s = state ?: return null
        if (s.state != PlaybackState.STATE_PLAYING) return null
        val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + elapsed * s.playbackSpeed
    }

    /**
     * Finds MMV's cached analysis for the track Namida is playing.
     *
     * Matched by file name — the media id is a path, and the library cache records each
     * track's display name, which for SAF documents is the file name. When two folders hold
     * files with the same name, duration breaks the tie.
     */
    fun resolveCached(context: Context, id: String, durationMs: Long): CachedTrack? {
        val name = id.substringAfterLast('/')
        if (name.isEmpty()) return null
        val candidates = LibraryStore.cachedTracks(context).values.filter { it.displayName == name }
        if (candidates.size <= 1) return candidates.firstOrNull()
        return candidates.minByOrNull { abs(it.durationMs - durationMs) }
    }
}
