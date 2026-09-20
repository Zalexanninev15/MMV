package io.github.zalexanninev15.magicmusicv.ui

import androidx.compose.ui.graphics.Color

/**
 * Sprite data for the beat visualiser.
 *
 * Each sprite is 16 rows of 16 characters, one character per pixel, indexing [palette].
 * Kept as text on purpose: a 16x16 grid is legible and editable in the source, and it
 * avoids shipping bitmap assets for something that has to be recoloured per theme anyway.
 *
 * Instruments are separate overlays drawn on the same 16x16 grid as the body, so the two
 * always line up without anchor maths. Each has two frames; the strum alternates on beats.
 */
private const val TRANSPARENT = '.'

/** Shared palette. Characters override hair, skin and jacket. */
fun spriteColor(
    ch: Char,
    hair: Color,
    skin: Color,
    jacket: Color,
    jacketShade: Color,
    eye: Color = Color(0xFF14100F),
): Color? = when (ch) {
    'E' -> eye
    '1' -> hair
    '2' -> skin
    '3' -> jacket
    '4' -> jacketShade
    '5' -> Color(0xFF14100F)
    '9' -> Color(0xFFFFD75E)
    '6' -> Color(0xFF2B2B33)   // instrument body
    '7' -> Color(0xFFC08A4A)   // fretboard / wood
    '8' -> Color(0xFFD9D9E3)   // metal, strings, keys
    '9' -> Color(0xFFFFD75E)
    else -> null
}

/** Rows 0..7 are the head, 8..12 the torso, 13..15 the legs. Used for per-part animation. */
const val HEAD_LAST_ROW = 7
const val LEG_FIRST_ROW = 13

enum class PixelCharacter(
    val title: String,
    val hair: Color,
    val skin: Color,
    val jacket: Color,
    val jacketShade: Color,
    val rows: List<String>,
    /** Eye colour, and what to paint over the eyes mid-blink. */
    val eye: Color = Color(0xFF14100F),
    val eyeClosed: Color = skin,
) {
    ROADIE(
        "Roadie",
        hair = Color(0xFF2A1B12),
        skin = Color(0xFFE8A07A),
        jacket = Color(0xFFD8622F),
        jacketShade = Color(0xFF9B3F18),
        rows = listOf(
            "................",
            "....111111......",
            "...11111111.....",
            "...11222211.....",
            "...12222221.....",
            "...15225221.....",
            "...12222221.....",
            "....122221......",
            "....333333......",
            "...33333333.....",
            "..3333333333....",
            "..3333333333....",
            "...33333333.....",
            "...444..444.....",
            "...444..444.....",
            "...555..555.....",
        ),
    ),
    PUNK(
        "Punk",
        hair = Color(0xFF3FBF7F),
        skin = Color(0xFFF0C09A),
        jacket = Color(0xFF2E2E38),
        jacketShade = Color(0xFF1A1A22),
        rows = listOf(
            ".....1..1.......",
            "....111111......",
            "....111111......",
            "...11222211.....",
            "...12222221.....",
            "...15225221.....",
            "...12222221.....",
            "....122221......",
            "....333333......",
            "...33333333.....",
            "..3333333333....",
            "..3333333333....",
            "...33333333.....",
            "...444..444.....",
            "...444..444.....",
            "...555..555.....",
        ),
    ),
    BATMETAL(
        "Batmetal",
        hair = Color(0xFF1B1B22),
        skin = Color(0xFFD9A07A),
        jacket = Color(0xFF2A2A33),
        jacketShade = Color(0xFF16161C),
        rows = listOf(
            "................",
            "...1........1...",
            "...11......11...",
            "...1111111111...",
            "...1111111111...",
            "...1EE1..1EE1...",
            "...1222222221...",
            "....12222221....",
            "....333333......",
            "...33333333.....",
            "..3333333333....",
            "..3399999933....",
            "...33333333.....",
            "...444..444.....",
            "...444..444.....",
            "...555..555.....",
        ),
        eye = Color(0xFFE6E6F0),
        eyeClosed = Color(0xFF1B1B22),
    ),
    METALHEAD(
        "Metalhead",
        hair = Color(0xFFE8D48A),
        skin = Color(0xFFE0A882),
        jacket = Color(0xFF6B2A6B),
        jacketShade = Color(0xFF431A43),
        rows = listOf(
            "...111111111....",
            "..11111111111...",
            "..11122221111...",
            "..11222222111...",
            "..11522521111...",
            "..11222222111...",
            "..11122221111...",
            "...1122211......",
            "....333333......",
            "...33333333.....",
            "..3333333333....",
            "..3333333333....",
            "...33333333.....",
            "...444..444.....",
            "...444..444.....",
            "...555..555.....",
        ),
    ),
}

enum class PixelInstrument(
    val title: String,
    val frameA: List<String>,
    val frameB: List<String>,
) {
    GUITAR(
        "Guitar",
        frameA = listOf(
            "................", "................", "................",
            "................", "................", "................",
            "................", "..............8.",
            ".............88.", "...........887..",
            ".........6677...", "........66667...",
            "........66666...", ".........666....",
            "................", "................",
        ),
        frameB = listOf(
            "................", "................", "................",
            "................", "................", "................",
            "................", "..............8.",
            ".............88.", "...........887..",
            ".........6677...", "........66657...",
            "........66566...", ".........666....",
            "................", "................",
        ),
    ),
    BASS(
        "Bass",
        frameA = listOf(
            "................", "................", "................",
            "................", "................", "...............8",
            "..............8.", ".............8..",
            "............87..", "..........877...",
            ".........6677...", "........666667..",
            "........666666..", ".........6666...",
            "................", "................",
        ),
        frameB = listOf(
            "................", "................", "................",
            "................", "................", "...............8",
            "..............8.", ".............8..",
            "............87..", "..........877...",
            ".........6677...", "........666657..",
            "........665666..", ".........6666...",
            "................", "................",
        ),
    ),
    DRUMS(
        "Drums",
        frameA = listOf(
            "................", "................", "................",
            "................", "................", "................",
            "................", "................",
            "................", "...8........8...",
            "....8......8....", "................",
            "..6666..6666....", "..6688..8866....",
            "..5555..5555....", "................",
        ),
        frameB = listOf(
            "................", "................", "................",
            "................", "................", "................",
            "................", "................",
            "....8......8....", "................",
            "................", "..6666..6666....",
            "..6688..8866....", "..5555..5555....",
            "................", "................",
        ),
    ),
    KEYS(
        "Keys",
        frameA = listOf(
            "................", "................", "................",
            "................", "................", "................",
            "................", "................",
            "................", "................",
            "...888888888....", "...585858585....",
            "...888888888....", "...666666666....",
            "................", "................",
        ),
        frameB = listOf(
            "................", "................", "................",
            "................", "................", "................",
            "................", "................",
            "................", "................",
            "...888888888....", "...858585858....",
            "...888888888....", "...666666666....",
            "................", "................",
        ),
    ),
}

/**
 * Where the band takes its colours from.
 *
 * VIDEO is the fixed palette sampled out of the reference clip, so the band looks the same
 * on every device regardless of wallpaper or light/dark. THEME derives from the Material You
 * scheme and follows the rest of the app.
 */
enum class BandPalette(val title: String) {
    /** The palette sampled from the reference clip. Identical on every device. */
    STANDARD("Standard"),

    /** Derived from the Material You scheme, so the band follows the rest of the app. */
    THEME("Theme"),

    /** The first implementation: theme colours, grid always drawn, narrow fast waves. */
    CLASSIC("Classic"),
}

/** Sampled from the reference video: background gradient, and the colour of a lit cell. */
val STANDARD_DEEP = Color(0xFF27164C)
val STANDARD_SHALLOW = Color(0xFF4B4162)
val STANDARD_LIT = Color(0xFFF0EEF4)

const val SPRITE_SIZE = 16

fun spritePixel(rows: List<String>, x: Int, y: Int): Char {
    if (y !in rows.indices) return TRANSPARENT
    val row = rows[y]
    if (x !in row.indices) return TRANSPARENT
    return row[x]
}
