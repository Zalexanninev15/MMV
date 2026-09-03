package io.github.zalexanninev15.magicmusicv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.zalexanninev15.magicmusicv.EngineState
import io.github.zalexanninev15.magicmusicv.MainActivity
import io.github.zalexanninev15.magicmusicv.Mode
import io.github.zalexanninev15.magicmusicv.R
import io.github.zalexanninev15.magicmusicv.audio.AudioSourceReader
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.core.Band
import io.github.zalexanninev15.magicmusicv.core.CachedBeatGrid
import io.github.zalexanninev15.magicmusicv.core.FluxFrame
import io.github.zalexanninev15.magicmusicv.core.OnsetDetector
import io.github.zalexanninev15.magicmusicv.core.OnsetThresholder
import io.github.zalexanninev15.magicmusicv.core.TempoTracker
import io.github.zalexanninev15.magicmusicv.haptics.HapticEngine
import io.github.zalexanninev15.magicmusicv.haptics.Tap
import io.github.zalexanninev15.magicmusicv.library.CachedTrack
import io.github.zalexanninev15.magicmusicv.library.LibraryState
import io.github.zalexanninev15.magicmusicv.library.LibraryStore
import kotlin.math.roundToInt

class HapticService : Service() {

    private lateinit var engine: HapticEngine
    private lateinit var detector: OnsetDetector
    private lateinit var tempo: TempoTracker
    private lateinit var reader: AudioSourceReader

    // Cached-playback path. Its own OnsetThresholder rather than reusing `detector`'s: no
    // FFT is involved here, flux comes pre-computed from disk, and reusing the field name
    // would blur which path is currently driving the frame counter / median history.
    private val cachedThresholder = OnsetThresholder()
    private var player: MediaPlayer? = null
    private var cachedTrack: CachedTrack? = null
    private var cachedFrames: List<FluxFrame> = emptyList()
    private var cachedBeatGrid: CachedBeatGrid? = null
    private var cachedCursor = 0
    private var lastScheduledBeatMs = Float.NaN
    private var pollRunnable: Runnable? = null

    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    private var lastScheduledBeat = Long.MIN_VALUE
    private var beatStrength = 0.85f
    private var uiTick = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopEverything("Screen capture was revoked") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = HapticEngine(this)
        detector = OnsetDetector()
        tempo = TempoTracker(detector.hopSeconds)
        reader = AudioSourceReader(detector.hopSize)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything(null)
                return START_NOT_STICKY
            }
        }

        val source = EngineState.source.value
        val fgsType = when (source) {
            SourceKind.PLAYBACK_CAPTURE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            SourceKind.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            SourceKind.LOCAL_LIBRARY -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        // Android 14+ requires the foreground service to be running with the
        // mediaProjection type *before* getMediaProjection() is called, so this has to
        // happen first, not after the capture is set up.
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), fgsType)

        if (source == SourceKind.PLAYBACK_CAPTURE) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (data == null) {
                stopEverything("Screen capture consent missing")
                return START_NOT_STICKY
            }
            val mgr = getSystemService(MediaProjectionManager::class.java)
            // getMediaProjection() is @Nullable: the token is single-use and the platform
            // returns null if this Intent was already consumed, or if the FGS type was
            // wrong when it was called.
            val mp = mgr.getMediaProjection(code, data)
            if (mp == null) {
                stopEverything("Screen capture token was rejected — start again")
                return START_NOT_STICKY
            }
            mp.registerCallback(projectionCallback, main)
            projection = mp
        }

        acquireWakeLock()
        applySettings()
        EngineState.tapCount.value = 0

        if (source == SourceKind.LOCAL_LIBRARY) {
            return startCachedPlayback()
        }

        detector.reset()
        tempo.reset()
        lastScheduledBeat = Long.MIN_VALUE

        val ok = reader.start(
            kind = source,
            projection = projection,
            onHop = ::onHop,
            onError = { msg -> main.post { stopEverything(msg) } },
        )
        if (!ok) return START_NOT_STICKY

        EngineState.error.value = null
        EngineState.running.value = true
        return START_STICKY
    }

    // ---------------------------------------------------------------- live capture

    private fun applySettings() {
        engine.intensity = EngineState.intensity.value
        engine.applyChoice(EngineState.backendChoice.value)
        engine.oplusEffects = intArrayOf(
            EngineState.effectLow.value,
            EngineState.effectMid.value,
            EngineState.effectHigh.value,
        )
        engine.bypassSystemScaling = EngineState.bypassSystemScaling.value
        engine.magicPresetId = EngineState.magicPreset.value.takeIf { it.isNotEmpty() }
        detector.sensitivity = EngineState.sensitivity.value
        detector.bandEnabled = booleanArrayOf(
            EngineState.bandLow.value,
            EngineState.bandMid.value,
            EngineState.bandHigh.value,
        )
    }

    private var lastEffects = intArrayOf(-1, -1, -1)

    /**
     * Pulls the tunable settings across on every hop/tick, live and cached alike.
     *
     * These used to be copied once in onStartCommand, which froze intensity, sensitivity
     * and the band switches for the whole session — moving a slider did nothing until you
     * stopped and started again. Volatile writes at audio-thread rate cost nothing; the
     * two allocating fields are only rebuilt when they actually change.
     */
    private fun pullLiveSettings() {
        engine.intensity = EngineState.intensity.value
        engine.bypassSystemScaling = EngineState.bypassSystemScaling.value
        engine.magicPresetId = EngineState.magicPreset.value.takeIf { it.isNotEmpty() }

        detector.sensitivity = EngineState.sensitivity.value
        val be = detector.bandEnabled
        be[0] = EngineState.bandLow.value
        be[1] = EngineState.bandMid.value
        be[2] = EngineState.bandHigh.value
        // Cached playback shares the same array rather than getting its own copy: the two
        // paths never run concurrently, and this keeps "sensitivity/bands mean the same
        // thing" true by construction instead of by two call sites staying in sync.
        cachedThresholder.sensitivity = EngineState.sensitivity.value
        cachedThresholder.bandEnabled = be

        val lo = EngineState.effectLow.value
        val mi = EngineState.effectMid.value
        val hi = EngineState.effectHigh.value
        if (lo != lastEffects[0] || mi != lastEffects[1] || hi != lastEffects[2]) {
            lastEffects = intArrayOf(lo, mi, hi)
            engine.oplusEffects = lastEffects
        }
    }

    /** Runs on the audio thread. Keep it allocation-light and never block it. */
    private fun onHop(hop: FloatArray) {
        pullLiveSettings()
        val onsets = detector.process(hop)
        tempo.push(detector.lastFluxSum)

        val mode = EngineState.mode.value
        val frame = detector.currentFrame
        val hopMs = detector.hopSeconds * 1000f
        val offset = EngineState.offsetMs.value

        if (mode == Mode.BEAT || mode == Mode.HYBRID) {
            val next = tempo.nextBeat(frame)
            if (next != null && next != lastScheduledBeat) {
                // Schedule only once the beat is inside the lookahead window, and let the
                // vibrator service hold the delay. Positive offset = tap later, negative =
                // earlier, which is only possible because the beat is predicted, not
                // reacted to.
                val deltaMs = (next - frame) * hopMs + offset
                if (deltaMs >= 0f && deltaMs <= LOOKAHEAD_MS) {
                    lastScheduledBeat = next
                    engine.play(Tap(Band.LOW, beatStrength, deltaMs.roundToInt(), accent = true))
                    EngineState.tapCount.value++
                }
            }
        }

        if (onsets.isNotEmpty()) {
            val delay = offset.coerceAtLeast(0)
            for (o in onsets) {
                if (o.band == Band.LOW) beatStrength = 0.6f * beatStrength + 0.4f * o.strength
                val emit = when (mode) {
                    Mode.ONSET -> true
                    Mode.BEAT -> false
                    // In hybrid the grid already owns the pulse; a low-band onset landing
                    // on the same beat would double-tap and read as a rattle.
                    Mode.HYBRID -> o.band != Band.LOW && !tempo.isOnBeat(o.frame, 2)
                }
                if (!emit) continue
                val strength = if (mode == Mode.HYBRID) o.strength * 0.7f else o.strength
                engine.play(Tap(o.band, strength, delay))
                EngineState.tapCount.value++
            }
        }

        // Push meters to the UI at ~15 Hz instead of 188 Hz.
        if (++uiTick >= 12) {
            uiTick = 0
            EngineState.bpm.value = tempo.bpm
            EngineState.confidence.value = tempo.confidence
            EngineState.level.value = (detector.lastFluxSum / 4f).coerceIn(0f, 1f)
        }
    }

    // ---------------------------------------------------------------- cached local playback

    /**
     * Starts MMV's own player against the selected library track, using its cached flux
     * instead of a live capture. No AudioRecord, no FFT, no screen-capture consent — the
     * expensive half of the pipeline already ran once when the track was analysed.
     */
    private fun startCachedPlayback(): Int {
        val uri = LibraryState.selectedTrackUri.value
        val cached = uri?.let { u -> LibraryStore.cachedTracks(this)[u] }
        if (uri == null || cached == null) {
            stopEverything("No analysed track selected")
            return START_NOT_STICKY
        }
        val frames = LibraryStore.readFlux(this, cached)
        if (frames == null) {
            stopEverything("Cached analysis for \"${cached.displayName}\" is unreadable — re-analyse it")
            return START_NOT_STICKY
        }

        cachedTrack = cached
        cachedFrames = frames
        cachedCursor = 0
        lastScheduledBeatMs = Float.NaN
        beatStrength = 0.85f
        cachedThresholder.reset()
        cachedBeatGrid = if (cached.beatConfidence >= TempoTracker.MIN_CONFIDENCE) {
            CachedBeatGrid(cached.beatPeriodMs, cached.beatAnchorMs, cached.beatConfidence)
        } else {
            null
        }

        val mp = try {
            MediaPlayer().apply {
                setDataSource(this@HapticService, Uri.parse(uri))
                setOnCompletionListener { stopEverything(null) }
                setOnErrorListener { _, _, _ ->
                    main.post { stopEverything("Playback failed for \"${cached.displayName}\"") }
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopEverything("Could not play \"${cached.displayName}\": ${e.message}")
            return START_NOT_STICKY
        }
        player = mp

        EngineState.bpm.value = cached.bpm
        EngineState.confidence.value = cached.beatConfidence
        EngineState.error.value = null
        EngineState.running.value = true

        val tick = object : Runnable {
            override fun run() {
                cachedTick()
                pollRunnable?.let { main.postDelayed(it, POLL_MS) }
            }
        }
        pollRunnable = tick
        main.postDelayed(tick, POLL_MS)

        return START_STICKY
    }

    /**
     * One poll of cached playback: replays every hop the player has moved past since the
     * last tick through [cachedThresholder] (never just the current one — the adaptive
     * median needs every hop in order, the same as live capture does), then schedules the
     * next predicted beat exactly as [onHop] does but against milliseconds instead of
     * analysis frames.
     *
     * Deliberately mirrors [onHop]'s branching rather than sharing code with it: the two
     * operate on different time bases (frame index vs. player position in ms), and a
     * shared abstraction over both wasn't worth the complexity for this pass.
     */
    private fun cachedTick() {
        val mp = player ?: return
        val track = cachedTrack ?: return
        if (!mp.isPlaying) return

        pullLiveSettings()
        val mode = EngineState.mode.value
        val offset = EngineState.offsetMs.value
        val hopMs = track.hopSeconds * 1000f
        val posMs = mp.currentPosition.toFloat()

        val targetHop = (posMs / hopMs).toInt().coerceAtMost(cachedFrames.size)
        while (cachedCursor < targetHop) {
            val frame = cachedFrames[cachedCursor]
            val hopTimeMs = cachedCursor * hopMs
            val onsets = cachedThresholder.accept(frame)
            if (onsets.isNotEmpty()) {
                val delay = offset.coerceAtLeast(0)
                for (o in onsets) {
                    if (o.band == Band.LOW) beatStrength = 0.6f * beatStrength + 0.4f * o.strength
                    val onGrid = cachedBeatGrid?.isOnBeat(hopTimeMs, 2 * hopMs) == true
                    val emit = when (mode) {
                        Mode.ONSET -> true
                        Mode.BEAT -> false
                        Mode.HYBRID -> o.band != Band.LOW && !onGrid
                    }
                    if (!emit) continue
                    val strength = if (mode == Mode.HYBRID) o.strength * 0.7f else o.strength
                    engine.play(Tap(o.band, strength, delay))
                    EngineState.tapCount.value++
                }
            }
            EngineState.level.value = (frame.sum / 4f).coerceIn(0f, 1f)
            cachedCursor++
        }

        if (mode == Mode.BEAT || mode == Mode.HYBRID) {
            val grid = cachedBeatGrid
            val next = grid?.nextBeat(posMs)
            if (next != null && next != lastScheduledBeatMs) {
                val deltaMs = (next - posMs) + offset
                if (deltaMs >= 0f && deltaMs <= LOOKAHEAD_MS) {
                    lastScheduledBeatMs = next
                    engine.play(Tap(Band.LOW, beatStrength, deltaMs.roundToInt(), accent = true))
                    EngineState.tapCount.value++
                }
            }
        }
    }

    private fun stopCachedPlayback() {
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        cachedTrack = null
        cachedFrames = emptyList()
        cachedBeatGrid = null
    }

    // ---------------------------------------------------------------- lifecycle

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        // A foreground service keeps the process alive but does not guarantee the CPU
        // stays out of deep idle with the screen off, and a stalled audio thread here
        // means taps arrive in clumps. Partial lock only — the screen is untouched.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magicmusicv:engine").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun stopEverything(message: String?) {
        reader.stop()
        stopCachedPlayback()
        engine.cancel()
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        EngineState.running.value = false
        EngineState.bpm.value = 0f
        EngineState.confidence.value = 0f
        EngineState.level.value = 0f
        if (message != null) EngineState.error.value = message
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        reader.stop()
        stopCachedPlayback()
        wakeLock?.let { if (it.isHeld) it.release() }
        EngineState.running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HapticService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tap)
            .setContentTitle(getString(R.string.notif_title))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.zalexanninev15.magicmusicv.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "magicmusicv.engine"
        private const val NOTIF_ID = 1001
        private const val LOOKAHEAD_MS = 260f
        private const val POLL_MS = 30L

        fun start(context: Context, resultCode: Int, resultData: Intent?) {
            val i = Intent(context, HapticService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HapticService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
