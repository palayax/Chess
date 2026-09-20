package net.palaya.chessanalyzer.core.pgn

import net.palaya.chessanalyzer.core.chess.Color

/**
 * A single played move within a PGN movetext, enriched with the positions before
 * and after it so downstream code never has to replay the game itself just to know
 * "what was the board like here".
 *
 * @property san the move exactly as written in the source (may include trailing
 *   check/mate marks and annotation glyphs, e.g. "Qxf7+!").
 * @property uci the same move in UCI long-algebraic form, e.g. "f3f7".
 * @property moveNumber the full-move number this ply belongs to (same for White's
 *   and Black's move of a given move pair).
 * @property color which side played this move.
 * @property positionFenBefore FEN of the position before this move.
 * @property positionFenAfter FEN of the position after this move.
 * @property clock clock value extracted from a `{[%clk H:MM:SS(.f)]}` comment, if present.
 * @property comment the raw `{...}`/`;...` comment text attached to this move, if any
 *   (comments from multiple sources for the same move are concatenated).
 * @property nags Numeric Annotation Glyph codes (`$1`, `$2`, ...) attached to this move.
 * @property variations alternative continuations (RAVs) branching off from the
 *   position *before* this move; each is itself a list of PgnMoves, recursively
 *   carrying its own nested variations.
 */
data class PgnMove(
    val san: String,
    val uci: String,
    val moveNumber: Int,
    val color: Color,
    val positionFenBefore: String,
    val positionFenAfter: String,
    val clock: String? = null,
    val comment: String? = null,
    val nags: List<Int> = emptyList(),
    val variations: List<List<PgnMove>> = emptyList()
)

/**
 * One parsed PGN game.
 *
 * @property tags all tag pairs in file order (Seven Tag Roster plus any extras,
 *   e.g. chess.com's WhiteElo/TimeControl/Termination/ECO/UTCDate/UTCTime/Link).
 * @property moves the main line, in order.
 * @property result the game result token ("1-0", "0-1", "1/2-1/2" or "*"), taken
 *   from the trailing movetext token if present, else the Result tag, else "*".
 * @property startFen non-null only when the game began from a custom position
 *   (i.e. `[SetUp "1"]` + `[FEN "..."]` tags were present); the FEN tag's value.
 */
data class PgnGame(
    val tags: Map<String, String>,
    val moves: List<PgnMove>,
    val result: String,
    val startFen: String?
)

/**
 * Thrown when a PGN document is structurally malformed, or its movetext contains
 * a SAN token that cannot be parsed or is not a legal move in context.
 */
class PgnParseException(
    message: String,
    val ply: Int = -1,
    val moveText: String? = null
) : Exception(message)
