package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.analysis.MaterialBalance
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.ScriptTiming
import net.palaya.chessanalyzer.core.narration.SegmentLeadIn
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.data.mapper.fenToBoardState
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.model.Square
import net.palaya.chessanalyzer.ui.model.algebraicToSquare

/** What one instant within a segment should render as. */
sealed interface RenderInstruction {
    data class Board(val spec: BoardFrameRenderer.BoardFrameSpec) : RenderInstruction
    /** A title card: its words as [CardContent] (fitted by the renderer) and the caption bar under it ("" = none). */
    data class Card(val content: CardContent, val caption: String) : RenderInstruction
}

/**
 * Turns a [ScriptSegment] plus "how far into it we are" into a concrete [RenderInstruction],
 * including move/line slide animation and the side-panel context fields. Shared by
 * [VideoExporter] and in-app playback so both consumers of one [VideoScript] drive pixel-identical
 * visuals off the same logic instead of two copies drifting apart.
 */
object SegmentFrameBuilder {

    /** How long a single move's slide animation takes, honouring the "~400ms, not a snap" spec. */
    const val MOVE_ANIMATION_MS = ScriptTiming.MOVE_ANIMATION_MS

    /** How many previously-played moves the side panel's "recent moves" list shows. */
    private const val RECENT_MOVES_LIMIT = 5

    /** The most recent chapter whose `startSegmentIndex` has been reached, for the panel header. */
    fun chapterLabelFor(script: VideoScript, segmentIndex: Int): String? {
        var label: String? = null
        for (chapter in script.chapters) {
            if (chapter.startSegmentIndex <= segmentIndex) label = chapter.title else break
        }
        return label
    }

    /** SAN of every `PlayMove` segment (and lead-in approach move) up to and including [uptoIndex], most recent last. */
    private fun recentPlayedSans(script: VideoScript, uptoIndex: Int): List<String> {
        val result = ArrayList<String>()
        for (seg in script.segments) {
            if (seg.index > uptoIndex) break
            // The game moves a key move's lead-in played first (ANALYSIS_SPEC 9.8) come before it.
            seg.leadIn?.let { result.addAll(it.approachSan) }
            // The engine's best line (V4) was never played: it is not one of the game's recent moves.
            if (seg.kind == SegmentKind.BEST_LINE) continue
            val directive = seg.board
            if (directive is BoardDirective.PlayMove) result.add(directive.san)
        }
        return result.takeLast(RECENT_MOVES_LIMIT)
    }

    /**
     * @param elapsedMs time since the segment started, lead-in included: while it is inside
     *   [ScriptSegment.leadIn] the lead-in is drawn (ANALYSIS_SPEC 9.8), and the segment's own board
     *   starts after it, so a PlayMove slides exactly when its narration starts.
     *
     * Every board frame carries the material of the position it shows ([MaterialBalance], V4): the pieces
     * each side has captured and "+N" for the side ahead, chess.com style, counted from the pieces it draws.
     */
    fun build(
        script: VideoScript,
        segment: ScriptSegment,
        elapsedMs: Long,
        orientation: BoardOrientation,
        /** Resolved once per frame stream by the caller — see [BoardFrameRenderer.PanelLabels]. */
        labels: BoardFrameRenderer.PanelLabels = BoardFrameRenderer.PanelLabels.ENGLISH,
    ): RenderInstruction {
        val leadIn = segment.leadIn
        val instruction = if (leadIn != null && elapsedMs < leadIn.durationMs) {
            buildLeadIn(script, segment, leadIn, elapsedMs, orientation, labels)
        } else {
            buildBoard(script, segment, (elapsedMs - segment.leadInMs).coerceAtLeast(0L), orientation, labels)
        }
        return withMaterial(instruction)
    }

    /** The material of the position a board frame shows, counted from the pieces it draws. */
    private fun withMaterial(instruction: RenderInstruction): RenderInstruction {
        if (instruction !is RenderInstruction.Board) return instruction
        val pieces = instruction.spec.boardState.pieces.values.map { p ->
            net.palaya.chessanalyzer.core.chess.Piece(
                net.palaya.chessanalyzer.core.chess.PieceType.valueOf(p.type.name),
                if (p.color == net.palaya.chessanalyzer.ui.model.PieceColor.WHITE) net.palaya.chessanalyzer.core.chess.Color.WHITE
                else net.palaya.chessanalyzer.core.chess.Color.BLACK,
            )
        }
        return RenderInstruction.Board(instruction.spec.copy(material = MaterialBalance.of(pieces)))
    }

    /** The segment's own board, [elapsedMs] after its lead-in (if any) ended. */
    private fun buildBoard(
        script: VideoScript,
        segment: ScriptSegment,
        elapsedMs: Long,
        orientation: BoardOrientation,
        labels: BoardFrameRenderer.PanelLabels,
    ): RenderInstruction {
        val chapterLabel = chapterLabelFor(script, segment.index)
        // Common panel context, independent of which BoardDirective this segment carries — merged
        // onto each branch's spec below via `copy`.
        val panelDefaults = BoardFrameRenderer.BoardFrameSpec(
            boardState = net.palaya.chessanalyzer.ui.model.BoardState.empty(),
            labels = labels,
            chapterLabel = chapterLabel,
            segmentKind = segment.kind,
            speakerColor = segment.speakerColor,
            userColor = script.userColor,
            tactic = segment.tactic,
            ply = segment.ply,
            header = script.header,
            evalWinPercentWhite = segment.eval?.winPercentWhite,
            evalCp = segment.eval?.evalCp,
            evalMateIn = segment.eval?.mateIn,
            moveNumber = segment.moveNumber,
            evalSwingCp = segment.evalSwingCp,
            // Carried on every segment shape, not just PlayMove: the beat that *talks about* an
            // error annotates the position rather than replaying the move, and that frame needs
            // the verdict too or the panel falls back to labelling the move by its SegmentKind.
            classification = segment.classification,
        )

        val result = when (val directive = segment.board) {
            is BoardDirective.Hold -> RenderInstruction.Board(
                panelDefaults.copy(
                    boardState = fenToBoardState(directive.fen),
                    orientation = orientation,
                    checkedKingSquare = checkedSquare(directive.fen),
                    caption = segment.caption,
                )
            )

            is BoardDirective.PlayMove -> {
                val progress = easeInOut((elapsedMs.toFloat() / MOVE_ANIMATION_MS).coerceIn(0f, 1f))
                val fromTo = uciSquares(directive.uci)
                val beforeState = fenToBoardState(directive.fen)
                val afterFen = safeApply(directive.fen, directive.uci)
                val settled = elapsedMs >= MOVE_ANIMATION_MS
                val boardState = if (settled) fenToBoardState(afterFen) else beforeState
                val animating = if (!settled && fromTo != null) {
                    beforeState.pieces[fromTo.first]?.let { BoardFrameRenderer.AnimatingPiece(it, fromTo.first, fromTo.second, progress) }
                } else null
                RenderInstruction.Board(
                    panelDefaults.copy(
                        boardState = boardState,
                        orientation = orientation,
                        lastMove = fromTo,
                        checkedKingSquare = if (settled) checkedSquare(afterFen) else null,
                        animating = animating,
                        // The directive stays the local source of truth where it has one; the
                        // segment's is the same annotation's verdict, and is null for the
                        // hypothetical plies of an excursion, which were never classified.
                        classification = directive.classification ?: segment.classification,
                        caption = segment.caption,
                        san = directive.san,
                        recentMoves = recentPlayedSans(script, segment.index),
                    )
                )
            }

            is BoardDirective.PlayLine -> buildPlayLine(directive, segment, elapsedMs, orientation, panelDefaults)

            is BoardDirective.Annotate -> RenderInstruction.Board(
                panelDefaults.copy(
                    boardState = fenToBoardState(directive.fen),
                    orientation = orientation,
                    highlightSquares = directive.highlightSquares.mapNotNull { algebraicToSquare(it) },
                    arrows = directive.arrows.mapNotNull { spec ->
                        val from = algebraicToSquare(spec.fromSquare)
                        val to = algebraicToSquare(spec.toSquare)
                        if (from != null && to != null) BoardFrameRenderer.FrameArrow(from, to, spec.role) else null
                    },
                    checkedKingSquare = checkedSquare(directive.fen),
                    caption = segment.caption,
                    recentMoves = recentPlayedSans(script, segment.index),
                )
            )

            // V4: a card with a position (the intro) is that position with the card's words on the board, so
            // the board is on screen from the first frame; the words are the same fitted card (R6c).
            is BoardDirective.Card -> if (directive.boardFen != null) {
                RenderInstruction.Board(
                    panelDefaults.copy(
                        boardState = fenToBoardState(directive.boardFen!!),
                        orientation = orientation,
                        titleCard = CardContents.forSegment(script, segment.kind, directive.heading, directive.lines, labels.recap.className, labels.recap.side),
                        caption = CardContents.captionFor(segment.kind, segment.caption),
                    )
                )
            } else {
                // R6c: the words of a title card come from CardContents (each fact once, whole-percent accuracy,
                // names isolated), and the intro and the final numbers carry no caption bar (the card says it).
                RenderInstruction.Card(
                    CardContents.forSegment(script, segment.kind, directive.heading, directive.lines, labels.recap.className, labels.recap.side),
                    CardContents.captionFor(segment.kind, segment.caption),
                )
            }
        }

        // V2 + V4: a move of the engine's best line, played and spoken after its key moment. Drawn as an
        // excursion (the tinted border and the best-line chip) so the viewer can tell it from the game; no
        // verdict or kind chip, because none of these moves was played or classified.
        if (segment.kind == SegmentKind.BEST_LINE && result is RenderInstruction.Board) {
            return RenderInstruction.Board(
                result.spec.copy(excursionActive = true, excursionLabel = labels.bestLine, segmentKind = null, classification = null)
            )
        }

        // A missed-tactic excursion can now be more than one segment (a pivot-in beat, one
        // PlayMove per ply of the hypothetical line, a payoff beat) — core/narration is free to
        // carry it on any BoardDirective, not just PlayLine (which only [buildPlayLine] above
        // tints). Driving the tint off `segment.kind` instead of the directive type means every
        // shape of excursion gets the "what you could aim for" styling while MISSED_TACTIC is the
        // reason for the segment, and — just as importantly — automatically stops as soon as a
        // later segment's kind moves on (the pivot-out beat and the real move), so the viewer can
        // tell the detour ended without this needing to know how core sequences those segments.
        return if (segment.kind == SegmentKind.MISSED_TACTIC && result is RenderInstruction.Board && !result.spec.excursionActive) {
            RenderInstruction.Board(
                result.spec.copy(
                    excursionActive = true,
                    excursionLabel = result.spec.excursionLabel ?: labels.excursionDefault,
                )
            )
        } else {
            result
        }
    }

    /**
     * The silent lead-in before a key move (ANALYSIS_SPEC 9.8): first the skipped game moves, one every
     * [SegmentLeadIn.stepMs], each sliding for [MOVE_ANIMATION_MS] and then resting with its own caption;
     * then the position before the key move, still, with the move's two squares lit and the last
     * approach move still marked. The eval bar shows the position before the key move, and the panel
     * shows no verdict yet: the key move has not been played (its badge appears with it, as in a
     * review); the chip reads "Key moment" during the pause and is absent while the skipped moves play.
     */
    private fun buildLeadIn(
        script: VideoScript,
        segment: ScriptSegment,
        leadIn: SegmentLeadIn,
        elapsedMs: Long,
        orientation: BoardOrientation,
        labels: BoardFrameRenderer.PanelLabels,
    ): RenderInstruction {
        val base = BoardFrameRenderer.BoardFrameSpec(
            boardState = net.palaya.chessanalyzer.ui.model.BoardState.empty(),
            labels = labels,
            chapterLabel = chapterLabelFor(script, segment.index),
            // No verdict yet: the chip says "Key moment" during the pause and nothing while the skipped moves play.
            segmentKind = null,
            speakerColor = segment.speakerColor,
            userColor = script.userColor,
            ply = segment.ply,
            header = script.header,
            evalWinPercentWhite = leadIn.eval?.winPercentWhite ?: segment.eval?.winPercentWhite,
            evalCp = leadIn.eval?.evalCp ?: segment.eval?.evalCp,
            evalMateIn = leadIn.eval?.mateIn ?: segment.eval?.mateIn,
            moveNumber = segment.moveNumber,
            orientation = orientation,
        )
        val before = recentPlayedSans(script, segment.index - 1)
        // The approach moves are game moves too: the panel's "recent moves" lists each once it has been played.
        fun recent(played: Int) = (before + leadIn.approachSan.take(played)).takeLast(RECENT_MOVES_LIMIT)
        val steps = leadIn.approachUci
        var pos = Position.fromFen(leadIn.fen)
        if (steps.isNotEmpty() && leadIn.stepMs > 0 && elapsedMs < leadIn.approachMs) {
            val stepIndex = (elapsedMs / leadIn.stepMs).toInt().coerceIn(0, steps.size - 1)
            val local = elapsedMs - stepIndex * leadIn.stepMs
            for (i in 0 until stepIndex) pos = safeMakeMove(pos, steps[i])
            val beforeState = fenToBoardState(pos.toFen())
            val fromTo = uciSquares(steps[stepIndex])
            val settled = local >= MOVE_ANIMATION_MS
            val afterPos = safeMakeMove(pos, steps[stepIndex])
            val animating = if (!settled && fromTo != null) {
                val progress = easeInOut((local.toFloat() / MOVE_ANIMATION_MS).coerceIn(0f, 1f))
                beforeState.pieces[fromTo.first]?.let { BoardFrameRenderer.AnimatingPiece(it, fromTo.first, fromTo.second, progress) }
            } else null
            return RenderInstruction.Board(
                base.copy(
                    boardState = if (settled) fenToBoardState(afterPos.toFen()) else beforeState,
                    lastMove = fromTo,
                    animating = animating,
                    checkedKingSquare = if (settled) checkedSquare(afterPos.toFen()) else null,
                    caption = leadIn.approachCaptions.getOrNull(stepIndex) ?: segment.caption,
                    san = leadIn.approachSan.getOrNull(stepIndex),
                    recentMoves = recent(if (settled) stepIndex + 1 else stepIndex),
                )
            )
        }
        for (uci in steps) pos = safeMakeMove(pos, uci)
        val fen = pos.toFen()
        return RenderInstruction.Board(
            base.copy(
                boardState = fenToBoardState(fen),
                segmentKind = SegmentKind.KEY_MOMENT,
                lastMove = steps.lastOrNull()?.let { uciSquares(it) },
                highlightSquares = leadIn.highlightSquares.mapNotNull { algebraicToSquare(it) },
                checkedKingSquare = checkedSquare(fen),
                caption = segment.caption,
                recentMoves = recent(steps.size),
            )
        )
    }

    private fun buildPlayLine(
        directive: BoardDirective.PlayLine,
        segment: ScriptSegment,
        elapsedMs: Long,
        orientation: BoardOrientation,
        panelDefaults: BoardFrameRenderer.BoardFrameSpec,
    ): RenderInstruction {
        val steps = directive.uciMoves
        if (steps.isEmpty()) {
            return RenderInstruction.Board(
                panelDefaults.copy(
                    boardState = fenToBoardState(directive.fen),
                    orientation = orientation,
                    excursionActive = true,
                    excursionLabel = directive.label,
                    caption = segment.caption,
                )
            )
        }
        // Give each step an even slice of the animation budget, capped at MOVE_ANIMATION_MS so a
        // long excursion doesn't force a slow-motion crawl through every move.
        val perStepMs = MOVE_ANIMATION_MS
        val stepPosition = (elapsedMs.toFloat() / perStepMs).coerceIn(0f, steps.size.toFloat())
        val stepIndex = stepPosition.toInt().coerceIn(0, steps.size - 1)
        val stepProgress = easeInOut((stepPosition - stepIndex).coerceIn(0f, 1f))
        val stepSettled = stepIndex == steps.size - 1 && stepPosition >= steps.size.toFloat()

        var pos = Position.fromFen(directive.fen)
        for (i in 0 until stepIndex) pos = safeMakeMove(pos, steps[i])
        val beforeState = fenToBoardState(pos.toFen())
        val fromTo = uciSquares(steps[stepIndex])
        val afterPos = safeMakeMove(pos, steps[stepIndex])

        val boardState = if (stepSettled) fenToBoardState(afterPos.toFen()) else beforeState
        val animating = if (!stepSettled && fromTo != null) {
            beforeState.pieces[fromTo.first]?.let { BoardFrameRenderer.AnimatingPiece(it, fromTo.first, fromTo.second, stepProgress) }
        } else null

        return RenderInstruction.Board(
            panelDefaults.copy(
                boardState = boardState,
                orientation = orientation,
                lastMove = fromTo,
                animating = animating,
                excursionActive = true,
                excursionLabel = directive.label,
                caption = segment.caption,
            )
        )
    }

    private fun easeInOut(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return if (c < 0.5f) 2f * c * c else 1f - (-2f * c + 2f).let { it * it } / 2f
    }

    private fun uciSquares(uci: String): Pair<Square, Square>? {
        if (uci.length < 4) return null
        val from = algebraicToSquare(uci.substring(0, 2)) ?: return null
        val to = algebraicToSquare(uci.substring(2, 4)) ?: return null
        return from to to
    }

    private fun safeApply(fen: String, uci: String): String = try {
        val pos = Position.fromFen(fen)
        pos.makeMove(pos.parseUci(uci)).toFen()
    } catch (e: Exception) {
        fen
    }

    private fun safeMakeMove(pos: Position, uci: String): Position = try {
        pos.makeMove(pos.parseUci(uci))
    } catch (e: Exception) {
        pos
    }

    private fun checkedSquare(fen: String): Square? = try {
        val pos = Position.fromFen(fen)
        if (pos.isInCheck()) pos.kingSquare(pos.sideToMove).toUiSquare() else null
    } catch (e: Exception) {
        null
    }
}
