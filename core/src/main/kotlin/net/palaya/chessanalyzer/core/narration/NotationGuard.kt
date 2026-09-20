package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Square

/**
 * The backstop for the single hardest quality rule in this feature: nothing that reaches the
 * text-to-speech engine may contain algebraic notation.
 *
 * Every narration string is built from [SpokenMove] facts and should already be clean, but
 * narration also splices in text we do not control — player names off the PGN tags, opening
 * names from the book — so the generator runs [scrub] over every finished line. The guard only
 * *parses* the notation it finds; the words come from the locale's [SpokenVocabulary], so a
 * Hebrew review that quotes a chess.com username containing "Nf3" still speaks it in Hebrew.
 * Captions are deliberately NOT scrubbed: they are read on screen, where notation is the clearest
 * form there is.
 */
object NotationGuard {

    private val CASTLE_LONG = Regex("\\b[O0]-[O0]-[O0]\\b")
    private val CASTLE_SHORT = Regex("\\b[O0]-[O0]\\b")

    /** Piece moves: Nf3, Rad1, Qxh5+, R1xd7. */
    private val PIECE_MOVE =
        Regex("\\b([KQRBN])([a-h])?([1-8])?(x?)([a-h][1-8])(?:=([QRBN]))?([+#])?")

    /** Pawn captures: dxe5, exd8=Q+. */
    private val PAWN_CAPTURE =
        Regex("\\b([a-h])x([a-h][1-8])(?:=([QRBN]))?([+#])?")

    /** Pawn promotions: e8=Q. */
    private val PAWN_PROMOTION =
        Regex("\\b([a-h][1-8])=([QRBN])([+#])?")

    /** A pawn push carrying a check mark: e4+, d1#. */
    private val PAWN_PUSH_CHECK =
        Regex("\\b([a-h][1-8])([+#])")

    /** A bare square that survived everything else: "e4" -> "e four". */
    private val BARE_SQUARE = Regex("\\b([a-h])([1-8])\\b")

    /** The test-facing definition of "this is notation". Mirrors the guard the tests assert with. */
    private val DETECT = Regex(
        "\\b[KQRBN][a-h]?[1-8]?x?[a-h][1-8]\\b" +
            "|\\b[a-h]x[a-h][1-8]\\b" +
            "|\\b[O0]-[O0](-[O0])?\\b"
    )

    fun containsNotation(text: String): Boolean = DETECT.containsMatchIn(text)

    /**
     * Rewrites any algebraic notation in [text] into speakable words from [vocabulary].
     * Idempotent for English: the output has no digit-adjacent file letters left, so a second
     * pass changes nothing.
     */
    fun scrub(text: String, vocabulary: SpokenVocabulary = EnglishNarration.Vocabulary): String {
        var out = text
        out = CASTLE_LONG.replace(out) { vocabulary.spokenNotation(ParsedNotation.Castle(CastleSide.QUEENSIDE)) }
        out = CASTLE_SHORT.replace(out) { vocabulary.spokenNotation(ParsedNotation.Castle(CastleSide.KINGSIDE)) }
        out = PIECE_MOVE.replace(out) { m ->
            vocabulary.spokenNotation(
                ParsedNotation.PieceMove(
                    piece = pieceOf(m.groupValues[1][0]),
                    fromFile = m.groupValues[2].firstOrNull(),
                    fromRank = m.groupValues[3].firstOrNull()?.let { it - '0' },
                    capture = m.groupValues[4].isNotEmpty(),
                    to = Square.fromAlgebraic(m.groupValues[5]),
                    promotion = m.groupValues[6].firstOrNull()?.let(::pieceOf),
                    outcome = outcomeOf(m.groupValues[7])
                )
            )
        }
        out = PAWN_CAPTURE.replace(out) { m ->
            vocabulary.spokenNotation(
                ParsedNotation.PawnCapture(
                    fromFile = m.groupValues[1][0],
                    to = Square.fromAlgebraic(m.groupValues[2]),
                    promotion = m.groupValues[3].firstOrNull()?.let(::pieceOf),
                    outcome = outcomeOf(m.groupValues[4])
                )
            )
        }
        out = PAWN_PROMOTION.replace(out) { m ->
            vocabulary.spokenNotation(
                ParsedNotation.PawnPush(
                    to = Square.fromAlgebraic(m.groupValues[1]),
                    promotion = pieceOf(m.groupValues[2][0]),
                    outcome = outcomeOf(m.groupValues[3])
                )
            )
        }
        out = PAWN_PUSH_CHECK.replace(out) { m ->
            vocabulary.spokenNotation(
                ParsedNotation.PawnPush(
                    to = Square.fromAlgebraic(m.groupValues[1]),
                    promotion = null,
                    outcome = outcomeOf(m.groupValues[2])
                )
            )
        }
        out = BARE_SQUARE.replace(out) { m ->
            vocabulary.spokenNotation(ParsedNotation.BareSquare(Square.fromAlgebraic(m.value)))
        }
        return out
    }

    private fun outcomeOf(mark: String): MoveOutcome = when (mark) {
        "+" -> MoveOutcome.CHECK
        "#" -> MoveOutcome.CHECKMATE
        else -> MoveOutcome.NONE
    }

    private fun pieceOf(letter: Char): PieceType = when (letter.uppercaseChar()) {
        'K' -> PieceType.KING
        'Q' -> PieceType.QUEEN
        'R' -> PieceType.ROOK
        'B' -> PieceType.BISHOP
        'N' -> PieceType.KNIGHT
        else -> PieceType.PAWN
    }
}
