package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path as APath
import android.graphics.RectF
import android.text.TextPaint
import android.text.StaticLayout
import android.text.Layout
import android.text.TextUtils
import androidx.compose.ui.graphics.asAndroidPath
import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.narration.ArrowRole
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoGameHeader
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.PieceGeometry
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.Piece
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.PieceType
import net.palaya.chessanalyzer.ui.model.Square
import net.palaya.chessanalyzer.ui.model.file
import net.palaya.chessanalyzer.ui.model.rankFromTop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one frame of the narrated-video board straight onto an [android.graphics.Canvas], at
 * whatever pixel size the caller asks for (the export path renders 1280x720; in-app live
 * playback could ask for a different size). Deliberately has **no Compose dependency** — it is
 * shared between [net.palaya.chessanalyzer.video.VideoExporter]'s `MediaCodec` input-surface
 * frames and could equally back an `AndroidView`/`Canvas`-based live preview, without pulling in
 * recomposition machinery on the hot encoding path.
 *
 * Piece silhouettes are still [PieceGeometry]'s paths — that object is `internal`, which in
 * Kotlin means "visible anywhere in this Gradle module", not "visible only in its package", so
 * this class (also in `:app`) can call it directly. Each path is a `androidx.compose.ui.graphics.Path`
 * that already wraps a plain `android.graphics.Path` under the hood; [asAndroidPath] is a zero-copy
 * cast, not a conversion, so reusing them here costs nothing and keeps the two renderers
 * (Compose board, video board) drawing pixel-identical silhouettes.
 */
object BoardFrameRenderer {

    /** One piece caught mid-animation between two squares, drawn on top of the static board. */
    data class AnimatingPiece(
        val piece: Piece,
        val from: Square,
        val to: Square,
        /** 0f = at [from], 1f = at [to]. Callers ease this themselves before passing it in. */
        val progress: Float,
    )

    data class FrameArrow(val from: Square, val to: Square, val role: ArrowRole)

    /** Everything needed to draw one "board" frame (as opposed to a title [Card] frame). */
    data class BoardFrameSpec(
        val boardState: BoardState,
        val orientation: BoardOrientation = BoardOrientation.WHITE_DOWN,
        val lastMove: Pair<Square, Square>? = null,
        val checkedKingSquare: Square? = null,
        val highlightSquares: List<Square> = emptyList(),
        val arrows: List<FrameArrow> = emptyList(),
        val animating: AnimatingPiece? = null,
        /** True while playing out a `PlayLine` excursion — draws a tinted border + label chip. */
        val excursionActive: Boolean = false,
        val excursionLabel: String? = null,
        /**
         * The spec's verdict on the move this segment is about, from `ScriptSegment.classification`
         * — set on every segment shape that refers to a real played move, not just `PlayMove`.
         * This, not [segmentKind], is what the panel's chip says, so the video and the review move
         * list call the same ply the same thing.
         */
        val classification: MoveClassification? = null,
        val caption: String = "",
        val chapterLabel: String? = null,
        val showCoordinates: Boolean = true,
        // ---- Side-panel context (all optional; only real VideoScript/ScriptSegment data — never
        // fabricated. See SegmentFrameBuilder for how these are derived.) ----
        val segmentKind: SegmentKind? = null,
        val speakerColor: CoreColor? = null,
        val userColor: CoreColor? = null,
        val tactic: TacticInstance? = null,
        val ply: Int? = null,
        /** SAN of the move this segment is about (only set for a `PlayMove` directive). */
        val san: String? = null,
        /** SAN of moves already played earlier in the script, most recent last. */
        val recentMoves: List<String> = emptyList(),
        /** Players/result/opening, from `VideoScript.header` — null renders no Players block. */
        val header: VideoGameHeader? = null,
        /** 0..100, White's perspective — drives the eval bar. Null draws no bar. */
        val evalWinPercentWhite: Double? = null,
        val evalCp: Int? = null,
        /** Signed, White-relative mate distance; overrides [evalCp] on the bar's numeric label. */
        val evalMateIn: Int? = null,
        /** Preferred over deriving a move number from [ply] when the contract supplies it directly. */
        val moveNumber: Int? = null,
        /**
         * Signed, White-relative centipawn change this segment's move caused, from
         * `ScriptSegment.evalSwingCp`. Null on cards and on hypothetical-line beats, which is
         * why the panel draws the swing conditionally rather than substituting a zero.
         */
        val evalSwingCp: Int? = null,
        /** The words burned into the side panel — see [PanelLabels]. */
        val labels: PanelLabels = PanelLabels.ENGLISH,
    )

    /**
     * Every piece of text this renderer burns into a frame that is not data from the script.
     *
     * The renderer has no `Context` (it draws into an off-screen `Canvas` for the exporter and
     * into a `View` for playback), so the strings are resolved once by the caller —
     * [PanelLabels.from] — and passed in on the spec. [ENGLISH] is the compile-time default so
     * tests and previews that build a spec by hand keep working; the app always uses [from].
     *
     * The classification verdict goes through the *UI* string resources, so the panel's chip and
     * the review move list call a MISTAKE the same thing in every language.
     */
    data class PanelLabels(
        val moveNumber: (Int) -> String,
        val tactic: (TacticType) -> String,
        val recentMoves: String,
        val evaluation: String,
        val toMove: (CoreColor) -> String,
        val youMarker: String,
        val verdict: (MoveClassification) -> String,
        val segmentKind: (SegmentKind) -> String,
        val excursionDefault: String,
    ) {
        companion object {
            val ENGLISH = PanelLabels(
                moveNumber = { "Move $it" },
                tactic = { "Tactic: ${it.displayName}" },
                recentMoves = "RECENT MOVES",
                evaluation = "EVALUATION",
                toMove = { "${if (it == CoreColor.WHITE) "White" else "Black"} to move" },
                youMarker = "(you)",
                verdict = { "${it.glyph} ${it.displayName}" },
                segmentKind = ::humanizeKind,
                excursionDefault = "What you could aim for",
            )

            /** Resolves every label from the app's resources in the current locale. */
            fun from(context: Context): PanelLabels = PanelLabels(
                moveNumber = { context.getString(R.string.panel_move_number, it) },
                tactic = { context.getString(R.string.panel_tactic, context.tacticTypeName(it)) },
                recentMoves = context.getString(R.string.panel_recent_moves),
                evaluation = context.getString(R.string.panel_evaluation),
                toMove = {
                    val side = context.getString(if (it == CoreColor.WHITE) R.string.side_white else R.string.side_black)
                    context.getString(R.string.panel_to_move, side)
                },
                youMarker = context.getString(R.string.panel_you_marker),
                verdict = { v ->
                    val uiVerdict = net.palaya.chessanalyzer.ui.theme.MoveClassification.valueOf(v.name)
                    "${v.glyph} ${context.getString(uiVerdict.displayNameRes)}"
                },
                // SegmentKind chips ("Puzzle Prompt", "Intro") have no resources yet — see RUN_LOG.
                segmentKind = ::humanizeKind,
                excursionDefault = context.getString(R.string.panel_excursion_default),
            )
        }
    }

    // ---- Palette (mirrors ui/theme/Color.kt's hex values so the exported video matches the
    // in-app board; kept as plain ARGB ints here since this class must not depend on Compose). ----
    private const val BOARD_LIGHT = 0xFFEBECD0.toInt()
    private const val BOARD_DARK = 0xFF739552.toInt()
    private const val LAST_MOVE = 0x99BACA44.toInt()
    private const val HIGHLIGHT = 0x99F7F769.toInt()
    private const val CHECK_RED = 0xFFEE3B3B.toInt()
    private const val CHROME_DARK = 0xFF302E2B.toInt()
    private const val SURFACE_DARK = 0xFF262421.toInt()
    private const val ON_DARK_PRIMARY = 0xFFFAF9F6.toInt()
    private const val ON_DARK_SECONDARY = 0xFFB8B5AE.toInt()
    private const val GREEN_PRIMARY = 0xFF81B64C.toInt()
    private const val CAPTION_BG = 0xE61E1D1B.toInt()
    private const val EXCURSION_TINT = 0xFFC77DFF.toInt() // distinct purple — never used elsewhere
    private const val WHITE_FILL = 0xFFF7F5EF.toInt()
    private const val WHITE_STROKE = 0xFF23221F.toInt()
    private const val BLACK_FILL = 0xFF1A1917.toInt()
    private const val BLACK_STROKE = 0xFFF2F1EC.toInt()
    // Exact hex match to ui/theme/Color.kt's EvalWhiteFill/EvalBlackFill — the video's eval bar
    // must look like the app's `EvalBar` composable, not just be "close".
    private const val EVAL_WHITE_FILL = 0xFFF2F1EC.toInt()
    private const val EVAL_BLACK_FILL = 0xFF1A1917.toInt()

    private val classificationColors: Map<MoveClassification, Int> = mapOf(
        MoveClassification.BRILLIANT to 0xFF26C2A3.toInt(),
        MoveClassification.GREAT to 0xFF749BBF.toInt(),
        MoveClassification.BEST to 0xFF81B64C.toInt(),
        MoveClassification.EXCELLENT to 0xFF81B64C.toInt(),
        MoveClassification.GOOD to 0xFF95B776.toInt(),
        MoveClassification.BOOK to 0xFFA88865.toInt(),
        MoveClassification.INACCURACY to 0xFFF7C631.toInt(),
        MoveClassification.MISTAKE to 0xFFE58F2A.toInt(),
        MoveClassification.MISS to 0xFFFF7769.toInt(),
        MoveClassification.BLUNDER to 0xFFFA412D.toInt(),
        MoveClassification.FORCED to 0xFF9E9E9E.toInt(),
    )

    private fun arrowColor(role: ArrowRole): Int = when (role) {
        ArrowRole.PLAYED -> GREEN_PRIMARY
        ArrowRole.BEST -> 0xFF61AFEF.toInt()
        ArrowRole.THREAT -> CHECK_RED
        ArrowRole.SUPPORT -> 0xFFF7C631.toInt()
    }

    // ---- Cached android.graphics.Path per piece type (built once, reused every frame). ----
    private val androidPieceCache = HashMap<PieceType, APath>()
    private val androidBoundsCache = HashMap<PieceType, RectF>()

    private fun androidPathFor(type: PieceType): APath = androidPieceCache.getOrPut(type) {
        PieceGeometry.pathFor(type).asAndroidPath()
    }

    private fun androidBoundsFor(type: PieceType): RectF = androidBoundsCache.getOrPut(type) {
        val b = PieceGeometry.boundsFor(type)
        RectF(b.left, b.top, b.right, b.bottom)
    }

    /** Caption bar height reserved at the bottom of the frame, in pixels, for a given frame height. */
    fun captionBarHeight(heightPx: Int): Float = heightPx * 0.095f

    /**
     * Renders a live-position frame: a dominant board (roughly half the frame's height in side
     * length — see the layout constants below) plus a side-info panel filling the rest of the
     * width, above a caption bar. [widthPx]/[heightPx] is the full output frame (e.g. 1280x720).
     *
     * Layout rationale: at 1280x720 a board sized purely by "fit inside a square region minus
     * margins" (the original layout) comes out ~520px — correct for a phone-sized preview, but it
     * leaves ~380px of empty canvas on each side once the frame is 16:9, which is wasted space in
     * something meant to be *watched*. Sizing the board off the frame's **height** instead (it
     * only competes with the caption bar for vertical space, not with a side panel) pushes it to
     * ~620-630px, and the leftover width goes to a real eval bar (immediately left of the board,
     * matching where [net.palaya.chessanalyzer.ui.components.EvalBar] sits in the app itself) and
     * a side panel, instead of empty margin. `margin` is applied on every outer edge — the board
     * and eval bar must not touch the frame's edges.
     */
    fun renderBoardFrame(canvas: Canvas, widthPx: Int, heightPx: Int, spec: BoardFrameSpec) {
        canvas.drawColor(CHROME_DARK)

        val captionHeight = captionBarHeight(heightPx)
        val margin = heightPx * 0.025f
        val gap = heightPx * 0.03f
        val boardAreaHeight = heightPx - captionHeight
        val boardSize = boardAreaHeight - 2 * margin
        val squareSize = boardSize / 8f

        val evalBarWidth = heightPx * 0.06f
        val evalBarLeft = margin
        val boardLeft = evalBarLeft + evalBarWidth + gap * 0.5f
        val boardTop = margin

        drawEvalBar(canvas, evalBarLeft, boardTop, evalBarWidth, boardSize, spec.evalWinPercentWhite, spec.evalCp, spec.evalMateIn)

        val panelLeft = boardLeft + boardSize + gap
        val panelWidth = (widthPx - panelLeft - margin).coerceAtLeast(0f)
        if (panelWidth > squareSize) {
            drawPanel(canvas, spec, panelLeft, boardTop, panelWidth, boardSize)
        }

        fun displayCell(sq: Square): Pair<Int, Int> {
            val f = sq.file()
            val r = sq.rankFromTop()
            return if (spec.orientation == BoardOrientation.WHITE_DOWN) f to r else (7 - f) to (7 - r)
        }

        val squarePaint = Paint()
        for (row in 0..7) {
            for (col in 0..7) {
                val isLight = (row + col) % 2 == 0
                squarePaint.color = if (isLight) BOARD_LIGHT else BOARD_DARK
                canvas.drawRect(
                    boardLeft + col * squareSize, boardTop + row * squareSize,
                    boardLeft + (col + 1) * squareSize, boardTop + (row + 1) * squareSize,
                    squarePaint,
                )
            }
        }

        spec.lastMove?.let { (from, to) ->
            squarePaint.color = LAST_MOVE
            listOf(from, to).forEach { sq ->
                val (c, r) = displayCell(sq)
                canvas.drawRect(
                    boardLeft + c * squareSize, boardTop + r * squareSize,
                    boardLeft + (c + 1) * squareSize, boardTop + (r + 1) * squareSize,
                    squarePaint,
                )
            }
        }

        if (spec.highlightSquares.isNotEmpty()) {
            squarePaint.color = HIGHLIGHT
            spec.highlightSquares.forEach { sq ->
                val (c, r) = displayCell(sq)
                canvas.drawRect(
                    boardLeft + c * squareSize, boardTop + r * squareSize,
                    boardLeft + (c + 1) * squareSize, boardTop + (r + 1) * squareSize,
                    squarePaint,
                )
            }
        }

        spec.checkedKingSquare?.let { sq ->
            val (c, r) = displayCell(sq)
            squarePaint.color = (CHECK_RED and 0x00FFFFFF) or 0x8C000000.toInt()
            canvas.drawRect(
                boardLeft + c * squareSize, boardTop + r * squareSize,
                boardLeft + (c + 1) * squareSize, boardTop + (r + 1) * squareSize,
                squarePaint,
            )
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = CHECK_RED
                strokeWidth = squareSize * 0.05f
            }
            canvas.drawCircle(
                boardLeft + c * squareSize + squareSize / 2f,
                boardTop + r * squareSize + squareSize / 2f,
                squareSize * 0.46f,
                ringPaint,
            )
        }

        if (spec.showCoordinates) {
            drawCoordinates(canvas, boardLeft, boardTop, squareSize, spec.orientation)
        }

        // Pieces — skip the animating piece's origin/destination squares, it is drawn separately
        // at an interpolated position so it doesn't pop between squares.
        val skipSquares = setOfNotNull(spec.animating?.from, spec.animating?.to)
        spec.boardState.pieces.forEach { (sq, piece) ->
            if (sq in skipSquares) return@forEach
            val (c, r) = displayCell(sq)
            drawPiece(canvas, piece, boardLeft + c * squareSize, boardTop + r * squareSize, squareSize)
        }
        spec.animating?.let { anim ->
            val (fc, fr) = displayCell(anim.from)
            val (tc, tr) = displayCell(anim.to)
            val fx = boardLeft + fc * squareSize
            val fy = boardTop + fr * squareSize
            val tx = boardLeft + tc * squareSize
            val ty = boardTop + tr * squareSize
            val x = fx + (tx - fx) * anim.progress
            val y = fy + (ty - fy) * anim.progress
            drawPiece(canvas, anim.piece, x, y, squareSize)
        }

        spec.arrows.forEach { arrow -> drawArrow(canvas, arrow, displayCell(arrow.from), displayCell(arrow.to), boardLeft, boardTop, squareSize) }

        if (spec.excursionActive) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = EXCURSION_TINT
                strokeWidth = squareSize * 0.08f
            }
            val inset = borderPaint.strokeWidth / 2f
            canvas.drawRect(
                boardLeft - inset, boardTop - inset,
                boardLeft + boardSize + inset, boardTop + boardSize + inset,
                borderPaint,
            )
            if (!spec.excursionLabel.isNullOrBlank()) {
                // Inset into the board's own top-left corner rather than floating above it — the
                // new layout gives the board almost no headroom above its top edge.
                drawChip(
                    canvas,
                    text = spec.excursionLabel,
                    left = boardLeft + squareSize * 0.12f,
                    top = boardTop + squareSize * 0.12f,
                    bgColor = EXCURSION_TINT,
                    textColor = AColor.BLACK,
                    textSizePx = squareSize * 0.22f,
                )
            }
        }

        // The classification badge for the current move now lives in the side panel (drawn
        // above, before the board itself) rather than floating over the board — see [drawPanel].

        drawCaptionBar(canvas, widthPx, heightPx, captionHeight, spec.caption)
    }

    /**
     * Renders a board-less title/summary card (intro, outro, chapter break). [subLines] is
     * structured data the renderer itself formats — [VideoGameHeader] for an intro (names,
     * ratings, result, opening) or the accuracy/rating summary for an outro — drawn below the
     * generator's freeform [lines] in a distinct style, rather than trusting that text to already
     * contain it. Empty when the script has nothing structured to add (older cached script, or a
     * plain chapter-break card), in which case the card renders exactly as it did before.
     */
    fun renderCardFrame(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        heading: String,
        lines: List<String>,
        caption: String = "",
        chapterLabel: String? = null,
        subLines: List<String> = emptyList(),
    ) {
        canvas.drawColor(SURFACE_DARK)

        val accentPaint = Paint().apply { color = GREEN_PRIMARY }
        canvas.drawRect(0f, 0f, widthPx * 0.015f, heightPx.toFloat(), accentPaint)

        val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_DARK_PRIMARY
            textSize = heightPx * 0.09f
            isFakeBoldText = true
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_DARK_SECONDARY
            textSize = heightPx * 0.05f
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_PRIMARY
            textSize = heightPx * 0.042f
            isFakeBoldText = true
        }

        val textLeft = widthPx * 0.10f
        val textWidth = (widthPx * 0.80f).toInt()
        val headingLayout = StaticLayout.Builder
            .obtain(heading, 0, heading.length, headingPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .build()

        val bodyLayouts = lines.map { line ->
            StaticLayout.Builder
                .obtain(line, 0, line.length, bodyPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
        }
        val subLayouts = subLines.map { line ->
            StaticLayout.Builder
                .obtain(line, 0, line.length, subPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
        }

        val bodyBlockHeight = bodyLayouts.sumOf { it.height } + bodyLayouts.size * (heightPx * 0.03f).toInt()
        val subBlockHeight = if (subLayouts.isEmpty()) 0 else {
            (heightPx * 0.04f).toInt() + subLayouts.sumOf { it.height } + subLayouts.size * (heightPx * 0.025f).toInt()
        }
        val totalHeight = headingLayout.height + (heightPx * 0.06f) + bodyBlockHeight + subBlockHeight
        var y = (heightPx - totalHeight) / 2f

        canvas.save()
        canvas.translate(textLeft, y)
        headingLayout.draw(canvas)
        canvas.restore()
        y += headingLayout.height + heightPx * 0.06f

        bodyLayouts.forEach { layout ->
            canvas.save()
            canvas.translate(textLeft, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + heightPx * 0.03f
        }

        if (subLayouts.isNotEmpty()) {
            y += heightPx * 0.02f
            canvas.drawRect(textLeft, y, textLeft + textWidth, y + 2f, Paint().apply { color = 0x33FFFFFF })
            y += heightPx * 0.04f
            subLayouts.forEach { layout ->
                canvas.save()
                canvas.translate(textLeft, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + heightPx * 0.025f
            }
        }

        if (chapterLabel != null) drawChapterBar(canvas, widthPx, heightPx * 0.07f, chapterLabel)
        if (caption.isNotBlank()) {
            drawCaptionBar(canvas, widthPx, heightPx, captionBarHeight(heightPx), caption)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, squareLeft: Float, squareTop: Float, squareSize: Float) {
        val path = androidPathFor(piece.type)
        val bounds = androidBoundsFor(piece.type)
        val (fill, stroke) = if (piece.color == PieceColor.WHITE) WHITE_FILL to WHITE_STROKE else BLACK_FILL to BLACK_STROKE
        val padding = 0.90f
        val targetSize = squareSize * padding
        val boundsW = bounds.width().coerceAtLeast(0.01f)
        val boundsH = bounds.height().coerceAtLeast(0.01f)
        val scale = min(targetSize / boundsW, targetSize / boundsH)
        val originX = squareLeft + (squareSize - boundsW * scale) / 2f - bounds.left * scale
        val originY = squareTop + (squareSize - boundsH * scale) / 2f - bounds.top * scale

        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke
            style = Paint.Style.STROKE
            strokeWidth = 2.6f / scale
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    private fun drawArrow(
        canvas: Canvas,
        arrow: FrameArrow,
        fromCell: Pair<Int, Int>,
        toCell: Pair<Int, Int>,
        boardLeft: Float,
        boardTop: Float,
        squareSize: Float,
    ) {
        val (fc, fr) = fromCell
        val (tc, tr) = toCell
        val startX = boardLeft + fc * squareSize + squareSize / 2f
        val startY = boardTop + fr * squareSize + squareSize / 2f
        val endX = boardLeft + tc * squareSize + squareSize / 2f
        val endY = boardTop + tr * squareSize + squareSize / 2f
        val arrowStrokeWidth = squareSize * 0.16f
        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val shortenBy = squareSize * 0.32f
        val trimEndX = (endX - cos(angle) * shortenBy).toFloat()
        val trimEndY = (endY - sin(angle) * shortenBy).toFloat()
        val color = (arrowColor(arrow.role) and 0x00FFFFFF) or 0xE6000000.toInt()

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = arrowStrokeWidth
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(startX, startY, trimEndX, trimEndY, linePaint)

        val headLength = squareSize * 0.34
        val headAngle = Math.toRadians(28.0)
        val p1x = (endX - cos(angle - headAngle) * headLength).toFloat()
        val p1y = (endY - sin(angle - headAngle) * headLength).toFloat()
        val p2x = (endX - cos(angle + headAngle) * headLength).toFloat()
        val p2y = (endY - sin(angle + headAngle) * headLength).toFloat()
        val headPath = APath().apply {
            moveTo(endX, endY)
            lineTo(p1x, p1y)
            lineTo(p2x, p2y)
            close()
        }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawPath(headPath, headPaint)
    }

    private fun drawCoordinates(canvas: Canvas, boardLeft: Float, boardTop: Float, squareSize: Float, orientation: BoardOrientation) {
        val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BOARD_DARK
            textSize = squareSize * 0.26f
            isFakeBoldText = true
        }
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BOARD_LIGHT
            textSize = squareSize * 0.26f
            isFakeBoldText = true
        }
        for (i in 0..7) {
            val file = if (orientation == BoardOrientation.WHITE_DOWN) i else 7 - i
            val fileChar = ('a' + file).toString()
            val isLightCorner = (7 + i) % 2 == 0
            canvas.drawText(
                fileChar,
                boardLeft + i * squareSize + squareSize * 0.09f,
                boardTop + 8 * squareSize - squareSize * 0.08f,
                if (isLightCorner) lightPaint else darkPaint,
            )
            val rankFromTop = if (orientation == BoardOrientation.WHITE_DOWN) i else 7 - i
            val rankNum = (8 - rankFromTop).toString()
            val isLightRow = i % 2 == 0
            canvas.drawText(
                rankNum,
                boardLeft + squareSize * 8 - squareSize * 0.22f,
                boardTop + i * squareSize + squareSize * 0.24f,
                if (isLightRow) lightPaint else darkPaint,
            )
        }
    }

    private fun drawChapterBar(canvas: Canvas, widthPx: Int, height: Float, label: String) {
        val bg = Paint().apply { color = SURFACE_DARK }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), height, bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_PRIMARY
            textSize = height * 0.46f
            isFakeBoldText = true
        }
        canvas.drawText(label, widthPx * 0.03f, height * 0.68f, paint)
    }

    private fun drawCaptionBar(canvas: Canvas, widthPx: Int, heightPx: Int, captionHeight: Float, caption: String) {
        val top = heightPx - captionHeight
        val bg = Paint().apply { color = CAPTION_BG }
        canvas.drawRect(0f, top, widthPx.toFloat(), heightPx.toFloat(), bg)
        if (caption.isBlank()) return

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_DARK_PRIMARY
            textSize = captionHeight * 0.26f
        }
        val textWidth = (widthPx * 0.92f).toInt()
        // Long narration captions must wrap (or, past two lines, ellipsize) rather than overflow
        // the fixed-height bar — a caption can run longer than a title-card line does.
        val layout = StaticLayout.Builder
            .obtain(caption, 0, caption.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val y = (top + (captionHeight - layout.height) / 2f).coerceAtLeast(top + 4f)
        canvas.save()
        canvas.translate((widthPx - textWidth) / 2f, y)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Vertical eval bar, same visual language as [net.palaya.chessanalyzer.ui.components.EvalBar]:
     * white fill grows from the **bottom** proportional to White's win chance, black fills the
     * rest from the top, numeric label sits at whichever end is currently favoured. Deliberately
     * simple/unambiguous rather than "clever" — a full-rect black background painted first, then
     * a white rect drawn over only the bottom `fraction` of it, so there is no flip/invert logic
     * anywhere that a sign error could hide in (the most likely bug the reviewer flagged: a bar
     * that fills the wrong way for Black).
     */
    private fun drawEvalBar(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        winPercentWhite: Double?,
        evalCp: Int?,
        mateIn: Int?,
    ) {
        val blackBg = Paint().apply { color = EVAL_BLACK_FILL }
        canvas.drawRect(left, top, left + width, top + height, blackBg)

        if (winPercentWhite == null && mateIn == null) {
            // No eval for this beat (a Hold/Annotate segment before the generator attaches one,
            // or an older cached script) — render a neutral all-black bar rather than guessing.
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0x33FFFFFF
                strokeWidth = 2f
            }
            canvas.drawRect(left, top, left + width, top + height, outline)
            return
        }

        // fraction = how much of the bar (from the bottom) is WHITE. High win% for White -> a
        // mostly-white bar; low win% for White (Black winning) -> mostly black. Matches EvalBar.kt.
        val fraction = when {
            mateIn != null -> if (mateIn > 0) 0.97f else 0.03f
            else -> (winPercentWhite!! / 100.0).toFloat().coerceIn(0.03f, 0.97f)
        }
        val whiteHeight = height * fraction
        val whitePaint = Paint().apply { color = EVAL_WHITE_FILL }
        canvas.drawRect(left, top + height - whiteHeight, left + width, top + height, whitePaint)

        val favorsWhite = mateIn?.let { it > 0 } ?: (fraction >= 0.5f)
        val label = if (mateIn != null || evalCp != null) EvalFormat.score(evalCp, mateIn) else null
        if (label != null) {
            val textSize = width * 0.30f
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (favorsWhite) EVAL_BLACK_FILL else EVAL_WHITE_FILL
                this.textSize = textSize
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val chipHeight = textSize * 1.5f
            val chipTop = if (favorsWhite) top + height - chipHeight else top
            val chipPaint = Paint().apply { color = if (favorsWhite) EVAL_WHITE_FILL else EVAL_BLACK_FILL }
            canvas.drawRect(left, chipTop, left + width, chipTop + chipHeight, chipPaint)
            canvas.drawText(label, left + width / 2f, chipTop + chipHeight * 0.72f, textPaint)
        }
    }

    /**
     * The right-hand context panel. Deliberately uses **only** fields [BoardFrameSpec] actually
     * carries from the real `VideoScript`/`ScriptSegment` — no invented player names, ratings or
     * numeric eval, since the narration contract doesn't carry those today (see the video
     * feature's return notes for what a future contract extension would need to add).
     */
    private fun drawPanel(canvas: Canvas, spec: BoardFrameSpec, left: Float, top: Float, width: Float, height: Float) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE_DARK }
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 14f, 14f, bgPaint)

        val pad = (width * 0.08f).coerceAtMost(24f)
        val contentLeft = left + pad
        var y = top + height * 0.07f

        if (!spec.chapterLabel.isNullOrBlank()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GREEN_PRIMARY
                textSize = height * 0.040f
                isFakeBoldText = true
            }
            canvas.drawText(spec.chapterLabel.uppercase(), contentLeft, y, paint)
            y += height * 0.075f
        }

        panelChip(spec)?.let { chip ->
            // Advance by what the chip actually measured, not a fixed fraction: the verdict chip
            // is set larger than the kind chip it replaced, and a constant step let its bottom
            // edge run into the Players block underneath.
            val chipHeight = drawChip(canvas, chip.text, contentLeft, y, chip.color, AColor.BLACK, height * chip.textSizeFraction)
            y += chipHeight + height * 0.042f
        }

        y = drawPlayers(canvas, spec.header, spec.speakerColor, spec.userColor, contentLeft, y, height, spec.labels)

        y += height * 0.015f
        canvas.drawRect(contentLeft, y, left + width - pad, y + 2f, Paint().apply { color = 0x33FFFFFF })
        y += height * 0.06f

        if (!spec.san.isNullOrBlank()) {
            val sanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ON_DARK_PRIMARY
                textSize = height * 0.11f
                isFakeBoldText = true
            }
            y += height * 0.09f
            canvas.drawText(spec.san, contentLeft, y, sanPaint)
            y += height * 0.045f

            val moveNumber = spec.moveNumber ?: spec.ply?.let { (it + 1) / 2 }
            moveNumber?.let { n ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ON_DARK_SECONDARY
                    textSize = height * 0.03f
                }
                canvas.drawText(spec.labels.moveNumber(n), contentLeft, y, paint)
                y += height * 0.06f
            }
            // No classification badge here any more — it is the chip at the top of the panel, and
            // drawing the same verdict twice in one panel is how a viewer starts wondering which
            // of the two is the real one.
        }

        y = drawEvalReadout(canvas, spec, contentLeft, y, height)

        spec.tactic?.let { tactic ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF7C631.toInt()
                textSize = height * 0.032f
                isFakeBoldText = true
            }
            canvas.drawText(spec.labels.tactic(tactic.type), contentLeft, y, paint)
            y += height * 0.06f
        }

        if (spec.recentMoves.isNotEmpty()) {
            y += height * 0.02f
            val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ON_DARK_SECONDARY
                textSize = height * 0.027f
                isFakeBoldText = true
            }
            canvas.drawText(spec.labels.recentMoves, contentLeft, y, headingPaint)
            y += height * 0.045f
            val movePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ON_DARK_PRIMARY
                textSize = height * 0.034f
            }
            val bottomLimit = top + height - height * 0.04f
            for (san in spec.recentMoves.takeLast(5)) {
                if (y > bottomLimit) break
                canvas.drawText(san, contentLeft, y, movePaint)
                y += height * 0.045f
            }
        }
    }

    /**
     * The numeric evaluation for the position this beat is about, plus what the move did to it.
     *
     * The eval bar beside the board already shows the number, but it shows it inside a 40px-wide
     * chip: legible on a phone held close, not on a video someone is watching. The panel repeats
     * it at a readable size and adds the **swing**, which the bar cannot express at all — and the
     * swing is the quantity `NarrationOptions.significanceThresholdCp` filters on, so it is also
     * the answer to "why is the narrator talking about this move".
     *
     * Draws nothing when there is no evaluation (an intro card, a hypothetical line), rather than
     * printing a zero that would read as "equal".
     */
    private fun drawEvalReadout(
        canvas: Canvas,
        spec: BoardFrameSpec,
        contentLeft: Float,
        startY: Float,
        panelHeight: Float,
    ): Float {
        if (spec.evalCp == null && spec.evalMateIn == null) return startY
        var y = startY
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_DARK_SECONDARY
            textSize = panelHeight * 0.027f
            isFakeBoldText = true
        }
        canvas.drawText(spec.labels.evaluation, contentLeft, y, headingPaint)
        y += panelHeight * 0.055f

        val scoreText = EvalFormat.score(spec.evalCp, spec.evalMateIn)
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ON_DARK_PRIMARY
            textSize = panelHeight * 0.062f
            isFakeBoldText = true
        }
        canvas.drawText(scoreText, contentLeft, y, scorePaint)

        spec.evalSwingCp?.let { swing ->
            val swingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // Green when the move helped whoever played it, red when it hurt them. Never the
                // only signal: the number itself is signed and labelled.
                color = swingFavoursMover(swing, spec.speakerColor)
                textSize = panelHeight * 0.036f
                isFakeBoldText = true
            }
            canvas.drawText(
                "swing ${EvalFormat.swing(swing)}",
                contentLeft + scorePaint.measureText(scoreText) + panelHeight * 0.03f,
                y,
                swingPaint,
            )
        }
        return y + panelHeight * 0.055f
    }

    /**
     * Swing is White-relative, so "good" depends on who moved. With no mover known (a card, a
     * held position) it stays neutral rather than guessing.
     */
    private fun swingFavoursMover(swingCp: Int, mover: CoreColor?): Int = when {
        mover == null || swingCp == 0 -> ON_DARK_SECONDARY
        (swingCp > 0) == (mover == CoreColor.WHITE) -> GREEN_PRIMARY
        else -> CHECK_RED
    }

    /**
     * White/Black name + rating, the side to move highlighted, the viewer's own side marked
     * "(you)" via [userColor]. Falls back to a plain "White/Black to move" line when [header] is
     * null (an older cached script, or a card the generator hasn't attached a header to) — never
     * invents a name. Returns the new `y` cursor.
     */
    private fun drawPlayers(
        canvas: Canvas,
        header: VideoGameHeader?,
        speakerColor: CoreColor?,
        userColor: CoreColor?,
        contentLeft: Float,
        startY: Float,
        panelHeight: Float,
        labels: PanelLabels,
    ): Float {
        var y = startY
        val rowHeight = panelHeight * 0.06f
        val nameSize = panelHeight * 0.034f

        if (header != null) {
            drawPlayerRow(canvas, header.whiteName, header.whiteRating, CoreColor.WHITE, speakerColor, userColor, contentLeft, y, nameSize)
            y += rowHeight
            drawPlayerRow(canvas, header.blackName, header.blackRating, CoreColor.BLACK, speakerColor, userColor, contentLeft, y, nameSize)
            y += rowHeight + panelHeight * 0.01f
        } else if (speakerColor != null) {
            val youSuffix = if (userColor != null && userColor == speakerColor) "  ${labels.youMarker}" else ""
            val text = "${labels.toMove(speakerColor)}$youSuffix"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ON_DARK_PRIMARY; textSize = nameSize }
            canvas.drawText(text, contentLeft, y, paint)
            y += rowHeight
        }
        return y
    }

    private fun drawPlayerRow(
        canvas: Canvas,
        name: String,
        rating: Int?,
        rowColor: CoreColor,
        speakerColor: CoreColor?,
        userColor: CoreColor?,
        contentLeft: Float,
        y: Float,
        textSize: Float,
    ) {
        val active = speakerColor == rowColor
        val isYou = userColor != null && userColor == rowColor
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (rowColor == CoreColor.WHITE) WHITE_FILL else BLACK_FILL
            style = Paint.Style.FILL
        }
        val dotRadius = textSize * 0.28f
        canvas.drawCircle(contentLeft + dotRadius, y - textSize * 0.32f, dotRadius, dotPaint)
        if (rowColor == CoreColor.BLACK) {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ON_DARK_SECONDARY; strokeWidth = 1.5f }
            canvas.drawCircle(contentLeft + dotRadius, y - textSize * 0.32f, dotRadius, ring)
        }

        val label = buildString {
            append(name)
            if (rating != null) append(" ($rating)")
            if (isYou) append(" · you")
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) GREEN_PRIMARY else ON_DARK_PRIMARY
            this.textSize = textSize
            isFakeBoldText = active
        }
        canvas.drawText(label, contentLeft + dotRadius * 2 + textSize * 0.4f, y, paint)
    }

    /** The panel's single label chip: its text, its colour, and its size as a fraction of height. */
    internal data class PanelChip(val text: String, val color: Int, val textSizeFraction: Float)

    /**
     * One move, one verdict, one vocabulary.
     *
     * [MoveClassification] is the spec's judgement of the move (ANALYSIS_SPEC §4) and is exactly
     * what the review move list shows, so it wins this chip whenever the segment is about a real
     * played move. [SegmentKind] answers a different question — *why this beat exists* — and
     * buckets MISTAKE, MISS and BLUNDER all into `SegmentKind.BLUNDER`; labelling the move by it
     * is how the panel came to call a "Mistake" a "Blunder" while the move list called the same
     * ply "Mistake". The kind now labels only segments that are about no one classified move:
     * intros, puzzle prompts, and the plies of a hypothetical line.
     *
     * Extracted from [drawPanel] so which of the two labels wins is a thing a test can assert,
     * rather than a branch buried in a draw call that only a screenshot could check.
     */
    internal fun panelChip(spec: BoardFrameSpec): PanelChip? {
        spec.classification?.let { verdict ->
            return PanelChip(
                text = spec.labels.verdict(verdict),
                color = classificationColors[verdict] ?: GREEN_PRIMARY,
                textSizeFraction = 0.036f,
            )
        }
        val kind = spec.segmentKind ?: return null
        return PanelChip(spec.labels.segmentKind(kind), kindAccentColor(kind), 0.032f)
    }

    private fun humanizeKind(kind: SegmentKind): String =
        kind.name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

    private fun kindAccentColor(kind: SegmentKind): Int = when (kind) {
        SegmentKind.BLUNDER, SegmentKind.THREAT_ALLOWED -> 0xFFFA412D.toInt()
        SegmentKind.MISSED_TACTIC -> 0xFFE58F2A.toInt()
        SegmentKind.FOUND_TACTIC, SegmentKind.TURNING_POINT, SegmentKind.KEY_MOMENT -> GREEN_PRIMARY
        SegmentKind.PUZZLE_PROMPT -> 0xFFF7C631.toInt()
        else -> 0xFF61AFEF.toInt()
    }

    /** Draws the chip and returns its height, so callers can advance past it whatever its size. */
    private fun drawChip(canvas: Canvas, text: String, left: Float, top: Float, bgColor: Int, textColor: Int, textSizePx: Float): Float {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSizePx
            isFakeBoldText = true
        }
        val textW = textPaint.measureText(text)
        val paddingH = textSizePx * 0.5f
        val paddingV = textSizePx * 0.35f
        val rect = RectF(left, top, left + textW + paddingH * 2, top + textSizePx + paddingV * 2)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(rect, textSizePx * 0.3f, textSizePx * 0.3f, bgPaint)
        canvas.drawText(text, rect.left + paddingH, rect.top + paddingV + textSizePx * 0.85f, textPaint)
        return rect.height()
    }
}
