package net.palaya.chessanalyzer.desktop.render

import net.palaya.chessanalyzer.core.chess.Piece
import net.palaya.chessanalyzer.core.chess.PieceType
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Java2D compositor for one 1920x1080 frame (design §8). Headless, `TYPE_3BYTE_BGR` so the backing
 * bytes are exactly ffmpeg's `bgr24` (no conversion pass).
 *
 * Layout (§8.3): eval bar x 40 (36 wide), board 720x720 at (100,100), right panel x 860..1880,
 * caption bar y 850..1060 across x 40..1880.
 *
 * Reuse: [render] returns the previous image *instance* when the spec equals the previous spec;
 * otherwise it draws into the other of two buffers, so a caller can tell "unchanged" by identity.
 */
class FrameRenderer(val width: Int = 1920, val height: Int = 1080) {

    init {
        require(width == 1920 && height == 1080) { "P1 renders 1920x1080 only (got ${width}x$height)" }
        System.setProperty("java.awt.headless", "true")
    }

    private val buffers = Array(2) { BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR) }
    private var current = 0
    private var lastSpec: FrameSpec? = null
    var framesDrawn = 0
        private set

    private val sprites = HashMap<Char, BufferedImage>()

    fun render(spec: FrameSpec): BufferedImage {
        if (spec == lastSpec) return buffers[current]
        current = 1 - current
        val img = buffers[current]
        val g = img.createGraphics()
        try {
            hints(g)
            if (spec.card != null) drawCard(g, spec.card) else drawBoardFrame(g, spec)
        } finally {
            g.dispose()
        }
        lastSpec = spec
        framesDrawn++
        return img
    }

    /** The bgr24 bytes of [img] (its backing array, not a copy). */
    fun bytes(img: BufferedImage): ByteArray = (img.raster.dataBuffer as DataBufferByte).data

    // ---- geometry ---------------------------------------------------------------------------

    fun squareRect(square: Int, whiteAtBottom: Boolean): Pair<Int, Int> {
        val file = square and 7
        val rank = square shr 3
        val col = if (whiteAtBottom) file else 7 - file
        val row = if (whiteAtBottom) 7 - rank else rank
        return (BOARD_X + col * SQ) to (BOARD_Y + row * SQ)
    }

    private fun squareCenter(square: Int, whiteAtBottom: Boolean): Pair<Double, Double> {
        val (x, y) = squareRect(square, whiteAtBottom)
        return (x + SQ / 2.0) to (y + SQ / 2.0)
    }

    // ---- board frame ------------------------------------------------------------------------

    private fun drawBoardFrame(g: Graphics2D, s: FrameSpec) {
        g.color = Palette.CHROME_DARK
        g.fillRect(0, 0, width, height)
        drawEvalBar(g, s)
        drawBoard(g, s)
        drawPanel(g, s)
        drawCaptionBar(g, s.subtitle)
    }

    private fun drawEvalBar(g: Graphics2D, s: FrameSpec) {
        val whiteH = Math.round(s.evalWinWhite.coerceIn(0.0, 100.0) / 100.0 * BOARD).toInt()
        g.color = Palette.EVAL_BLACK
        g.fillRect(EVAL_X, BOARD_Y, EVAL_W, BOARD)
        g.color = Palette.EVAL_WHITE
        if (s.whiteAtBottom) g.fillRect(EVAL_X, BOARD_Y + BOARD - whiteH, EVAL_W, whiteH)
        else g.fillRect(EVAL_X, BOARD_Y, EVAL_W, whiteH)
        if (s.evalText.isNotEmpty()) {
            g.font = font(Font.BOLD, 13)
            val fm = g.fontMetrics
            val whiteAhead = s.evalWinWhite >= 50.0
            val atBottom = whiteAhead == s.whiteAtBottom
            g.color = if (whiteAhead) Palette.EVAL_BLACK else Palette.EVAL_WHITE
            val tx = EVAL_X + (EVAL_W - fm.stringWidth(s.evalText)) / 2
            val ty = if (atBottom) BOARD_Y + BOARD - 8 else BOARD_Y + fm.ascent + 6
            g.drawString(s.evalText, tx, ty)
        }
    }

    private fun drawBoard(g: Graphics2D, s: FrameSpec) {
        for (sq in 0 until 64) {
            val (x, y) = squareRect(sq, s.whiteAtBottom)
            g.color = if (((sq and 7) + (sq shr 3)) % 2 == 0) Palette.BOARD_DARK else Palette.BOARD_LIGHT
            g.fillRect(x, y, SQ, SQ)
        }
        listOfNotNull(s.lastMoveFrom, s.lastMoveTo).forEach { fillSquare(g, it, s.whiteAtBottom, Palette.LAST_MOVE) }
        s.highlights.forEach { fillSquare(g, it, s.whiteAtBottom, Palette.HIGHLIGHT) }
        s.checkSquare?.let { sq ->
            val (x, y) = squareRect(sq, s.whiteAtBottom)
            g.paint = java.awt.RadialGradientPaint(
                (x + SQ / 2).toFloat(), (y + SQ / 2).toFloat(), SQ * 0.6f,
                floatArrayOf(0f, 1f), arrayOf(Palette.CHECK_RED, Color(0xEE, 0x3B, 0x3B, 0)),
            )
            g.fillRect(x, y, SQ, SQ)
        }
        val board = parsePlacement(s.placement)
        for (sq in 0 until 64) {
            val c = board[sq] ?: continue
            if (s.animPiece != null && sq == s.animFrom) continue
            val (x, y) = squareRect(sq, s.whiteAtBottom)
            g.drawImage(sprite(c), x, y, null)
        }
        if (s.animPiece != null) {
            val (fx, fy) = squareRect(s.animFrom, s.whiteAtBottom)
            val (tx, ty) = squareRect(s.animTo, s.whiteAtBottom)
            val p = s.animProgressMilli / 1000.0
            g.drawImage(sprite(s.animPiece), Math.round(fx + (tx - fx) * p).toInt(), Math.round(fy + (ty - fy) * p).toInt(), null)
        }
        // After the pieces, so a rook on a1 does not hide the "1" and the "a".
        drawCoordinates(g, s.whiteAtBottom)
        s.arrows.forEach { drawArrow(g, it, s.whiteAtBottom) }
        if (s.badgeSquare != null && s.badgeClassification != null) drawBadge(g, s.badgeSquare, s.badgeClassification, s.whiteAtBottom)

        if (s.excursion) {
            g.color = Palette.EXCURSION
            g.stroke = BasicStroke(8f)
            g.drawRect(BOARD_X - 4, BOARD_Y - 4, BOARD + 8, BOARD + 8)
            s.excursionLabel?.let { label ->
                g.font = font(Font.BOLD, 26)
                val w = g.fontMetrics.stringWidth(label) + 28
                g.fill(RoundRectangle2D.Double(BOARD_X.toDouble(), BOARD_Y - 52.0, w.toDouble(), 40.0, 12.0, 12.0))
                g.color = Color.WHITE
                g.drawString(label, BOARD_X + 14, BOARD_Y - 22)
            }
        }
    }

    private fun fillSquare(g: Graphics2D, sq: Int, whiteAtBottom: Boolean, c: Color) {
        val (x, y) = squareRect(sq, whiteAtBottom)
        g.color = c
        g.fillRect(x, y, SQ, SQ)
    }

    private fun drawCoordinates(g: Graphics2D, whiteAtBottom: Boolean) {
        g.font = font(Font.BOLD, 17)
        val fm = g.fontMetrics
        for (i in 0 until 8) {
            // Rank numbers down the left edge.
            val rank = if (whiteAtBottom) 7 - i else i
            val leftSq = rank * 8 + (if (whiteAtBottom) 0 else 7)
            g.color = if (((leftSq and 7) + rank) % 2 == 0) Palette.BOARD_LIGHT else Palette.BOARD_DARK
            g.drawString("${rank + 1}", BOARD_X + 5, BOARD_Y + i * SQ + fm.ascent + 2)
            // File letters along the bottom edge.
            val file = if (whiteAtBottom) i else 7 - i
            val bottomRank = if (whiteAtBottom) 0 else 7
            g.color = if ((file + bottomRank) % 2 == 0) Palette.BOARD_LIGHT else Palette.BOARD_DARK
            val letter = ('a' + file).toString()
            g.drawString(letter, BOARD_X + i * SQ + SQ - fm.stringWidth(letter) - 5, BOARD_Y + BOARD - 5)
        }
    }

    private fun drawArrow(g: Graphics2D, a: ArrowSpec, whiteAtBottom: Boolean) {
        val (sx, sy) = squareCenter(a.from, whiteAtBottom)
        val (ex, ey) = squareCenter(a.to, whiteAtBottom)
        val angle = atan2(ey - sy, ex - sx)
        val head = SQ * 0.42
        val shaftEndX = ex - cos(angle) * head * 0.8
        val shaftEndY = ey - sin(angle) * head * 0.8
        val color = Palette.arrow(a.role)
        g.color = color
        g.stroke = BasicStroke((SQ * 0.18).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Double(sx, sy, shaftEndX, shaftEndY))
        val spread = Math.toRadians(30.0)
        val tipX = ex - cos(angle) * SQ * 0.12
        val tipY = ey - sin(angle) * SQ * 0.12
        val p = Path2D.Double()
        p.moveTo(tipX, tipY)
        p.lineTo(tipX - cos(angle - spread) * head, tipY - sin(angle - spread) * head)
        p.lineTo(tipX - cos(angle + spread) * head, tipY - sin(angle + spread) * head)
        p.closePath()
        g.fill(p)
    }

    private fun drawBadge(g: Graphics2D, sq: Int, cls: String, whiteAtBottom: Boolean) {
        val (x, y) = squareRect(sq, whiteAtBottom)
        val r = 21.0
        val cx = x + SQ - r + 4
        val cy = y + r - 4
        g.color = Color(0, 0, 0, 90)
        g.fill(Ellipse2D.Double(cx - r + 1, cy - r + 2, 2 * r, 2 * r))
        g.color = Palette.classification(cls)
        g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.5f)
        g.draw(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
        val glyph = Palette.glyph(cls)
        g.font = font(Font.BOLD, if (glyph.length > 1) 19 else 23)
        val fm = g.fontMetrics
        g.drawString(glyph, (cx - fm.stringWidth(glyph) / 2.0).toFloat(), (cy + fm.ascent / 2.0 - 3).toFloat())
    }

    // ---- panel --------------------------------------------------------------------------------

    private fun drawPanel(g: Graphics2D, s: FrameSpec) {
        g.color = Palette.SURFACE_DARK
        g.fill(RoundRectangle2D.Double(PANEL_X.toDouble(), BOARD_Y.toDouble(), PANEL_W.toDouble(), BOARD.toDouble(), 24.0, 24.0))
        // Player cards: the side at the top of the board on top.
        drawPlayerCard(g, s.topName, BOARD_Y + 16)
        drawPlayerCard(g, s.bottomName, BOARD_Y + BOARD - 16 - 72)

        var y = BOARD_Y + 150
        if (s.chapter.isNotEmpty()) {
            g.font = font(Font.PLAIN, 28)
            g.color = Palette.ON_DARK_2
            g.drawString(s.chapter, PANEL_X + 32, y)
            y += 70
        }
        if (s.moveLabel.isNotEmpty()) {
            g.font = font(Font.BOLD, 52)
            g.color = Palette.ON_DARK
            g.drawString(s.moveLabel, PANEL_X + 32, y)
            val labelW = g.fontMetrics.stringWidth(s.moveLabel)
            s.classification?.let { cls ->
                g.font = font(Font.BOLD, 28)
                val text = cls.lowercase().replaceFirstChar { it.uppercase() }
                val w = g.fontMetrics.stringWidth(text) + 32
                val cx = PANEL_X + 32 + labelW + 24
                g.color = Palette.classification(cls)
                g.fill(RoundRectangle2D.Double(cx.toDouble(), y - 38.0, w.toDouble(), 46.0, 23.0, 23.0))
                g.color = Color.WHITE
                g.drawString(text, cx + 16, y - 5)
            }
            y += 60
        }
        if (s.beatCaption.isNotEmpty()) {
            g.font = font(Font.PLAIN, 32)
            g.color = Palette.ON_DARK
            val lines = wrap(g.fontMetrics, s.beatCaption, PANEL_W - 64).take(6)
            for (line in lines) {
                g.drawString(line, PANEL_X + 32, y)
                y += 44
            }
        }
    }

    private fun drawPlayerCard(g: Graphics2D, name: String, top: Int) {
        if (name.isEmpty()) return
        g.color = Palette.CHROME_DARK
        g.fill(RoundRectangle2D.Double(PANEL_X + 16.0, top.toDouble(), PANEL_W - 32.0, 72.0, 16.0, 16.0))
        g.font = font(Font.BOLD, 34)
        g.color = Palette.ON_DARK
        g.drawString(ellipsize(g.fontMetrics, name, PANEL_W - 80), PANEL_X + 40, top + 48)
    }

    // ---- caption bar ------------------------------------------------------------------------

    private fun drawCaptionBar(g: Graphics2D, text: String) {
        if (text.isBlank()) return
        g.color = Palette.CAPTION_BG
        g.fill(RoundRectangle2D.Double(CAP_X.toDouble(), CAP_Y.toDouble(), CAP_W.toDouble(), CAP_H.toDouble(), 20.0, 20.0))
        var size = 46
        var lines: List<String>
        while (true) {
            g.font = font(Font.BOLD, size)
            lines = wrap(g.fontMetrics, text, CAP_W - 80)
            val lineH = (size * 1.25).toInt()
            if (lines.size * lineH <= CAP_H - 30 || size <= 26) break
            size -= 4
        }
        val fm = g.fontMetrics
        val lineH = (size * 1.25).toInt()
        val block = lines.size * lineH
        var y = CAP_Y + (CAP_H - block) / 2 + fm.ascent
        g.color = Palette.ON_DARK
        for (line in lines) {
            g.drawString(line, CAP_X + (CAP_W - fm.stringWidth(line)) / 2, y)
            y += lineH
        }
    }

    // ---- cards ------------------------------------------------------------------------------

    private fun drawCard(g: Graphics2D, c: CardSpec) {
        g.color = Palette.CHROME_DARK
        g.fillRect(0, 0, width, height)
        g.color = Palette.SURFACE_DARK
        g.fill(RoundRectangle2D.Double(160.0, 120.0, width - 320.0, 680.0, 32.0, 32.0))
        var y = 260
        g.font = font(Font.BOLD, 64)
        g.color = Palette.ON_DARK
        for (line in wrap(g.fontMetrics, c.heading, width - 440)) {
            g.drawString(line, (width - g.fontMetrics.stringWidth(line)) / 2, y)
            y += 80
        }
        y += 20
        g.font = font(Font.PLAIN, 36)
        for (line in c.lines.flatMap { wrap(g.fontMetrics, it, width - 440) }.take(6)) {
            g.drawString(line, (width - g.fontMetrics.stringWidth(line)) / 2, y)
            y += 52
        }
        y += 20
        g.font = font(Font.PLAIN, 32)
        g.color = Palette.ON_DARK_2
        for (line in c.subLines.take(4)) {
            g.drawString(line, (width - g.fontMetrics.stringWidth(line)) / 2, y)
            y += 46
        }
        drawCaptionBar(g, c.subtitle)
    }

    // ---- pieces ------------------------------------------------------------------------------

    private fun sprite(c: Char): BufferedImage = sprites.getOrPut(c) {
        val piece = Piece.fromFenChar(c)
        val shape = PiecePaths.shape(piece.type)
        val b = shape.bounds2D
        val img = BufferedImage(SQ, SQ, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        hints(g)
        val target = SQ * if (piece.type == PieceType.PAWN) 0.78 else 0.88
        val scale = min(target / b.width, target / b.height)
        val t = AffineTransform()
        t.translate((SQ - b.width * scale) / 2.0 - b.x * scale, (SQ - b.height * scale) / 2.0 - b.y * scale)
        t.scale(scale, scale)
        val placed = t.createTransformedShape(shape)
        val white = piece.color == net.palaya.chessanalyzer.core.chess.Color.WHITE
        g.color = if (white) Palette.WHITE_FILL else Palette.BLACK_FILL
        g.fill(placed)
        g.color = if (white) Palette.WHITE_STROKE else Palette.BLACK_STROKE
        g.stroke = BasicStroke((1.5 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(placed)
        g.dispose()
        img
    }

    // ---- helpers -----------------------------------------------------------------------------

    private val fonts = HashMap<Pair<Int, Int>, Font>()
    private fun font(style: Int, size: Int): Font = fonts.getOrPut(style to size) { Font(Font.SANS_SERIF, style, size) }

    private fun hints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.composite = AlphaComposite.SrcOver
    }

    companion object {
        const val EVAL_X = 40
        const val EVAL_W = 36
        const val BOARD_X = 100
        const val BOARD_Y = 100
        const val SQ = 90
        const val BOARD = SQ * 8
        const val PANEL_X = 860
        const val PANEL_W = 1020
        const val CAP_X = 40
        const val CAP_Y = 850
        const val CAP_W = 1840
        const val CAP_H = 210

        /** FEN placement → square index → FEN piece char. */
        fun parsePlacement(placement: String): Array<Char?> {
            val out = arrayOfNulls<Char>(64)
            if (placement.isEmpty()) return out
            val ranks = placement.split('/')
            for ((ri, row) in ranks.withIndex()) {
                val rank = 7 - ri
                var file = 0
                for (c in row) {
                    if (c.isDigit()) file += c - '0'
                    else {
                        if (file in 0..7 && rank in 0..7) out[rank * 8 + file] = c
                        file++
                    }
                }
            }
            return out
        }

        fun wrap(fm: FontMetrics, text: String, maxWidth: Int): List<String> {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val lines = ArrayList<String>()
            var cur = StringBuilder()
            for (w in words) {
                val candidate = if (cur.isEmpty()) w else "$cur $w"
                if (fm.stringWidth(candidate) <= maxWidth || cur.isEmpty()) {
                    cur = StringBuilder(candidate)
                } else {
                    lines.add(cur.toString())
                    cur = StringBuilder(w)
                }
            }
            if (cur.isNotEmpty()) lines.add(cur.toString())
            return lines
        }

        fun ellipsize(fm: FontMetrics, text: String, maxWidth: Int): String {
            if (fm.stringWidth(text) <= maxWidth) return text
            var t = text
            while (t.isNotEmpty() && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
            return "$t…"
        }
    }
}
