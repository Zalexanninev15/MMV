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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private class Wave(val startNanos: Long, val strength: Float, val accent: Boolean)
private class Note(val startNanos: Long, val lane: Int, val drift: Float)

private const val WAVE_LIFE_MS = 1150f
private const val CLASSIC_WAVE_LIFE_MS = 900f
private const val NOTE_LIFE_MS = 900f
private const val EQ_COLUMNS = 5

/**
 * The beat visualiser.
 *
 * Driven by [EngineState.pulse], published the moment a tap is handed to the haptic engine,
 * so the picture and the vibration are the same event.
 *
 * The character is animated per body part rather than by swapping whole frames: the head
 * nods, the torso sways, the legs alternate, the instrument shears, the eyes blink. Four
 * characters times four instruments times a dozen poses would be two hundred hand-drawn
 * sprites; transforming regions of one sprite gets the same liveliness from one.
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
    var blinkAtNanos by remember { mutableLongStateOf(0L) }

    // Per-band levels for the columns. Bumped by pulses, decayed in the draw pass.
    val bandLevel = remember { floatArrayOf(0f, 0f, 0f) }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { nowNanos = it }
    }

    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        val strength = p.strength.coerceIn(0.15f, 1f)
        waves.add(Wave(nowNanos, strength, p.accent))
        lastHitNanos = nowNanos
        bandLevel[p.band.coerceIn(0, 2)] = min(1f, bandLevel[p.band.coerceIn(0, 2)] + strength)
        if (p.accent || strength > 0.55f) {
            strumFrame = !strumFrame
            lane = (lane + 1) % 3
            notes.add(Note(nowNanos, lane, if (lane % 2 == 0) 1f else -1f))
        }
        while (waves.size > 24) waves.removeAt(0)
        waves.removeAll { ageMs(nowNanos, it.startNanos) > WAVE_LIFE_MS }
        notes.removeAll { ageMs(nowNanos, it.startNanos) > NOTE_LIFE_MS }
    }

    val scheme = MaterialTheme.colorScheme
    val classic = palette == BandPalette.CLASSIC
    val deep: Color
    val shallow: Color
    val litColor: Color
    when (palette) {
        BandPalette.STANDARD -> {
            deep = STANDARD_DEEP
            shallow = STANDARD_SHALLOW
            litColor = STANDARD_LIT
        }

        else -> {
            deep = lerp(scheme.surfaceContainerLowest, scheme.primary, 0.30f)
            shallow = lerp(scheme.surfaceContainerLowest, scheme.primary, 0.48f)
            litColor = lerp(scheme.primaryContainer, Color.White, 0.55f)
        }
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
            // Room on the right for the columns. The character used to sit flush against the
            // edge, which left dead space behind him and nowhere for a wave to come from.
            val eqWidth = cell * (EQ_COLUMNS * 1.6f)
            val spriteLeft = w - spriteW - eqWidth - cell * 0.5f
            val baseline = h - spriteCell * 1.2f - SPRITE_SIZE * spriteCell
            val originCol = (spriteLeft + spriteW * 0.5f) / cell

            val waveLife = if (classic) CLASSIC_WAVE_LIFE_MS else WAVE_LIFE_MS

            for (cx in 0 until cols) {
                val dist = originCol - cx
                if (dist < -1f) continue

                var lit = if (classic) 0f else
                    0.05f + 0.05f * sin(tSec * 1.1f + cx * 0.35f) + level * 0.10f

                for (wv in waves) {
                    val age = ageMs(nowNanos, wv.startNanos)
                    if (age > waveLife) continue
                    val t = age / waveLife
                    if (classic) {
                        // The first implementation: a narrow fast front, constant width.
                        val front = t * (cols * 1.15f)
                        val d = dist - front
                        val width = if (wv.accent) 2.6f else 1.6f
                        lit += exp(-(d * d) / (width * width)) * wv.strength * (1f - t)
                    } else {
                        val front = t * (cols * 1.25f)
                        val width = 1.8f + t * 6.5f
                        val d = (dist - front) / width
                        val fade = (1f - t) * (0.45f + 0.55f * (1f - t))
                        lit += exp(-d * d) * wv.strength * fade * (if (wv.accent) 1.15f else 0.82f)
                    }
                }

                for (cy in 0 until rows) {
                    if (classic) {
                        // Classic drew every cell, lit or not, so the grid was always visible.
                        val a = min(1f, lit)
                        val c = if (a <= 0.01f) lerp(deep, litColor, 0.12f)
                        else lerp(lerp(deep, litColor, 0.12f), litColor, a)
                        drawRect(
                            color = c,
                            topLeft = Offset(cx * cell, cy * cell),
                            size = Size(cell - 1f, cell - 1f),
                        )
                    } else {
                        if (lit <= 0.06f) continue
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
            }

            // --- EQ columns, in the gap to the right of the character ---
            for (i in 0 until 3) bandLevel[i] = max(0f, bandLevel[i] - 0.045f)
            val eqLeft = w - eqWidth
            val colW = eqWidth / EQ_COLUMNS
            for (i in 0 until EQ_COLUMNS) {
                // Five columns from three bands: the outer pair interpolate, so the block
                // moves as a group instead of three independent bars.
                val bandPos = i * 2f / (EQ_COLUMNS - 1)
                val b0 = bandPos.toInt().coerceIn(0, 2)
                val b1 = (b0 + 1).coerceAtMost(2)
                val mix = bandPos - b0
                val v = bandLevel[b0] * (1f - mix) + bandLevel[b1] * mix
                val wobble = 0.10f + 0.06f * sin(tSec * 5f + i * 1.3f)
                val barH = (v * 0.72f + wobble) * h
                val cellsHigh = max(1, (barH / cell).toInt())
                for (k in 0 until cellsHigh) {
                    val a = 0.30f + 0.55f * (1f - k.toFloat() / cellsHigh)
                    drawRect(
                        color = litColor.copy(alpha = a),
                        topLeft = Offset(
                            eqLeft + i * colW + colW * 0.15f,
                            h - (k + 1) * cell + 0.6f,
                        ),
                        size = Size(colW * 0.7f, cell - 1.2f),
                    )
                }
            }

            // --- character, animated per body part ---
            val hitAge = ageMs(nowNanos, lastHitNanos)
            val hit = if (lastHitNanos == 0L) 0f else max(0f, 1f - hitAge / 260f)
            val sway = sin(tSec * 1.7f) * spriteCell * 0.35f
            val breathe = sin(tSec * 2.2f) * spriteCell * 0.16f
            val headNod = hit * spriteCell * 1.5f + sin(tSec * 3.1f) * spriteCell * 0.22f
            val legPhase = sin(tSec * 4.3f)

            // Blink roughly every three seconds, for ~130 ms.
            if (nowNanos - blinkAtNanos > 3_000_000_000L) blinkAtNanos = nowNanos
            val blinking = (nowNanos - blinkAtNanos) < 130_000_000L

            val body = character.rows
            val inst = if (strumFrame) instrument.frameB else instrument.frameA
            val spriteTop = baseline - hit * spriteCell * 1.2f + breathe

            for (y in 0 until SPRITE_SIZE) {
                // Head nods; legs shuffle; torso holds the middle.
                val partDx = when {
                    y <= HEAD_LAST_ROW -> sway * 1.2f
                    y >= LEG_FIRST_ROW -> -sway * 0.4f
                    else -> sway * 0.6f
                }
                val partDy = when {
                    y <= HEAD_LAST_ROW -> headNod
                    y >= LEG_FIRST_ROW -> abs(legPhase) * spriteCell * 0.25f
                    else -> 0f
                }
                for (x in 0 until SPRITE_SIZE) {
                    // Instrument shears with the strum, pivoting near the hands.
                    val shear = (if (strumFrame) 1f else -1f) * hit * spriteCell * 0.5f
                    val instCh = spritePixel(inst, x, y)
                    val ch: Char
                    val dx: Float
                    val dy: Float
                    if (instCh != '.') {
                        ch = instCh
                        dx = partDx + shear
                        dy = partDy * 0.5f
                    } else {
                        ch = spritePixel(body, x, y)
                        dx = partDx
                        dy = partDy
                    }
                    val eyeColor = if (blinking && ch == 'E') character.eyeClosed else character.eye
                    val painted = if (blinking && ch == '5' && y <= HEAD_LAST_ROW) {
                        character.eyeClosed
                    } else {
                        spriteColor(
                            ch, character.hair, character.skin,
                            character.jacket, character.jacketShade, eyeColor,
                        ) ?: continue
                    }
                    drawRect(
                        color = painted,
                        topLeft = Offset(
                            spriteLeft + x * spriteCell + dx,
                            spriteTop + y * spriteCell + dy,
                        ),
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
