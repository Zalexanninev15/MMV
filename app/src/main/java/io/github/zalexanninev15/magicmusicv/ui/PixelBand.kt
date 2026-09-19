package io.github.zalexanninev15.magicmusicv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.EngineState
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One expanding ring of brightness, spawned by a tap. */
private class Wave(val startNanos: Long, val strength: Float, val accent: Boolean)

/** A note that floats up out of the instrument on a strong hit. */
private class Note(val startNanos: Long, val lane: Int)

private const val WAVE_LIFE_MS = 900f
private const val NOTE_LIFE_MS = 750f

/**
 * The beat visualiser.
 *
 * A grid of square cells fills the band. Every tap spawns a wave at the character on the
 * right, which travels left as a moving band of brighter cells — so what you see and what
 * you feel come from the same event, not from two independent animations.
 *
 * Driven by [EngineState.pulse], which the service emits at the moment it hands a tap to
 * the haptic engine. Nothing here re-analyses audio.
 */
@Composable
fun PixelBand(
    title: String,
    modifier: Modifier = Modifier,
) {
    val character by EngineState.character.collectAsState()
    val instrument by EngineState.instrument.collectAsState()
    val pulse by EngineState.pulse.collectAsState()

    val waves = remember { mutableStateListOf<Wave>() }
    val notes = remember { mutableStateListOf<Note>() }
    var nowNanos by remember { mutableLongStateOf(0L) }
    var strumFrame by remember { mutableStateOf(false) }
    var noteLane by remember { mutableStateOf(0) }

    // One frame loop for the whole widget. Every moving part reads nowNanos, so nothing
    // animates on its own schedule and the waves cannot drift out of step with the sprite.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nowNanos = it }
        }
    }

    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        waves.add(Wave(nowNanos, p.strength, p.accent))
        if (p.accent || p.strength > 0.6f) {
            strumFrame = !strumFrame
            noteLane = (noteLane + 1) % 3
            notes.add(Note(nowNanos, noteLane))
        }
        // Pruning happens on spawn rather than every frame: the lists are tiny and this
        // keeps the frame loop free of allocation.
        waves.removeAll { ageMs(nowNanos, it.startNanos) > WAVE_LIFE_MS }
        notes.removeAll { ageMs(nowNanos, it.startNanos) > NOTE_LIFE_MS }
    }

    val scheme = MaterialTheme.colorScheme
    val cellBase = scheme.primaryContainer
    val cellLit = scheme.surfaceContainerLowest
    val bandBg = scheme.primary.copy(alpha = 0.22f).flattenOnto(scheme.surfaceContainerLow)

    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(MaterialTheme.shapes.large)
    ) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val cell = size.height / 9f
            val cols = (size.width / cell).toInt() + 1
            val rows = (size.height / cell).toInt() + 1

            drawRect(bandBg, size = size)

            // Character sits against the right edge, one sprite tall.
            val spriteCell = size.height / SPRITE_SIZE.toFloat()
            val spriteLeft = size.width - SPRITE_SIZE * spriteCell - cell
            val originCol = (spriteLeft / cell)

            for (cx in 0 until cols) {
                for (cy in 0 until rows) {
                    var lit = 0f
                    for (w in waves) {
                        val age = ageMs(nowNanos, w.startNanos)
                        if (age > WAVE_LIFE_MS) continue
                        // Distance measured in cells from the character, so the wave front
                        // sweeps leftwards at a constant speed regardless of band width.
                        val dist = originCol - cx
                        if (dist < 0) continue
                        val front = age / WAVE_LIFE_MS * (cols * 1.15f)
                        val d = dist - front
                        val fade = 1f - age / WAVE_LIFE_MS
                        val width = if (w.accent) 2.6f else 1.6f
                        lit += exp(-(d * d) / (width * width)) * w.strength * fade
                    }
                    if (lit <= 0.01f) {
                        // Unlit cells still draw, so the pixel grid reads as a grid.
                        drawRect(
                            color = cellBase.copy(alpha = 0.35f),
                            topLeft = Offset(cx * cell, cy * cell),
                            size = Size(cell - 1f, cell - 1f),
                        )
                    } else {
                        drawRect(
                            color = lerp(cellBase, cellLit, min(1f, lit)),
                            topLeft = Offset(cx * cell, cy * cell),
                            size = Size(cell - 1f, cell - 1f),
                        )
                    }
                }
            }

            // Bounce: strongest right after a hit, settling within ~200 ms.
            val lastHit = waves.maxOfOrNull { it.startNanos } ?: 0L
            val hitAge = ageMs(nowNanos, lastHit)
            val bounce = if (lastHit == 0L) 0f else max(0f, 1f - hitAge / 200f) * spriteCell * 0.8f
            val spriteTop = -bounce

            val body = character.rows
            val inst = if (strumFrame) instrument.frameB else instrument.frameA
            for (y in 0 until SPRITE_SIZE) {
                for (x in 0 until SPRITE_SIZE) {
                    val ch = spritePixel(inst, x, y).takeIf { it != '.' }
                        ?: spritePixel(body, x, y)
                    val color = spriteColor(
                        ch, character.hair, character.skin, character.jacket, character.jacketShade
                    ) ?: continue
                    drawRect(
                        color = color,
                        topLeft = Offset(spriteLeft + x * spriteCell, spriteTop + y * spriteCell),
                        size = Size(spriteCell + 0.5f, spriteCell + 0.5f),
                    )
                }
            }

            for (n in notes) {
                val age = ageMs(nowNanos, n.startNanos)
                if (age > NOTE_LIFE_MS) continue
                val t = age / NOTE_LIFE_MS
                val nx = spriteLeft + (3 + n.lane * 3) * spriteCell
                val ny = size.height * 0.45f - t * size.height * 0.5f
                val alpha = 1f - t
                drawRect(
                    color = Color(0xFFFFD75E).copy(alpha = alpha),
                    topLeft = Offset(nx, ny),
                    size = Size(spriteCell * 1.4f, spriteCell * 1.4f),
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onPrimaryContainer,
            modifier = Modifier.padding(start = 16.dp, top = 34.dp),
        )
    }
}

private fun ageMs(nowNanos: Long, startNanos: Long): Float =
    if (startNanos == 0L) Float.MAX_VALUE else (nowNanos - startNanos) / 1_000_000f

private fun Color.flattenOnto(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}
