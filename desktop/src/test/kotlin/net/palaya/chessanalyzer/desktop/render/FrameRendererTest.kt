package net.palaya.chessanalyzer.desktop.render

import net.palaya.chessanalyzer.core.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

/** Pixel checks on one rendered frame (design §13 FrameRendererTest). */
class FrameRendererTest {

    private fun rgb(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y) and 0xFFFFFF

    private fun sq(a: String) = Square.fromAlgebraic(a).index

    private val spec = FrameSpec(
        placement = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR",
        evalWinWhite = 75.0,
        evalText = "+1.2",
        arrows = listOf(ArrowSpec(sq("g1"), sq("f3"), "BEST")),
        badgeSquare = sq("e4"),
        badgeClassification = "BLUNDER",
        topName = "Black", bottomName = "White",
        subtitle = "Knight to f three.",
    )

    @Test
    fun boardEvalBarArrowBadgeAndReuse() {
        val r = FrameRenderer()
        val img = r.render(spec)
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, img.type)
        assertEquals(1920 * 1080 * 3, r.bytes(img).size)

        // Empty squares, sampled at the centre: a3 is dark, h3 is light (a1 is dark, but holds a rook).
        val (a3x, a3y) = r.squareRect(sq("a3"), true)
        assertEquals(0x739552, rgb(img, a3x + 45, a3y + 45))
        val (h3x, h3y) = r.squareRect(sq("h3"), true)
        assertEquals(0xEBECD0, rgb(img, h3x + 45, h3y + 45))
        // The board's corner: a1's square is at bottom-left with White at the bottom.
        assertEquals(FrameRenderer.BOARD_X to FrameRenderer.BOARD_Y + 7 * FrameRenderer.SQ, r.squareRect(sq("a1"), true))

        // Eval bar: the white fill is winPercent x bar height from the bottom (±2 px).
        val barX = FrameRenderer.EVAL_X + FrameRenderer.EVAL_W / 2
        val boundary = (FrameRenderer.BOARD_Y until FrameRenderer.BOARD_Y + FrameRenderer.BOARD).first { y ->
            rgb(img, barX, y) == 0xF2F1EC
        }
        val whiteH = FrameRenderer.BOARD_Y + FrameRenderer.BOARD - boundary
        assertEquals(0.75 * FrameRenderer.BOARD, whiteH.toDouble(), 2.0)

        // Arrow pixel at the g1→f3 midpoint is the BEST green (alpha-blended over the board).
        val (gx, gy) = r.squareRect(sq("g1"), true)
        val (fx, fy) = r.squareRect(sq("f3"), true)
        val mid = rgb(img, (gx + fx) / 2 + 45, (gy + fy) / 2 + 45)
        val (mr, mg, mb) = Triple(mid shr 16 and 0xFF, mid shr 8 and 0xFF, mid and 0xFF)
        assertTrue("arrow midpoint is green-dominant: #${Integer.toHexString(mid)}", mg > mr && mg > mb && mg in 150..200)

        // Badge colour at the destination square's top-right corner (inside the disc, below the glyph).
        val (ex, ey) = r.squareRect(sq("e4"), true)
        val badge = rgb(img, ex + FrameRenderer.SQ - 17, ey + 17 + 14)
        assertEquals("blunder red", 0xFA412D, badge)

        // Reuse: identical spec → same instance; a caption change → a different buffer.
        assertSame(img, r.render(spec.copy()))
        val changed = r.render(spec.copy(subtitle = "Something else."))
        assertNotSame(img, changed)
        assertEquals(2, r.framesDrawn)
    }

    @Test
    fun flippedBoardPutsA1TopRight() {
        val r = FrameRenderer()
        assertEquals(FrameRenderer.BOARD_X + 7 * FrameRenderer.SQ to FrameRenderer.BOARD_Y, r.squareRect(sq("a1"), false))
        val img = r.render(spec.copy(whiteAtBottom = false, arrows = emptyList(), badgeSquare = null))
        val (a3x, a3y) = r.squareRect(sq("a3"), false)
        assertEquals(0x739552, rgb(img, a3x + 45, a3y + 45))
    }
}
