package io.github.zalexanninev15.magicmusicv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.EngineState
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One expanding front of brightness, spawned by a tap. */
private class Wave(val startNanos: Long, val strength: Float, val accent: Boolean)

/** A note that drifts up and away from the instrument on a strong hit. */
private class Note(val startNanos: Long, val lane: Int, val drift: Float)

private const val WAVE_LIFE_MS = 1150f
private const val NOTE_LIFE_MS = 900f

/**
 * The beat visualiser.
 *
 * Driven by [EngineState.pulse], which the service publishes at the moment it hands a tap
 * to the haptic engine — so the picture and the vibration come from one event and cannot
 * drift apart.
 *
 * Three things here exist because the first version got them wrong:
 *
 *  - The grid is drawn only where a wave lights it. Painting every cell all the time made
 *    the whole band look like graph paper; in the reference the dark part is smooth and the
 *    pixels only emerge inside the moving light.
 *  - A wave is a wide front that *widens* as it travels, not a two-cell line. A narrow band
 *    reads as a scanline; a broad soft one reads as a shockwave.
 *  - The sprite is smaller than the band and sits on a baseline, so the bounce has somewhere
 *    to go. At full height its head was clipped off the top on every hit.
 */
@Composable
fun PixelBand(
    title: String,
    modifier: Modifier = Modifier,
) {
    val character by EngineState.character.collectAsState()
    val instrument by EngineState.instrument.collectAsState()
    val pulse by EngineState.pulse.collectAsState()
    val level by EngineState.level.collectAsState()
    val palette by EngineState.bandPalette.collectAsState()

    val waves = remember { mutableStateListOf<Wave>() }
    val notes = remember { mutableStateListOf<Note>() }
    var nowNanos by remember { mutableLongStateOf(0L) }
    var strumFrame by remember { mutableStateOf(false) }
    var lane by remember { mutableStateOf(0) }
    var lastHitNanos by remember { mutableLongStateOf(0L) }

    // A single frame loop for every moving part, so nothing animates on its own clock.
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { nowNanos = it }
    }

    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        waves.add(Wave(nowNanos, p.strength.coerceIn(0.15f, 1f), p.accent))
        lastHitNanos = nowNanos
        if (p.accent || p.strength > 0.55f) {
            strumFrame = !strumFrame
            lane = (lane + 1) % 3
            notes.add(Note(nowNanos, lane, if (lane % 2 == 0) 1f else -1f))
        }
        // Pruned on spawn rather than per frame: the lists stay tiny and the frame loop
        // stays allocation-free.
        while (waves.size > 24) waves.removeAt(0)
        waves.removeAll { ageMs(nowNanos, it.startNanos) > WAVE_LIFE_MS }
        notes.removeAll { ageMs(nowNanos, it.startNanos) > NOTE_LIFE_MS }
    }

    val scheme = MaterialTheme.colorScheme
    val deep: Color
    val shallow: Color
    val litColor: Color
    if (palette == BandPalette.VIDEO) {
        deep = VIDEO_DEEP
        shallow = VIDEO_SHALLOW
        litColor = VIDEO_LIT
    } else {
        deep = lerp(scheme.surfaceContainerLowest, scheme.primary, 0.30f)
        shallow = lerp(scheme.surfaceContainerLowest, scheme.primary, 0.48f)
        litColor = lerp(scheme.primaryContainer, Color.White, 0.55f)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(MaterialTheme.shapes.large)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cell = h / 11f
            val cols = (w / cell).toInt() + 1
            val rows = (h / cell).toInt() + 1
            val tSec = nowNanos / 1_000_000_000f

            drawRect(Brush.horizontalGradient(listOf(deep, shallow)), size = size)

            val spriteCell = h * 0.72f / SPRITE_SIZE
            val spriteW = SPRITE_SIZE * spriteCell
            val spriteLeft = w - spriteW - cell * 0.8f
            val baseline = h - spriteCell * 1.2f - SPRITE_SIZE * spriteCell
            val originCol = spriteLeft / cell + SPRITE_SIZE * spriteCell / cell / 2f

            for (cx in 0 until cols) {
                val dist = originCol - cx
                if (dist < -1f) continue

                // Ambient shimmer so the band is alive between taps instead of dead flat.
                var lit = 0.05f + 0.05f * sin(tSec * 1.1f + cx * 0.35f) + level * 0.10f

                for (wv in waves) {
                    val age = ageMs(nowNanos, wv.startNanos)
                    if (age > WAVE_LIFE_MS) continue
                    val t = age / WAVE_LIFE_MS
                    val front = t * (cols * 1.25f)
                    // The front widens as it travels: tight at the instrument, broad by the
                    // far edge. This is what makes it read as a shockwave rather than a line.
                    val width = 1.8f + t * 6.5f
                    val d = (dist - front) / width
                    // Squared fade killed the wave around two-thirds across; in the
                    // reference it still reads at the far edge.
                    val fade = (1f - t) * (0.45f + 0.55f * (1f - t))
                    val amp = if (wv.accent) 1.15f else 0.82f
                    lit += exp(-d * d) * wv.strength * fade * amp
                }
                if (lit <= 0.06f) continue

                for (cy in 0 until rows) {
                    // Slight vertical shaping keeps the front from looking like a solid bar.
                    val rowT = (cy + 0.5f) / rows
                    val shape = 0.82f + 0.18f * sin((rowT * PI).toFloat())
                    val a = min(1f, lit * shape)
                    if (a <= 0.06f) continue
                    drawRect(
                        color = litColor.copy(alpha = a * 0.85f),
                        topLeft = Offset(cx * cell + 0.6f, cy * cell + 0.6f),
                        size = Size(cell - 1.2f, cell - 1.2f),
                    )
                }
            }

            // Bounce decays over ~260 ms; idle breathing keeps the sprite from freezing.
            val hitAge = ageMs(nowNanos, lastHitNanos)
            val bounce = if (lastHitNanos == 0L) 0f else max(0f, 1f - hitAge / 260f)
            val breathe = sin(tSec * 2.2f) * spriteCell * 0.18f
            val spriteTop = baseline - bounce * spriteCell * 1.6f + breathe

            val body = character.rows
            val inst = if (strumFrame) instrument.frameB else instrument.frameA
            for (y in 0 until SPRITE_SIZE) {
                for (x in 0 until SPRITE_SIZE) {
                    val ch = spritePixel(inst, x, y).takeIf { it != '.' }
                        ?: spritePixel(body, x, y)
                    val color = spriteColor(
                        ch, character.hair, character.skin,
                        character.jacket, character.jacketShade,
                    ) ?: continue
                    drawRect(
                        color = color,
                        topLeft = Offset(spriteLeft + x * spriteCell, spriteTop + y * spriteCell),
                        size = Size(spriteCell + 0.6f, spriteCell + 0.6f),
                    )
                }
            }

            for (n in notes) {
                val age = ageMs(nowNanos, n.startNanos)
                if (age > NOTE_LIFE_MS) continue
                val t = age / NOTE_LIFE_MS
                val alpha = (1f - t).coerceIn(0f, 1f)
                val px = spriteLeft + spriteW * 0.55f +
                    sin(t * 6f + n.lane) * spriteCell * 1.6f * n.drift
                val py = spriteTop + spriteCell * 3f - t * h * 0.65f
                val s = spriteCell
                val note = Color(0xFFFFD75E).copy(alpha = alpha)
                // Head plus stem, drawn as two rects — a five-pixel note at this size.
                drawRect(note, Offset(px, py + s * 1.6f), Size(s * 1.8f, s * 1.2f))
                drawRect(note, Offset(px + s * 1.4f, py), Size(s * 0.5f, s * 1.9f))
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp),
        )
    }
}

private fun ageMs(nowNanos: Long, startNanos: Long): Float =
    if (startNanos == 0L) Float.MAX_VALUE else (nowNanos - startNanos) / 1_000_000f
