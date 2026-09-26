package io.github.zalexanninev15.magicmusicv.library

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.zalexanninev15.magicmusicv.core.FluxExtractor
import io.github.zalexanninev15.magicmusicv.core.FluxFrame
import io.github.zalexanninev15.magicmusicv.core.TempoTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes one local file and runs it through the same flux extraction the live pipeline
 * uses, without any of the live pipeline's real-time constraints — this can run faster
 * than playback speed, which is the entire point: the expensive part happens once, off
 * the audio-critical path, instead of every time the track plays.
 *
 * Format support rides on the platform's own decoders via `MediaExtractor`/`MediaCodec`,
 * not a bundled one: MP3, M4A/AAC and Ogg Opus have been solid since early Android
 * versions. Standalone FLAC container extraction is a newer platform addition — solid by
 * the API level this app targets, but untested here across every possible FLAC encoding,
 * so a file that fails to decode is reported rather than silently skipped.
 */
object TrackAnalyzer {

    /** True if MMV recognises this as a supported audio type. */
    fun isSupportedMime(mime: String?): Boolean = mime != null && mime.startsWith("audio/")

    data class Result(
        val sampleRate: Int,
        val hopSeconds: Float,
        val durationMs: Long,
        val bpm: Float,
        val beatAnchorMs: Float,
        val beatPeriodMs: Float,
        val beatConfidence: Float,
        val frames: List<FluxFrame>,
    )

    /**
     * Decodes and analyses [track], reporting fractional progress (0f..1f) as it goes.
     * Runs on [Dispatchers.Default] — safe to call from the main thread.
     */
    suspend fun analyze(
        context: Context,
        track: LibraryTrack,
        onProgress: (Float) -> Unit,
    ): Result? = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, android.net.Uri.parse(track.uri), null)
        } catch (_: Exception) {
            return@withContext null
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (isSupportedMime(mime)) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return@withContext null
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (_: Exception) {
            extractor.release()
            return@withContext null
        }

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        val fluxExtractor = FluxExtractor(sampleRate)
        val tempo = TempoTracker(fluxExtractor.hopSeconds, autoAnalyse = false)
        val estimatedHops = if (durationUs > 0) {
            (durationUs / 1_000_000.0 / fluxExtractor.hopSeconds).toInt().coerceAtLeast(64)
        } else {
            4096
        }
        val frames = ArrayList<FluxFrame>(estimatedHops)

        // Samples are gathered into one reused hop buffer. The previous version concatenated
        // carry-over and new samples into a fresh array per decoder buffer and copied out a
        // fresh array per hop — tens of thousands of short-lived allocations per track.
        val hop = FloatArray(fluxExtractor.hopSize)
        var fill = 0
        fun feedHops(samples: FloatArray, count: Int) {
            var i = 0
            while (i < count) {
                val take = minOf(hop.size - fill, count - i)
                System.arraycopy(samples, i, hop, fill, take)
                fill += take
                i += take
                if (fill == hop.size) {
                    val f = fluxExtractor.extract(hop)
                    frames.add(f)
                    tempo.push(f.sum)
                    fill = 0
                }
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10_000L

        try {
            // Fill every free input buffer and drain every ready output buffer before
            // waiting at all. The old loop fed one input, then blocked up to 10 ms for one
            // output; the decoder works on its own thread, so output was often not ready yet
            // and that wait was paid on nearly every frame of every file.
            while (!sawOutputEOS) {
                var progressed = false

                while (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }

                while (true) {
                    // Only block when this pass made no progress at all.
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (progressed) 0L else timeoutUs)
                    if (outIndex < 0) break
                    progressed = true
                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        // Android's audio decoders emit 16-bit signed little-endian PCM,
                        // interleaved by channel.
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val sampleCount = shorts.remaining()
                        val raw = ShortArray(sampleCount)
                        shorts.get(raw)

                        val frameCount = sampleCount / channelCount
                        val mono = FloatArray(frameCount)
                        if (channelCount <= 1) {
                            for (i in 0 until frameCount) mono[i] = raw[i] / 32768f
                        } else {
                            for (i in 0 until frameCount) {
                                var sum = 0
                                for (c in 0 until channelCount) sum += raw[i * channelCount + c]
                                mono[i] = (sum / channelCount) / 32768f
                            }
                        }
                        feedHops(mono, mono.size)

                        if (durationUs > 0) {
                            onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                        break
                    }
                }
                // Negative indices — try-again, format-changed — just end the drain pass.
                // A mid-stream format change inside one audio file is out of scope, as before.
            }
        } catch (_: Exception) {
            codec.stop(); codec.release(); extractor.release()
            return@withContext null
        }

        codec.stop()
        codec.release()
        extractor.release()

        val durationMs = if (durationUs > 0) {
            durationUs / 1000
        } else {
            (frames.size * fluxExtractor.hopSeconds * 1000).toLong()
        }

        onProgress(1f)

        // The one tempo estimate, over the final window — what the per-64-hop estimates
        // used to converge to, computed once instead of hundreds of times.
        tempo.analyseNow()

        Result(
            sampleRate = sampleRate,
            hopSeconds = fluxExtractor.hopSeconds,
            durationMs = durationMs,
            bpm = tempo.bpm,
            beatAnchorMs = (tempo.anchorSeconds ?: 0f) * 1000f,
            beatPeriodMs = (tempo.periodSeconds ?: 0f) * 1000f,
            beatConfidence = tempo.confidence,
            frames = frames,
        )
    }
}
