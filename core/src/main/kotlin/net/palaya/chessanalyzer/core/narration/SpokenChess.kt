package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseUci

/**
 * Turns board facts into [SpokenMove]s — the language-neutral description of a move that a
 * [NarrationStrings] locale then puts into words.
 *
 * The narration track is spoken, never read, so notation is useless in it: "Nxf3" comes out of a
 * TTS voice as a spelled-out letter salad. Everything the narrator says about a move starts from
 * a [SpokenMove] built here, and the notation goes in the caption instead. See [NotationGuard]
 * for the backstop that catches anything that slips past. No words live in this file.
 */
object SpokenChess {

    /**
     * The facts of [move] played from [position] (the position BEFORE the move): the victim on
     * the target square, whether a second piece of the same type could reach it (in which case the
     * origin must be spoken — the equivalent of SAN disambiguation), and whether it gives check
     * or mate.
     */
    fun describe(position: Position, move: Move): SpokenMove {
        val ambiguous = !move.isCastle && position.legalMoves().any {
            it.piece == move.piece && it.to == move.to && it.from != move.from
        }
        return SpokenMove(
            color = move.color,
            piece = move.piece,
            from = move.from,
            to = move.to,
            castle = when {
                move.isCastleKingside -> CastleSide.KINGSIDE
                move.isCastleQueenside -> CastleSide.QUEENSIDE
                else -> null
            },
            isCapture = move.isCapture,
            captured = if (move.isCapture && !move.isEnPassant) position.pieceAt(move.to)?.type else null,
            enPassant = move.isEnPassant,
            promotion = move.promotion,
            ambiguous = ambiguous,
            outcome = outcome(position, move)
        )
    }

    /** Check / mate / nothing, read off the position after the move. */
    fun outcome(position: Position, move: Move): MoveOutcome {
        val after = try {
            position.makeMove(move)
        } catch (e: Exception) {
            return MoveOutcome.NONE
        }
        return when {
            after.isCheckmate() -> MoveOutcome.CHECKMATE
            after.isInCheck() -> MoveOutcome.CHECK
            else -> MoveOutcome.NONE
        }
    }

    /** The facts of a UCI move in [position], or null if it is not legal there. */
    fun describeUci(position: Position, uci: String): SpokenMove? {
        val move = moveOrNull(position, uci) ?: return null
        return describe(position, move)
    }

    fun moveOrNull(position: Position, uci: String): Move? = try {
        position.parseUci(uci)
    } catch (e: Exception) {
        null
    }

    /**
     * A whole variation as facts, at most [maxPlies] long. Stops at the first move that will not
     * parse, so a truncated or stale PV degrades to the part of the line that is still legal
     * rather than throwing, and stops after a mate.
     */
    fun describeLine(start: Position, uciMoves: List<String>, maxPlies: Int = 4): List<SpokenMove> {
        val out = ArrayList<SpokenMove>()
        var pos = start
        for (uci in uciMoves) {
            if (out.size >= maxPlies) break
            val move = moveOrNull(pos, uci) ?: break
            out.add(describe(pos, move))
            pos = pos.makeMove(move)
            if (pos.isCheckmate()) break
        }
        return out
    }

    fun squareOrNull(algebraic: String): Square? {
        val s = algebraic.trim()
        if (s.length != 2) return null
        val file = s[0].lowercaseChar() - 'a'
        val rank = s[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        return Square.of(file, rank)
    }
}
