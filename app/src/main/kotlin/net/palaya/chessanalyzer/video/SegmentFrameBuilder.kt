package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
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
    const val MOVE_ANIMATION_MS = 400L

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

    /** SAN of every `PlayMove` segment up to and including [uptoIndex], most recent last. */
    private fun recentPlayedSans(script: VideoScript, uptoIndex: Int): List<String> {
        val result = ArrayList<String>()
        for (seg in script.segments) {
            if (seg.index > uptoIndex) break
            val directive = seg.board
            if (directive is BoardDirective.PlayMove) result.add(directive.san)
        }
        return result.takeLast(RECENT_MOVES_LIMIT)
    }

    fun build(
        script: VideoScript,
        segment: ScriptSegment,
        elapsedMs: Long,
        orientation: BoardOrientation,
        /** Resolved once per frame stream by the caller — see [BoardFrameRenderer.PanelLabels]. */
        labels: BoardFrameRenderer.PanelLabels = BoardFrameRenderer.PanelLabels.ENGLISH,
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

            // R6c: the words of a title card come from CardContents (each fact once, whole-percent accuracy,
            // names isolated), and the intro and the final numbers carry no caption bar (the card says it).
            is BoardDirective.Card -> RenderInstruction.Card(
                CardContents.forSegment(script, segment.kind, directive.heading, directive.lines),
                CardContents.captionFor(segment.kind, segment.caption),
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
