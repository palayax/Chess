package net.palaya.chessanalyzer.ui.board

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import net.palaya.chessanalyzer.ui.model.PieceType

/**
 * Classic Staunton chess piece silhouettes: the **Cburnett** piece set, by Wikimedia Commons
 * user Cburnett, licensed CC BY-SA 3.0. It is the de-facto standard open piece set (used by
 * Wikipedia and Lichess) and is what most players recognise as "the" Staunton look.
 *
 * The path data below is transcribed by hand from the 12 source SVGs (45x45 viewBox each,
 * fetched from Wikimedia Commons — see full attribution and licence text in
 * `app/src/main/assets/PIECES_LICENSE.txt`, which ships in the APK to satisfy CC BY-SA's
 * attribution requirement). Every `d="..."` path command from the originals is reproduced here
 * as Compose [Path] calls (`moveTo`/`lineTo`/`cubicTo`/relative variants), and every circle is
 * reproduced as an oval. **Modifications from the original artwork** (also noted in the licence
 * file, as CC BY-SA requires): coordinates are uniformly scaled by [SCALE]; decorative
 * stroke-only accent lines that are cosmetically minor (the queen's neckline arcs, the knight's
 * tiny nostril highlight) are omitted; and stroke-only detail lines that matter for reading the
 * piece (the bishop's mitre slit + waist bands, the knight's eye) are reproduced as carved
 * [PathOperation.Difference] holes instead of separate stroke draws, because this app's
 * renderers (see [ChessBoard] and [net.palaya.chessanalyzer.video.BoardFrameRenderer]) fill and
 * stroke a *single* path per piece type rather than compositing several coloured sub-paths.
 *
 * Both piece colors share one [Path] per type — [ChessBoard] just fills/strokes it with a
 * different palette (light fill + dark outline for White, dark fill + light outline for Black)
 * — so there are exactly 6 paths total, built once and cached via `by lazy`, never rebuilt per
 * frame or per square. This matches the original Cburnett source, where White and Black share
 * the same outer silhouette per piece type and differ mainly in fill/stroke colour.
 */
internal object PieceGeometry {

    val king: Path by lazy { buildKing() }
    val queen: Path by lazy { buildQueen() }
    val rook: Path by lazy { buildRook() }
    val bishop: Path by lazy { buildBishop() }
    val knight: Path by lazy { buildKnight() }
    val pawn: Path by lazy { buildPawn() }

    fun pathFor(type: PieceType): Path = when (type) {
        PieceType.KING -> king
        PieceType.QUEEN -> queen
        PieceType.ROOK -> rook
        PieceType.BISHOP -> bishop
        PieceType.KNIGHT -> knight
        PieceType.PAWN -> pawn
    }

    // Tight ink bounds per piece, cached alongside the path (also computed once, via `by lazy`).
    // Piece silhouettes don't all use the same amount of their nominal viewport, so [ChessBoard]
    // centers each piece by its *actual* bounds rather than a nominal box — that's what
    // guarantees an even margin on all four sides for every piece, instead of an inconsistent
    // gap that can crowd the board edge/coordinate labels for visually shorter pieces.
    private val kingBounds: Rect by lazy { king.getBounds() }
    private val queenBounds: Rect by lazy { queen.getBounds() }
    private val rookBounds: Rect by lazy { rook.getBounds() }
    private val bishopBounds: Rect by lazy { bishop.getBounds() }
    private val knightBounds: Rect by lazy { knight.getBounds() }
    private val pawnBounds: Rect by lazy { pawn.getBounds() }

    fun boundsFor(type: PieceType): Rect = when (type) {
        PieceType.KING -> kingBounds
        PieceType.QUEEN -> queenBounds
        PieceType.ROOK -> rookBounds
        PieceType.BISHOP -> bishopBounds
        PieceType.KNIGHT -> knightBounds
        PieceType.PAWN -> pawnBounds
    }

    // ---- Coordinate scale ----
    //
    // The Cburnett source SVGs are drawn in a 45x45 viewBox with a uniform stroke-width of 1.5.
    // [ChessBoard.drawPiece] and [net.palaya.chessanalyzer.video.BoardFrameRenderer.drawPiece]
    // are out of scope for this change (other work is in flight there) and both hardcode a
    // 2.6-unit stroke width in the *path's own* coordinate space, tuned for the old hand-authored
    // ~100-unit-tall geometry. Transcribing Cburnett at its native 45-unit scale unchanged would
    // make that fixed 2.6-unit stroke render roughly 1.7x too bold relative to piece size versus
    // what the source art intended. Scaling every transcribed coordinate by SCALE = 2.6 / 1.5
    // instead makes the *fixed* 2.6-unit renderer stroke line up with Cburnett's own 1.5-unit
    // stroke proportion, without touching either renderer file.
    private const val SCALE = 2.6f / 1.5f

    private fun Path.mv(x: Float, y: Float) = moveTo(x * SCALE, y * SCALE)
    private fun Path.ln(x: Float, y: Float) = lineTo(x * SCALE, y * SCALE)
    private fun Path.cv(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        cubicTo(x1 * SCALE, y1 * SCALE, x2 * SCALE, y2 * SCALE, x * SCALE, y * SCALE)
    private fun Path.rcv(dx1: Float, dy1: Float, dx2: Float, dy2: Float, dx: Float, dy: Float) =
        relativeCubicTo(dx1 * SCALE, dy1 * SCALE, dx2 * SCALE, dy2 * SCALE, dx * SCALE, dy * SCALE)

    // ---- Individual pieces (path data transcribed from the Cburnett SVGs; see file header) ----

    private fun buildPawn(): Path {
        // Source: Chess_plt45.svg / Chess_pdt45.svg (identical geometry for both colors).
        // "m 22.5,9 c -2.21,0 -4,1.79 -4,4 0,0.89 0.29,1.71 0.78,2.38
        //  C 17.33,16.5 16,18.59 16,21 c 0,2.03 0.94,3.84 2.41,5.03
        //  C 15.41,27.09 11,31.58 11,39.5 H 34
        //  C 34,31.58 29.59,27.09 26.59,26.03 28.06,24.84 29,23.03 29,21
        //  29,18.59 27.67,16.5 25.72,15.38 26.21,14.71 26.5,13.89 26.5,13
        //  c 0,-2.21 -1.79,-4 -4,-4 z"
        return Path().apply {
            mv(22.5f, 9f)
            rcv(-2.21f, 0f, -4f, 1.79f, -4f, 4f)
            rcv(0f, 0.89f, 0.29f, 1.71f, 0.78f, 2.38f)
            cv(17.33f, 16.5f, 16f, 18.59f, 16f, 21f)
            rcv(0f, 2.03f, 0.94f, 3.84f, 2.41f, 5.03f)
            cv(15.41f, 27.09f, 11f, 31.58f, 11f, 39.5f)
            ln(34f, 39.5f) // "H 34"
            cv(34f, 31.58f, 29.59f, 27.09f, 26.59f, 26.03f)
            cv(28.06f, 24.84f, 29f, 23.03f, 29f, 21f)
            cv(29f, 18.59f, 27.67f, 16.5f, 25.72f, 15.38f)
            cv(26.21f, 14.71f, 26.5f, 13.89f, 26.5f, 13f)
            rcv(0f, -2.21f, -1.79f, -4f, -4f, -4f)
            close()
        }
    }

    private fun buildRook(): Path {
        // Source: Chess_rlt45.svg / Chess_rdt45.svg (identical geometry). The crenellated top
        // edge is traced directly as a zigzag polyline (no separate merlon rects needed) — that
        // zigzag IS the crenellation, so it survives the union below intact.
        val crenellationsAndFunnel = Path().apply {
            mv(11f, 14f)
            ln(11f, 9f)
            ln(15f, 9f)
            ln(15f, 11f)
            ln(20f, 11f)
            ln(20f, 9f)
            ln(25f, 9f)
            ln(25f, 11f)
            ln(30f, 11f)
            ln(30f, 9f)
            ln(34f, 9f)
            ln(34f, 14f)
            ln(31f, 17f)
            ln(14f, 17f)
            close()
        }
        val wall = rect(14f, 17f, 31f, 29.5f)
        val taper = Path().apply {
            mv(14f, 29.5f)
            ln(31f, 29.5f)
            ln(32.5f, 32f)
            ln(12.5f, 32f)
            close()
        }
        val collar = rect(12f, 32f, 33f, 36f)
        val plinth = rect(9f, 36f, 36f, 39f)
        return unionAll(listOf(crenellationsAndFunnel, wall, taper, collar, plinth))
    }

    private fun buildBishop(): Path {
        // Source: Chess_blt45.svg / Chess_bdt45.svg (identical geometry). The mitre slit and
        // waist bands are stroke-only accents in the source; carved here as Difference holes so
        // they stay visible once the whole path is filled + outline-stroked by the renderer.
        val base = Path().apply {
            mv(9f, 36f)
            cv(12.39f, 35.03f, 19.11f, 36.43f, 22.5f, 34f)
            cv(25.89f, 36.43f, 32.61f, 35.03f, 36f, 36f)
            cv(36f, 36f, 37.65f, 36.54f, 39f, 38f)
            cv(38.32f, 38.97f, 37.35f, 38.99f, 36f, 38.5f)
            cv(32.61f, 37.53f, 25.89f, 38.96f, 22.5f, 37.5f)
            cv(19.11f, 38.96f, 12.39f, 37.53f, 9f, 38.5f)
            cv(7.65f, 38.99f, 6.68f, 38.97f, 6f, 38f)
            cv(7.35f, 36.54f, 9f, 36f, 9f, 36f)
            close()
        }
        val body = Path().apply {
            mv(15f, 32f)
            cv(17.5f, 34.5f, 27.5f, 34.5f, 30f, 32f)
            cv(30.5f, 30.5f, 30f, 30f, 30f, 30f)
            cv(30f, 27.5f, 27.5f, 26f, 27.5f, 26f)
            cv(33f, 24.5f, 33.5f, 14.5f, 22.5f, 10.5f)
            cv(11.5f, 14.5f, 12f, 24.5f, 17.5f, 26f)
            cv(17.5f, 26f, 15f, 27.5f, 15f, 30f)
            cv(15f, 30f, 14.5f, 30.5f, 15f, 32f)
            close()
        }
        val mitreBall = oval(22.5f, 8f, 2.5f, 2.5f)
        val combined = unionAll(listOf(base, body, mitreBall))

        val d = 0.75f // half-width of a carved detail groove, matching the source's 1.5 stroke-width
        val slitVertical = rect(22.5f - d, 15.5f, 22.5f + d, 20.5f)
        val slitHorizontal = rect(20f, 18f - d, 25f, 18f + d)
        val bandUpper = rect(17.5f, 26f - d, 27.5f, 26f + d)
        val bandLower = rect(15f, 30f - d, 30f, 30f + d)
        val cuts = unionAll(listOf(slitVertical, slitHorizontal, bandUpper, bandLower))
        return subtract(combined, cuts)
    }

    private fun buildKnight(): Path {
        // Source: Chess_nlt45.svg / Chess_ndt45.svg (identical geometry). Two filled sub-paths —
        // the back/neck outline and the front profile (which carries the jagged mane texture
        // along its top edge) — union into the full silhouette; the eye is a carved hole.
        val outline = Path().apply {
            mv(22f, 10f)
            cv(32.5f, 11f, 38.5f, 18f, 38f, 39f)
            ln(15f, 39f)
            cv(15f, 30f, 25f, 32.5f, 23f, 18f)
            close()
        }
        val faceAndMane = Path().apply {
            mv(24f, 18f)
            cv(24.38f, 20.91f, 18.45f, 25.37f, 16f, 27f)
            cv(13f, 29f, 13.18f, 31.34f, 11f, 31f)
            cv(9.958f, 30.06f, 12.41f, 27.96f, 11f, 28f)
            cv(10f, 28f, 11.19f, 29.23f, 10f, 30f)
            cv(9f, 30f, 5.997f, 31f, 6f, 26f)
            cv(6f, 24f, 12f, 14f, 12f, 14f)
            cv(12f, 14f, 13.89f, 12.1f, 14f, 10.5f)
            cv(13.27f, 9.506f, 13.5f, 8.5f, 13.5f, 7.5f)
            cv(14.5f, 6.5f, 16.5f, 10f, 16.5f, 10f)
            ln(18.5f, 10f)
            cv(18.5f, 10f, 19.28f, 8.008f, 21f, 7f)
            cv(22f, 7f, 22f, 10f, 22f, 10f)
            close()
        }
        val eye = oval(9f, 25.5f, 0.5f, 0.5f)
        val silhouette = union(outline, faceAndMane)
        return subtract(silhouette, eye)
    }

    private fun buildQueen(): Path {
        // Source: Chess_qlt45.svg / Chess_qdt45.svg (identical geometry). The spiked crown zigzag
        // reaches up to 5 pearls, each an individual circle unioned onto the crown tips.
        val crown = Path().apply {
            mv(9f, 26f)
            cv(17.5f, 24.5f, 30f, 24.5f, 36f, 26f)
            ln(38.5f, 13.5f)
            ln(31f, 25f)
            ln(30.7f, 10.9f)
            ln(25.5f, 24.5f)
            ln(22.5f, 10f)
            ln(19.5f, 24.5f)
            ln(14.3f, 10.9f)
            ln(14f, 25f)
            ln(6.5f, 13.5f)
            ln(9f, 26f)
            close()
        }
        val body = Path().apply {
            mv(9f, 26f)
            cv(9f, 28f, 10.5f, 28f, 11.5f, 30f)
            cv(12.5f, 31.5f, 12.5f, 31f, 12f, 33.5f)
            cv(10.5f, 34.5f, 11f, 36f, 11f, 36f)
            cv(9.5f, 37.5f, 11f, 38.5f, 11f, 38.5f)
            cv(17.5f, 39.5f, 27.5f, 39.5f, 34f, 38.5f)
            cv(34f, 38.5f, 35.5f, 37.5f, 34f, 36f)
            cv(34f, 36f, 34.5f, 34.5f, 33f, 33.5f)
            cv(32.5f, 31f, 32.5f, 31.5f, 33.5f, 30f)
            cv(34.5f, 28f, 36f, 28f, 36f, 26f)
            cv(27.5f, 24.5f, 17.5f, 24.5f, 9f, 26f)
            close()
        }
        val pearlCenters = listOf(6f to 12f, 14f to 9f, 22.5f to 8f, 31f to 9f, 39f to 12f)
        val pearls = pearlCenters.map { (cx, cy) -> oval(cx, cy, 2f, 2f) }
        return unionAll(listOf(crown, body) + pearls)
    }

    private fun buildKing(): Path {
        // Source: Chess_kdt45.svg (used for both colors — its coordinates are the fully-expanded
        // absolute equivalent of Chess_klt45.svg's shorthand curves; the numbers match exactly).
        // The cross is stroke-only in the source; reproduced here as two solid unioned bars.
        val body = Path().apply {
            mv(12.5f, 37f)
            cv(18f, 40.5f, 27f, 40.5f, 32.5f, 37f)
            ln(32.5f, 30f)
            cv(32.5f, 30f, 41.5f, 25.5f, 38.5f, 19.5f)
            cv(34.5f, 13f, 25f, 16f, 22.5f, 23.5f)
            ln(22.5f, 27f)
            ln(22.5f, 23.5f)
            cv(20f, 16f, 10.5f, 13f, 6.5f, 19.5f)
            cv(3.5f, 25.5f, 12.5f, 30f, 12.5f, 30f)
            ln(12.5f, 37f)
            close()
        }
        val head = Path().apply {
            mv(22.5f, 25f)
            cv(22.5f, 25f, 27f, 17.5f, 25.5f, 14.5f)
            cv(25.5f, 14.5f, 24.5f, 12f, 22.5f, 12f)
            cv(20.5f, 12f, 19.5f, 14.5f, 19.5f, 14.5f)
            cv(18f, 17.5f, 22.5f, 25f, 22.5f, 25f)
            close()
        }
        // The cross bars are made noticeably thicker (4.0 native units) than a literal
        // transcription of the source's 1.5-unit stroke-only line would suggest. Root cause of a
        // real rendering bug: both renderers draw this piece as ONE fill pass plus a *contrasting*
        // outline stroke ~2.6 units wide (constant regardless of piece scale in
        // BoardFrameRenderer, and roughly that order of magnitude in ChessBoard too). A 1.5-unit
        // bar is *narrower* than that outline stroke, so the contrasting-color stroke — drawn on
        // top, straddling each edge — eats essentially the *entire* fill width from both sides,
        // leaving only a razor-thin, near-invisible sliver of the piece's own color. That read as
        // "the cross is clipped" (verified with a debug render isolating fill-only vs fill+stroke
        // at the real board scale: fill-only showed a complete cross, fill+stroke did not). Cburnett's
        // own SVG sidesteps this because it draws the cross as a *pure stroke line*, never a filled
        // shape with its own separate outline — a technique this app's single-path
        // fill-then-outline renderers don't support. Widening the bars so a clear fill-colored core
        // survives the outline inset on both sides is the fix; the bottom of crossVertical also
        // reaches to y=16 (well past the head's own top vertex at (22.5, 12), two lines up) purely
        // for a generous, unambiguous overlap into the head.
        //
        // The horizontal arm needs *length*, not just thickness, to read as a cross rather than a
        // lumpy post. First attempt kept its span at the source's literal 20..25 (5 units) — only
        // 0.5 units wider than the 4-unit-wide vertical bar on each side — but the outline stroke
        // eats ~0.75 native units in from every outer edge (see the thickness comment above), so
        // that whole 0.5-unit protrusion vanished into the outline on both sides: the arm blended
        // into the post with nothing visibly sticking out. The arm's total span is now 3x the
        // vertical bar's width (12 vs 4), centered on the post, so each side protrudes 4 units
        // past it — comfortably more than the ~0.75-unit inset — leaving an unmistakable fill-
        // colored arm on both sides after the outline is drawn.
        // Both bars previously started at y=6, i.e. flush tops, which renders a "T" rather than a
        // cross — the vertical post has to protrude ABOVE the horizontal arm for the shape to read
        // as a king. Raise the post to y=3.5 and drop the arm to y=7.5..11.5 so ~4 native units of
        // post stand clear above it (Cburnett's own art puts the arm at y=8 with the post topping
        // out at y=6, the same relationship).
        val crossVertical = rect(20.5f, 3.5f, 24.5f, 16f)
        val crossHorizontal = rect(16.5f, 7.5f, 28.5f, 11.5f)
        return unionAll(listOf(body, head, crossVertical, crossHorizontal))
    }

    // ---- Primitive helpers (all take native Cburnett/45-viewBox coordinates and apply SCALE) ----

    private fun rect(l: Float, t: Float, r: Float, b: Float): Path =
        Path().apply { addRect(Rect(l * SCALE, t * SCALE, r * SCALE, b * SCALE)) }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float): Path =
        Path().apply {
            addOval(Rect((cx - rx) * SCALE, (cy - ry) * SCALE, (cx + rx) * SCALE, (cy + ry) * SCALE))
        }

    private fun union(a: Path, b: Path): Path {
        val result = Path()
        result.op(a, b, PathOperation.Union)
        return result
    }

    private fun subtract(a: Path, b: Path): Path {
        val result = Path()
        result.op(a, b, PathOperation.Difference)
        return result
    }

    private fun unionAll(paths: List<Path>): Path {
        require(paths.isNotEmpty())
        var acc = paths[0]
        for (i in 1 until paths.size) {
            acc = union(acc, paths[i])
        }
        return acc
    }
}
