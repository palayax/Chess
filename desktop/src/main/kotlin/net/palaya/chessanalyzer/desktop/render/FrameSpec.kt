package net.palaya.chessanalyzer.desktop.render

import java.awt.Color as AwtColor

/**
 * Everything one frame shows. A data class on purpose: two equal specs render identical pixels,
 * so the render stage compares a frame's spec with the previous one and re-sends the previous
 * buffer instead of drawing (design §8.2, "render only at dirty times").
 */
data class FrameSpec(
    val card: CardSpec? = null,
    /** FEN placement field only (what is on the squares). */
    val placement: String = "",
    val whiteAtBottom: Boolean = true,
    val lastMoveFrom: Int? = null,
    val lastMoveTo: Int? = null,
    val highlights: List<Int> = emptyList(),
    val checkSquare: Int? = null,
    val arrows: List<ArrowSpec> = emptyList(),
    /** A piece in flight: FEN char, from/to square index, eased progress quantised to 1/1000. */
    val animPiece: Char? = null,
    val animFrom: Int = 0,
    val animTo: Int = 0,
    val animProgressMilli: Int = 0,
    /** Classification badge on a square (after a move settles). */
    val badgeSquare: Int? = null,
    val badgeClassification: String? = null,
    val excursion: Boolean = false,
    val excursionLabel: String? = null,
    /** 0..100, White's win percent. */
    val evalWinWhite: Double = 50.0,
    val evalText: String = "",
    // Right panel.
    val topName: String = "",
    val bottomName: String = "",
    val chapter: String = "",
    val moveLabel: String = "",
    val classification: String? = null,
    val beatCaption: String = "",
    // Bottom caption bar: the spoken line being heard.
    val subtitle: String = "",
)

data class CardSpec(val heading: String, val lines: List<String>, val subLines: List<String>, val subtitle: String)

data class ArrowSpec(val from: Int, val to: Int, val role: String)

/** Colours: board/chrome from the app's `BoardFrameRenderer.kt:177-210`, arrows per VIDEO_FORMAT. */
object Palette {
    val BOARD_LIGHT = AwtColor(0xEB, 0xEC, 0xD0)
    val BOARD_DARK = AwtColor(0x73, 0x95, 0x52)
    val LAST_MOVE = AwtColor(0xBA, 0xCA, 0x44, 0x99)
    val HIGHLIGHT = AwtColor(0xF7, 0xF7, 0x69, 0x99)
    val CHECK_RED = AwtColor(0xEE, 0x3B, 0x3B)
    val CHROME_DARK = AwtColor(0x30, 0x2E, 0x2B)
    val SURFACE_DARK = AwtColor(0x26, 0x24, 0x21)
    val ON_DARK = AwtColor(0xFA, 0xF9, 0xF6)
    val ON_DARK_2 = AwtColor(0xB8, 0xB5, 0xAE)
    val CAPTION_BG = AwtColor(0x1E, 0x1D, 0x1B, 0xE6)
    val EXCURSION = AwtColor(0xC7, 0x7D, 0xFF)
    val WHITE_FILL = AwtColor(0xF7, 0xF5, 0xEF)
    val WHITE_STROKE = AwtColor(0x23, 0x22, 0x1F)
    val BLACK_FILL = AwtColor(0x1A, 0x19, 0x17)
    val BLACK_STROKE = AwtColor(0xF2, 0xF1, 0xEC)
    val EVAL_WHITE = AwtColor(0xF2, 0xF1, 0xEC)
    val EVAL_BLACK = AwtColor(0x1A, 0x19, 0x17)

    fun classification(name: String?): AwtColor = when (name) {
        "BRILLIANT" -> AwtColor(0x26, 0xC2, 0xA3)
        "GREAT" -> AwtColor(0x74, 0x9B, 0xBF)
        "BEST", "EXCELLENT" -> AwtColor(0x81, 0xB6, 0x4C)
        "GOOD" -> AwtColor(0x95, 0xB7, 0x76)
        "BOOK" -> AwtColor(0xA8, 0x88, 0x65)
        "INACCURACY" -> AwtColor(0xF7, 0xC6, 0x31)
        "MISTAKE" -> AwtColor(0xE5, 0x8F, 0x2A)
        "MISS" -> AwtColor(0xFF, 0x77, 0x69)
        "BLUNDER" -> AwtColor(0xFA, 0x41, 0x2D)
        else -> AwtColor(0x9E, 0x9E, 0x9E)
    }

    fun glyph(name: String?): String = when (name) {
        "BRILLIANT" -> "!!"
        "GREAT" -> "!"
        "BEST" -> "★"
        "EXCELLENT" -> "✓"
        "GOOD" -> "✓"
        "BOOK" -> "B"
        "INACCURACY" -> "?!"
        "MISTAKE" -> "?"
        "MISS" -> "×"
        "BLUNDER" -> "??"
        "FORCED" -> "→"
        else -> ""
    }

    /**
     * VIDEO_FORMAT's mapping (green = best, red = threat, blue = idea/support, yellow = played),
     * which differs from the app's (green played, blue best) on purpose; design §8.3.
     */
    fun arrow(role: String): AwtColor = when (role) {
        "BEST" -> AwtColor(0x81, 0xB6, 0x4C, 0xE6)
        "THREAT" -> AwtColor(0xEE, 0x3B, 0x3B, 0xE6)
        "PLAYED" -> AwtColor(0xF7, 0xC6, 0x31, 0xE6)
        else -> AwtColor(0x61, 0xAF, 0xEF, 0xE6)
    }
}
