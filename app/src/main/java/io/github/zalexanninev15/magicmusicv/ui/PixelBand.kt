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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import io.github.zalexanninev15.magicmusicv.EngineState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private class Wave(val startNanos: Long, val strength: Float, val accent: Boolean)
private class Note(val startNanos: Long, val lane: Int, val drift: Float, val fromHead: Boolean)
private class Spark(val startNanos: Long, val seed: Int)

private const val WAVE_LIFE_MS = 1100f
private const val CLASSIC_WAVE_LIFE_MS = 900f
private const val NOTE_LIFE_MS = 900f
private const val SPARK_LIFE_MS = 320f
private const val EQ_COLUMNS = 5

private const val HERO_EVERY_TAPS = 1000L
private const val HERO_DURATION_NS = 5_000_000_000L

private val HERO_BG = Color(0xFF0B0A10)
private val HERO_NEON = listOf(
    Color(0xFFFFF200), Color(0xFFFF3DDB), Color(0xFF37D3FF),
    Color(0xFFFF8A1F), Color(0xFF9BFF3D), Color(0xFF8B5CFF),
)
private val SPARK = Color(0xFFFFD75E)
private val TWO_PI = (2 * PI).toFloat()

/**
 * The beat visualiser.
 *
 * Driven by [EngineState.pulse], published the moment a tap is handed to the haptic engine,
 * so the picture and the vibration are the same event.
 *
 * Built from a frame-by-frame read of the reference clip:
 *
 *  - Waves are rings expanding from the character in every direction, starting as a solid
 *    disc and hollowing out as they grow — not fronts sweeping left. That is also why there
 *    was nothing behind the character before: a leftward sweep never goes right.
 *  - The character squashes wide and short on strong beats with the eyes spreading to the
 *    corners, leans back with the free arm flung out, squeezes the eyes shut into dashes
 *    while the playing is dense, and throws lightning off the instrument on big accents.
 *
 * Every thousandth tap, Hero X takes the stage for five seconds with a shattered neon burst
 * in place of the rings.
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
    val enhanced by EngineState.enhancedAnimations.collectAsState()
    val tapCount by EngineState.tapCount.collectAsState()

    val waves = remember { mutableStateListOf<Wave>() }
    val notes = remember { mutableStateListOf<Note>() }
    val sparks = remember { mutableStateListOf<Spark>() }
    var nowNanos by remember { mutableLongStateOf(0L) }
    var strumFrame by remember { mutableStateOf(false) }
    var lane by remember { mutableStateOf(0) }
    var accentCount by remember { mutableStateOf(0) }
    var lastHitNanos by remember { mutableLongStateOf(0L) }
    var squashNanos by remember { mutableLongStateOf(0L) }
    var leanStartNanos by remember { mutableLongStateOf(0L) }
    var leanDir by remember { mutableStateOf(-1f) }
    var shredUntilNanos by remember { mutableLongStateOf(0L) }
    var sparkReadyNanos by remember { mutableLongStateOf(0L) }
    var flashNanos by remember { mutableLongStateOf(0L) }
    var heroStartNanos by remember { mutableLongStateOf(0L) }
    var heroMilestone by remember { mutableLongStateOf(0L) }
    var lastMilestone by remember { mutableLongStateOf(tapCount / HERO_EVERY_TAPS) }
    var heroActive by remember { mutableStateOf(false) }

    // Mutated inside the draw pass, so deliberately not snapshot state: writing state from a
    // draw lambda would invalidate the frame that is being drawn.
    val bandLevel = remember { floatArrayOf(0f, 0f, 0f) }
    val heroPulse = remember { floatArrayOf(0f) }
    val lastDraw = remember { longArrayOf(0L) }
    val recentHits = remember { LongArray(10) }
    val recentIdx = remember { intArrayOf(0) }
    val blinkAt = remember { longArrayOf(0L) }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { nowNanos = it }
    }

    // Milestones. The count restarts with every session, so a drop resets the watermark;
    // the initial watermark is the current count, so reopening the app mid-session does not
    // fire a milestone that was already passed.
    LaunchedEffect(tapCount) {
        val m = tapCount / HERO_EVERY_TAPS
        if (m < lastMilestone) lastMilestone = m
        if (m > lastMilestone) {
            lastMilestone = m
            if (m > 0) {
                heroStartNanos = nowNanos
                heroMilestone = m
            }
        }
    }

    LaunchedEffect(pulse) {
        val p = pulse ?: return@LaunchedEffect
        val strength = p.strength.coerceIn(0.15f, 1f)
        waves.add(Wave(nowNanos, strength, p.accent))
        lastHitNanos = nowNanos
        val b = p.band.coerceIn(0, 2)
        bandLevel[b] = min(1f, bandLevel[b] + strength)
        heroPulse[0] = min(1f, heroPulse[0] + strength * 0.6f)

        // Dense playing closes the eyes, as the reference does mid-solo: seven hits inside
        // a second and a half, held for a little over a second after it thins out.
        recentHits[recentIdx[0]] = nowNanos
        recentIdx[0] = (recentIdx[0] + 1) % recentHits.size
        val dense = recentHits.count { it != 0L && nowNanos - it < 1_500_000_000L }
        if (dense >= 7) shredUntilNanos = nowNanos + 1_200_000_000L

        if (p.accent || strength > 0.55f) {
            strumFrame = !strumFrame
            lane = (lane + 1) % 3
            notes.add(Note(nowNanos, lane, if (lane % 2 == 0) 1f else -1f, fromHead = lane == 1))
            squashNanos = nowNanos
            accentCount += 1
            // Every sixth accent throws a lean, alternating sides, never back to back.
            if (accentCount % 6 == 0 && nowNanos - leanStartNanos > 1_200_000_000L) {
                leanStartNanos = nowNanos
                leanDir = if ((accentCount / 6) % 2 == 0) -1f else 1f
            }
        }
        if ((strength > 0.85f || (p.accent && strength > 0.75f)) && nowNanos > sparkReadyNanos) {
            sparks.add(Spark(nowNanos, accentCount))
            sparkReadyNanos = nowNanos + 1_500_000_000L
            flashNanos = nowNanos
        }

        while (waves.size > 24) waves.removeAt(0)
        waves.removeAll { ageMs(nowNanos, it.startNanos) > WAVE_LIFE_MS }
        notes.removeAll { ageMs(nowNanos, it.startNanos) > NOTE_LIFE_MS }
        sparks.removeAll { ageMs(nowNanos, it.startNanos) > SPARK_LIFE_MS }
    }

    // Driven by a timer rather than by comparing against nowNanos here: reading the frame
    // clock during composition would recompose this whole widget every frame instead of
    // merely redrawing the canvas.
    LaunchedEffect(heroStartNanos) {
        if (heroStartNanos != 0L) {
            heroActive = true
            delay(HERO_DURATION_NS / 1_000_000L)
            heroActive = false
        }
    }

    val classic = palette == BandPalette.CLASSIC
    val hero = heroActive
    val shownTitle = if (hero) "HERO X · ${heroMilestone}K taps" else title

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

            // Time-based decay, so it runs at the same speed on 60, 90 and 120 Hz screens.
            val dt = if (lastDraw[0] == 0L) 0f
            else ((nowNanos - lastDraw[0]) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastDraw[0] = nowNanos
            for (i in 0 until 3) bandLevel[i] = max(0f, bandLevel[i] - dt * 2.7f)
            heroPulse[0] = max(0f, heroPulse[0] - dt * 2.2f)

            if (hero) {
                drawRect(HERO_BG, size = size)
            } else {
                drawRect(Brush.horizontalGradient(listOf(STANDARD_DEEP, STANDARD_SHALLOW)), size = size)
            }

            val pc = h * 0.72f / SPRITE_SIZE
            val spriteW = SPRITE_SIZE * pc
            val eqWidth = cell * (EQ_COLUMNS * 1.6f)
            val spriteLeft = w - spriteW - eqWidth - cell * 0.5f
            val spriteBottom = h - pc * 1.2f
            val centerX = spriteLeft + spriteW / 2f
            val centerY = spriteBottom - spriteW * 0.55f
            val ocx = centerX / cell
            val ocy = centerY / cell

            when {
                hero -> drawShards(
                    tSec, cols, rows, cell, ocx, ocy, heroPulse[0],
                    heroAgeMs = (nowNanos - heroStartNanos) / 1_000_000f,
                )

                classic -> drawClassicWaves(waves, nowNanos, cols, rows, cell, ocx)
                else -> drawRings(waves, nowNanos, cols, rows, cell, ocx, ocy, level, tSec)
            }

            drawColumns(bandLevel, w, h, cell, eqWidth, tSec, hero)

            val hitAge = ageMs(nowNanos, lastHitNanos)
            val hit = if (lastHitNanos == 0L) 0f else max(0f, 1f - hitAge / 260f)
            val rowDx = FloatArray(SPRITE_SIZE)
            val rowDy = FloatArray(SPRITE_SIZE)

            if (hero) {
                // Hero X strikes the pose from the reference: a small bounce on every hit,
                // the raised arm pumping, and a glint sweeping the glasses.
                val sway = sin(tSec * 1.3f) * pc * 0.3f
                for (y in 0 until SPRITE_SIZE) {
                    rowDx[y] = sway * (1f - y / 16f)
                    rowDy[y] = -hit * pc * 0.6f
                }
                val glintT = (tSec % 1.2f) / 0.18f
                drawFigure(
                    look = HERO_X_LOOK, inst = null,
                    left = spriteLeft, bottom = spriteBottom, pc = pc,
                    scaleX = 1f, scaleY = 1f, rowDx = rowDx, rowDy = rowDy,
                    legLiftL = 0f, legLiftR = 0f, instShearPx = 0f,
                    eyesClosed = false, eyeSpread = 0f, armOut = 0f, armDir = 0f,
                    heroArmLift = hit * pc * 1.4f,
                    glint = if (glintT <= 1f) glintT else -1f,
                )
            } else {
                // Blink roughly every three seconds for ~130 ms.
                if (nowNanos - blinkAt[0] > 3_000_000_000L) blinkAt[0] = nowNanos
                val blinking = nowNanos - blinkAt[0] < 130_000_000L

                val breathe = sin(tSec * 2.2f) * pc * 0.16f
                val sway = sin(tSec * 1.7f) * pc * 0.35f
                val headNod = hit * pc * 1.2f + sin(tSec * 3.1f) * pc * 0.2f

                var scaleX = 1f
                var scaleY = 1f
                var eyeSpread = 0f
                var armOut = 0f
                var legL = 0f
                var legR = 0f
                val lean: Float

                if (enhanced) {
                    val sqAge = ageMs(nowNanos, squashNanos)
                    val sq = if (sqAge < 200f) (1f - sqAge / 200f).let { it * it } * 0.20f else 0f
                    scaleX = 1f + sq
                    scaleY = 1f - sq * 0.75f
                    eyeSpread = sq / 0.20f * pc * 1.2f

                    val leanAge = ageMs(nowNanos, leanStartNanos)
                    lean = if (leanAge < 800f) sin(PI.toFloat() * leanAge / 800f) else 0f
                    armOut = lean

                    // Stomp alternating feet on the beat.
                    legL = if (strumFrame) hit * pc * 0.9f else 0f
                    legR = if (!strumFrame) hit * pc * 0.9f else 0f
                } else {
                    lean = 0f
                }

                for (y in 0 until SPRITE_SIZE) {
                    val part = when {
                        y <= HEAD_LAST_ROW -> sway * 1.2f
                        y >= LEG_FIRST_ROW -> -sway * 0.4f
                        else -> sway * 0.6f
                    }
                    // A lean shears the figure: the head travels furthest, the feet stay put.
                    rowDx[y] = part + leanDir * lean * (1f - y / 15f) * 2.4f * pc
                    rowDy[y] = breathe - hit * pc * 0.9f +
                        if (y <= HEAD_LAST_ROW) headNod else 0f
                }

                drawFigure(
                    look = character.look,
                    inst = if (strumFrame) instrument.frameB else instrument.frameA,
                    left = spriteLeft, bottom = spriteBottom, pc = pc,
                    scaleX = scaleX, scaleY = scaleY, rowDx = rowDx, rowDy = rowDy,
                    legLiftL = legL, legLiftR = legR,
                    instShearPx = (if (strumFrame) 1f else -1f) * hit * pc * 0.5f,
                    eyesClosed = blinking || (enhanced && nowNanos < shredUntilNanos),
                    eyeSpread = eyeSpread, armOut = armOut, armDir = leanDir,
                    heroArmLift = 0f, glint = -1f,
                )

                if (enhanced) drawSparks(sparks, nowNanos, centerX + pc * 2f, centerY + pc * 2f, pc)
            }

            drawNotes(notes, nowNanos, spriteLeft, spriteW, centerY, pc, h, hero)

            // Entrance flash for Hero X; a softer one for power chords.
            val heroAge = (nowNanos - heroStartNanos) / 1_000_000f
            if (hero && heroAge < 300f) {
                drawRect(Color.White.copy(alpha = 0.7f * (1f - heroAge / 300f)), size = size)
            }
            val flashAge = ageMs(nowNanos, flashNanos)
            if (!hero && enhanced && flashAge < 140f) {
                drawRect(Color.White.copy(alpha = 0.18f * (1f - flashAge / 140f)), size = size)
            }
        }

        Text(
            text = shownTitle,
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

/** Rings from the character outward: a solid disc at birth that hollows as it spreads. */
private fun DrawScope.drawRings(
    waves: List<Wave>, now: Long, cols: Int, rows: Int, cell: Float,
    ocx: Float, ocy: Float, level: Float, tSec: Float,
) {
    val far = max(max(ocx, cols - ocx), 1f)
    val tall = max(max(ocy, rows - ocy), 1f)
    val maxR = sqrt(far * far + tall * tall)
    for (cx in 0 until cols) {
        for (cy in 0 until rows) {
            val dx = cx + 0.5f - ocx
            val dy = cy + 0.5f - ocy
            val r = sqrt(dx * dx + dy * dy)
            var v = 0.04f + 0.04f * sin(tSec * 1.1f + cx * 0.35f) + level * 0.08f
            for (wv in waves) {
                val age = ageMs(now, wv.startNanos)
                if (age > WAVE_LIFE_MS) continue
                val t = age / WAVE_LIFE_MS
                val front = t * maxR * 1.05f
                val width = 1.1f + t * 3.2f
                val d = (r - front) / width
                val fade = (1f - t) * (0.5f + 0.5f * (1f - t))
                v += exp(-d * d) * wv.strength * fade * (if (wv.accent) 1.2f else 0.85f)
                if (t < 0.3f && r < front) v += (1f - t / 0.3f) * 0.35f * wv.strength
            }
            if (v <= 0.07f) continue
            drawRect(
                color = STANDARD_LIT.copy(alpha = min(1f, v) * 0.85f),
                topLeft = Offset(cx * cell + 0.6f, cy * cell + 0.6f),
                size = Size(cell - 1.2f, cell - 1.2f),
            )
        }
    }
}

/** The first implementation, kept as the Classic style: grid always on, narrow sweep. */
private fun DrawScope.drawClassicWaves(
    waves: List<Wave>, now: Long, cols: Int, rows: Int, cell: Float, ocx: Float,
) {
    val base = Color(0xFF3A2A66)
    for (cx in 0 until cols) {
        val dist = ocx - cx
        var lit = 0f
        if (dist >= -1f) {
            for (wv in waves) {
                val age = ageMs(now, wv.startNanos)
                if (age > CLASSIC_WAVE_LIFE_MS) continue
                val t = age / CLASSIC_WAVE_LIFE_MS
                val d = dist - t * (cols * 1.15f)
                val width = if (wv.accent) 2.6f else 1.6f
                lit += exp(-(d * d) / (width * width)) * wv.strength * (1f - t)
            }
        }
        val a = min(1f, lit)
        for (cy in 0 until rows) {
            drawRect(
                color = if (a <= 0.01f) base else base.lerpTo(STANDARD_LIT, a),
                topLeft = Offset(cx * cell, cy * cell),
                size = Size(cell - 1f, cell - 1f),
            )
        }
    }
}

/**
 * Hero X's backdrop: neon shards bursting from him, after the reference art. Pixelated onto
 * the same grid as the rings so it still belongs to the band, rotating slowly, and kicked
 * longer by every hit.
 */
private fun DrawScope.drawShards(
    tSec: Float, cols: Int, rows: Int, cell: Float,
    ocx: Float, ocy: Float, pulse: Float, heroAgeMs: Float,
) {
    val n = 12
    val sector = TWO_PI / n
    val rot = tSec * 0.35f
    val far = max(max(ocx, cols - ocx), 1f)
    val tall = max(max(ocy, rows - ocy), 1f)
    val maxR = sqrt(far * far + tall * tall)
    val grow = min(1f, heroAgeMs / 350f)

    for (cx in 0 until cols) {
        for (cy in 0 until rows) {
            val dx = cx + 0.5f - ocx
            val dy = cy + 0.5f - ocy
            val r = sqrt(dx * dx + dy * dy)
            if (r < 0.8f) continue
            val ang = atan2(dy, dx) - rot
            val k = (ang / sector).roundToInt()
            val diff = ang - k * sector
            val idx = ((k % n) + n) % n
            val phase = sin(tSec * 2.3f + idx * 1.7f) * 0.5f + 0.5f
            val len = maxR * grow * (0.45f + 0.30f * phase + 0.35f * pulse) *
                (if (idx % 3 == 0) 1.15f else 0.9f)
            if (r > len) continue
            // 0.17 rad at the base against a 0.52 rad sector leaves black between the
            // shards; at 0.30 neighbours overlapped and the burst read as a solid wheel.
            val half = 0.17f * (1f - r / len) + 0.015f
            if (abs(diff) > half) continue
            val color = HERO_NEON[idx % HERO_NEON.size]
            drawRect(
                color = color.copy(alpha = 0.55f + 0.45f * (1f - r / len)),
                topLeft = Offset(cx * cell + 0.5f, cy * cell + 0.5f),
                size = Size(cell - 1f, cell - 1f),
            )
        }
    }

    // Chromatic glitch lines on strong hits, like the torn edges in the reference.
    if (pulse > 0.35f) {
        val row = ((tSec * 37f).toInt() % max(rows, 1))
        drawRect(
            color = HERO_NEON[2].copy(alpha = 0.5f * pulse),
            topLeft = Offset(0f, row * cell),
            size = Size(size.width, cell * 0.35f),
        )
        drawRect(
            color = HERO_NEON[1].copy(alpha = 0.5f * pulse),
            topLeft = Offset(cell * 0.6f, row * cell + cell * 0.35f),
            size = Size(size.width, cell * 0.25f),
        )
    }
}

/** Five columns right of the character, interpolated from the three bands. */
private fun DrawScope.drawColumns(
    bandLevel: FloatArray, w: Float, h: Float, cell: Float, eqWidth: Float,
    tSec: Float, hero: Boolean,
) {
    val eqLeft = w - eqWidth
    val colW = eqWidth / EQ_COLUMNS
    for (i in 0 until EQ_COLUMNS) {
        val bandPos = i * 2f / (EQ_COLUMNS - 1)
        val b0 = bandPos.toInt().coerceIn(0, 2)
        val b1 = (b0 + 1).coerceAtMost(2)
        val mix = bandPos - b0
        val v = bandLevel[b0] * (1f - mix) + bandLevel[b1] * mix
        val wobble = 0.10f + 0.06f * sin(tSec * 5f + i * 1.3f)
        val cellsHigh = max(1, (((v * 0.72f + wobble) * h) / cell).toInt())
        val color = if (hero) HERO_NEON[i % HERO_NEON.size] else STANDARD_LIT
        for (k in 0 until cellsHigh) {
            drawRect(
                color = color.copy(alpha = 0.30f + 0.55f * (1f - k.toFloat() / cellsHigh)),
                topLeft = Offset(eqLeft + i * colW + colW * 0.15f, h - (k + 1) * cell + 0.6f),
                size = Size(colW * 0.7f, cell - 1.2f),
            )
        }
    }
}

/**
 * Draws one figure from a sprite, scaled about its feet, with per-row offsets.
 *
 * Eyes are drawn last and separately rather than from the sprite, because they carry most of
 * the expression: open as square dots, shut as flat dashes, pushed apart when the body
 * squashes. The same routine draws Hero X, whose raised arm can pump independently.
 */
private fun DrawScope.drawFigure(
    look: SpriteLook,
    inst: List<String>?,
    left: Float, bottom: Float, pc: Float,
    scaleX: Float, scaleY: Float,
    rowDx: FloatArray, rowDy: FloatArray,
    legLiftL: Float, legLiftR: Float,
    instShearPx: Float,
    eyesClosed: Boolean, eyeSpread: Float,
    armOut: Float, armDir: Float,
    heroArmLift: Float,
    glint: Float,
) {
    val ax = left + SPRITE_SIZE / 2f * pc
    val cw = pc * scaleX
    val ch = pc * scaleY
    fun px(x: Int, y: Int) = ax + (x - SPRITE_SIZE / 2f) * cw + rowDx[y]
    fun py(y: Int) = bottom + (y - SPRITE_SIZE) * ch + rowDy[y]

    val eyeX = ArrayList<Float>(4)
    val eyeY = ArrayList<Float>(4)

    for (y in 0 until SPRITE_SIZE) {
        for (x in 0 until SPRITE_SIZE) {
            val instCh = if (inst != null) spritePixel(inst, x, y) else '.'
            val isInst = instCh != '.'
            val c = if (isInst) instCh else spritePixel(look.rows, x, y)
            if (c == '.') continue

            val isEye = !isInst && (c == 'E' || (c == '5' && y <= HEAD_LAST_ROW))
            var dx = 0f
            var dy = 0f
            if (isInst) dx += instShearPx * (11 - y) / 6f
            if (y >= LEG_FIRST_ROW) dy -= if (x < SPRITE_SIZE / 2) legLiftL else legLiftR
            if (heroArmLift != 0f && x >= 10 && y <= 6) dy -= heroArmLift

            if (isEye) {
                eyeX += px(x, y) + dx
                eyeY += py(y) + dy
                continue
            }
            val color = look.color(c) ?: continue
            drawRect(
                color = color,
                topLeft = Offset(px(x, y) + dx, py(y) + dy),
                size = Size(cw + 0.6f, ch + 0.6f),
            )
        }
    }

    // The free arm flung out during a lean, from the torso edge outwards.
    if (armOut > 0.05f && inst != null) {
        val row = look.rows.getOrNull(9) ?: ""
        val first = row.indexOfFirst { it != '.' }
        val last = row.indexOfLast { it != '.' }
        if (first >= 0) {
            val edge = if (armDir < 0f) first else last
            val reach = (armOut * 3f).roundToInt()
            for (i in 1..reach) {
                val x = edge + i * (if (armDir < 0f) -1 else 1)
                val color = if (i == reach) look.skin else look.jacket
                drawRect(
                    color = color,
                    topLeft = Offset(px(x, 9), py(9)),
                    size = Size(cw + 0.6f, ch + 0.6f),
                )
            }
        }
    }

    if (eyeX.isNotEmpty()) {
        for (i in eyeX.indices) {
            val side = if (i < eyeX.size / 2) -1f else 1f
            val ex = eyeX[i] + side * eyeSpread
            val ey = eyeY[i]
            if (eyesClosed) {
                drawRect(
                    color = look.eye,
                    topLeft = Offset(ex - cw * 0.25f, ey + ch * 0.35f),
                    size = Size(cw * 1.5f, ch * 0.4f),
                )
            } else {
                drawRect(color = look.eye, topLeft = Offset(ex, ey), size = Size(cw + 0.6f, ch + 0.6f))
            }
        }
        // A glint sweeping across Hero X's glasses.
        if (glint in 0f..1f) {
            val gi = (glint * (eyeX.size - 1)).roundToInt().coerceIn(0, eyeX.size - 1)
            drawRect(
                color = Color.White,
                topLeft = Offset(eyeX[gi] + cw * 0.2f, eyeY[gi] + ch * 0.2f),
                size = Size(cw * 0.6f, ch * 0.6f),
            )
        }
    }
}

/** Lightning off the instrument on a power chord: zigzag bolts thrown outward. */
private fun DrawScope.drawSparks(sparks: List<Spark>, now: Long, cx: Float, cy: Float, pc: Float) {
    for (s in sparks) {
        val age = ageMs(now, s.startNanos)
        if (age > SPARK_LIFE_MS) continue
        val t = age / SPARK_LIFE_MS
        val alpha = 1f - t
        for (b in 0 until 7) {
            val ang = b / 7f * TWO_PI + s.seed * 0.37f
            val reach = pc * (3f + 7f * t)
            for (seg in 0 until 4) {
                val along = pc * 1.6f + reach * seg / 4f
                val zig = (if (seg % 2 == 0) 0.7f else -0.7f) * pc
                val x = cx + cos(ang) * along - sin(ang) * zig
                val y = cy + sin(ang) * along + cos(ang) * zig
                drawRect(
                    color = (if (seg == 0) Color.White else SPARK).copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(pc * 0.9f, pc * 0.9f),
                )
            }
        }
    }
}

/** Notes off the instrument to the right, and rising from the head, as in the reference. */
private fun DrawScope.drawNotes(
    notes: List<Note>, now: Long, spriteLeft: Float, spriteW: Float,
    centerY: Float, pc: Float, h: Float, hero: Boolean,
) {
    for (n in notes) {
        val age = ageMs(now, n.startNanos)
        if (age > NOTE_LIFE_MS) continue
        val t = age / NOTE_LIFE_MS
        val alpha = (1f - t).coerceIn(0f, 1f)
        val color = (if (hero) HERO_NEON[n.lane % HERO_NEON.size] else SPARK).copy(alpha = alpha)
        val px: Float
        val py: Float
        if (n.fromHead) {
            px = spriteLeft + spriteW * 0.35f + sin(t * 5f + n.lane) * pc * 1.2f
            py = centerY - spriteW * 0.55f - t * h * 0.45f
        } else {
            px = spriteLeft + spriteW * 0.7f + t * pc * 5f
            py = centerY - pc + sin(t * 6f + n.lane) * pc * 1.6f * n.drift - t * h * 0.25f
        }
        drawRect(color, Offset(px, py + pc * 1.6f), Size(pc * 1.8f, pc * 1.2f))
        drawRect(color, Offset(px + pc * 1.4f, py), Size(pc * 0.5f, pc * 1.9f))
    }
}

private fun Color.lerpTo(other: Color, t: Float): Color = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = 1f,
)
