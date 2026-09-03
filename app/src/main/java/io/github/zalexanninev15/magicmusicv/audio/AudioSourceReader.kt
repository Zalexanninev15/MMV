package io.github.zalexanninev15.magicmusicv.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log

enum class SourceKind { PLAYBACK_CAPTURE, MICROPHONE, LOCAL_LIBRARY }

/**
 * Pulls mono float samples off either the system playback mix or the microphone and
 * hands them to [onHop] in fixed-size blocks.
 *
 * Two sources because neither one covers the whole use case:
 *
 *  - PLAYBACK_CAPTURE is exact and immune to room noise, but Android only lets it see
 *    apps whose capture policy allows it. Most local players (Poweramp, AIMP, VLC,
 *    Musicolet) are capturable; Spotify and YouTube Music are not, and no permission
 *    fixes that — the block is on the playing app's side.
 *  - MICROPHONE always works when the music is on speakers, and never works on
 *    headphones.
 */
class AudioSourceReader(
    private val hopSize: Int,
    private val sampleRate: Int = 48_000,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(
        kind: SourceKind,
        projection: MediaProjection?,
        onHop: (FloatArray) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        stop()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) {
            onError("Device rejected 48 kHz mono float capture")
            return false
        }
        // 4x the minimum: enough headroom that a scheduling hiccup does not overrun,
        // small enough that added latency stays around a couple of hops.
        val bufBytes = maxOf(minBuf * 4, hopSize * 4 * 8)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val rec = try {
            when (kind) {
                SourceKind.PLAYBACK_CAPTURE -> {
                    val proj = projection ?: run {
                        onError("Screen capture consent missing")
                        return false
                    }
                    val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufBytes)
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                }

                SourceKind.MICROPHONE -> AudioRecord.Builder()
                    // UNPROCESSED bypasses AGC and noise suppression. Both flatten
                    // transients, and transients are the only thing this app measures.
                    .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufBytes)
                    .build()

                // Local library playback never reaches this reader: HapticService drives it
                // from a cached flux track and its own MediaPlayer instead of AudioRecord.
                // The branch exists only so this remains an exhaustive `when`.
                SourceKind.LOCAL_LIBRARY -> {
                    onError("LOCAL_LIBRARY does not use AudioSourceReader")
                    return false
                }
            }
        } catch (e: Exception) {
            onError("Could not open audio source: ${e.message}")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("AudioRecord failed to initialise")
            return false
        }

        record = rec
        running = true
        rec.startRecording()

        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val hop = FloatArray(hopSize)
            while (running) {
                var got = 0
                while (got < hopSize && running) {
                    val n = rec.read(hop, got, hopSize - got, AudioRecord.READ_BLOCKING)
                    if (n <= 0) {
                        if (n < 0) Log.w(TAG, "read() returned $n")
                        break
                    }
                    got += n
                }
                if (got == hopSize) onHop(hop)
            }
        }, "mmv-audio").also { it.start() }

        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            it.release()
        }
        record = null
    }

    private companion object {
        const val TAG = "AudioSourceReader"
    }
}
