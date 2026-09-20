package net.palaya.chessanalyzer.core.pgn

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.SanParseException
import net.palaya.chessanalyzer.core.chess.parseSan

/**
 * Parses PGN (Portable Game Notation) text into [PgnGame]s.
 *
 * This is a hand-rolled recursive-descent parser (not regex-split-the-file) because
 * comments, RAVs and tag values can all contain characters - including braces and
 * parentheses inside `{}` comments quoted in some exports - that make line/regex
 * splitting unreliable. It tolerates a BOM, CRLF or LF line endings, multiple games
 * per file, blank lines and irregular whitespace.
 */
object PgnParser {

    private val RESULT_TOKENS = setOf("1-0", "0-1", "1/2-1/2", "*")
    private val MOVE_NUMBER_REGEX = Regex("^\\d+\\.+$")
    private val CLOCK_REGEX = Regex("\\[%clk\\s+([^]]+)]")
    private val TOKEN_DELIMITERS = charArrayOf('{', '(', ')', ';', '$', '[')

    /** Parses [text], which may contain one or more games, into a list of [PgnGame]. */
    fun parse(text: String): List<PgnGame> {
        val input = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
        val cursor = Cursor(input)
        val games = ArrayList<PgnGame>()
        var gameIndex = 0

        while (true) {
            skipWhitespace(cursor)
            if (cursor.peek() == null) break
            if (cursor.peek() != '[') {
                throw PgnParseException(
                    "Expected a tag section (starting with '[') at offset ${cursor.i}, " +
                        "found '${cursor.peek()}' (game ${gameIndex + 1})"
                )
            }

            val tags = parseTags(cursor, gameIndex)
            val fenTag = tags["FEN"]
            val startFen = if (tags["SetUp"] == "1" && fenTag != null) fenTag else null
            val startPos = try {
                if (startFen != null) Position.fromFen(startFen) else Position.startPosition()
            } catch (e: Exception) {
                throw PgnParseException("Invalid [FEN] tag in game ${gameIndex + 1}: ${e.message}")
            }

            skipWhitespace(cursor)
            val seq = parseMoveSequence(cursor, startPos, gameIndex)
            val result = seq.result ?: tags["Result"] ?: "*"

            games.add(PgnGame(tags = tags, moves = seq.moves, result = result, startFen = startFen))
            gameIndex++
        }

        return games
    }

    // -----------------------------------------------------------------
    // Tag section
    // -----------------------------------------------------------------

    private fun parseTags(cursor: Cursor, gameIndex: Int): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        while (true) {
            skipWhitespace(cursor)
            if (cursor.peek() != '[') break

            cursor.advance() // '['
            skipInlineWhitespace(cursor)
            val keyStart = cursor.i
            while (cursor.peek() != null && !cursor.peek()!!.isWhitespace()) cursor.advance()
            val key = cursor.text.substring(keyStart, cursor.i)
            skipInlineWhitespace(cursor)

            if (cursor.peek() != '"') {
                throw PgnParseException("Malformed tag pair '[$key' in game ${gameIndex + 1}: expected opening quote")
            }
            cursor.advance() // opening quote
            val sb = StringBuilder()
            while (cursor.peek() != null && cursor.peek() != '"') {
                val c = cursor.advance()
                if (c == '\\' && cursor.peek() != null) {
                    sb.append(cursor.advance())
                } else {
                    sb.append(c)
                }
            }
            if (cursor.peek() != '"') {
                throw PgnParseException("Unterminated tag value for '$key' in game ${gameIndex + 1}")
            }
            cursor.advance() // closing quote
            skipInlineWhitespace(cursor)
            if (cursor.peek() != ']') {
                throw PgnParseException("Malformed tag pair for '$key' in game ${gameIndex + 1}: expected ']'")
            }
            cursor.advance() // ']'
            tags[key] = sb.toString()
        }
        return tags
    }

    // -----------------------------------------------------------------
    // Movetext
    // -----------------------------------------------------------------

    private class MoveSeqResult(val moves: List<PgnMove>, val result: String?)

    private fun parseMoveSequence(cursor: Cursor, startPos: Position, gameIndex: Int): MoveSeqResult {
        val moves = ArrayList<PgnMove>()
        var pos = startPos
        var posBeforeLastMove: Position? = null
        var result: String? = null

        fun attachComment(text: String) {
            if (moves.isEmpty()) return
            val last = moves.last()
            val merged = if (last.comment == null) text else "${last.comment} $text"
            val clock = CLOCK_REGEX.find(text)?.groupValues?.get(1)?.trim() ?: last.clock
            moves[moves.size - 1] = last.copy(comment = merged, clock = clock)
        }

        loop@ while (true) {
            skipWhitespace(cursor)
            val c = cursor.peek() ?: break@loop

            when (c) {
                ')' -> break@loop
                '[' -> break@loop // next game's tags started without a result token
                '{' -> {
                    cursor.advance()
                    val start = cursor.i
                    while (cursor.peek() != null && cursor.peek() != '}') cursor.advance()
                    val commentText = cursor.text.substring(start, cursor.i)
                    if (cursor.peek() == '}') cursor.advance()
                    attachComment(commentText.trim())
                }
                ';' -> {
                    cursor.advance()
                    val start = cursor.i
                    while (cursor.peek() != null && cursor.peek() != '\n') cursor.advance()
                    attachComment(cursor.text.substring(start, cursor.i).trim())
                }
                '$' -> {
                    cursor.advance()
                    val start = cursor.i
                    while (cursor.peek()?.isDigit() == true) cursor.advance()
                    val nag = cursor.text.substring(start, cursor.i).toIntOrNull()
                    if (nag != null && moves.isNotEmpty()) {
                        val last = moves.last()
                        moves[moves.size - 1] = last.copy(nags = last.nags + nag)
                    }
                }
                '(' -> {
                    cursor.advance()
                    val branchPoint = posBeforeLastMove
                    if (branchPoint == null) {
                        throw PgnParseException(
                            "Variation '(' with no preceding move to vary, in game ${gameIndex + 1}",
                            ply = moves.size
                        )
                    }
                    val sub = parseMoveSequence(cursor, branchPoint, gameIndex)
                    if (cursor.peek() == ')') cursor.advance()
                    else throw PgnParseException("Unterminated variation in game ${gameIndex + 1}", ply = moves.size)
                    if (moves.isNotEmpty()) {
                        val last = moves.last()
                        moves[moves.size - 1] = last.copy(variations = last.variations + listOf(sub.moves))
                    }
                }
                else -> {
                    val tokenStart = cursor.i
                    while (true) {
                        val p = cursor.peek() ?: break
                        if (p.isWhitespace() || p in TOKEN_DELIMITERS) break
                        cursor.advance()
                    }
                    if (cursor.i == tokenStart) {
                        // Unrecognized stray character; skip it to guarantee progress.
                        cursor.advance()
                        continue@loop
                    }
                    val token = cursor.text.substring(tokenStart, cursor.i)
                    when {
                        token in RESULT_TOKENS -> {
                            result = token
                            break@loop
                        }
                        MOVE_NUMBER_REGEX.matches(token) -> {
                            // Move number indicator (e.g. "12." or "12..."); no state change needed,
                            // side-to-move is tracked by `pos` itself.
                        }
                        else -> {
                            val move = try {
                                pos.parseSan(token)
                            } catch (e: SanParseException) {
                                throw PgnParseException(
                                    "Illegal or unparsable move '$token' at ply ${moves.size + 1} " +
                                        "(move ${pos.fullmoveNumber}, ${pos.sideToMove}) in game ${gameIndex + 1}: ${e.message}",
                                    ply = moves.size + 1,
                                    moveText = token
                                )
                            } catch (e: IllegalArgumentException) {
                                throw PgnParseException(
                                    "Illegal or unparsable move '$token' at ply ${moves.size + 1} " +
                                        "(move ${pos.fullmoveNumber}, ${pos.sideToMove}) in game ${gameIndex + 1}: ${e.message}",
                                    ply = moves.size + 1,
                                    moveText = token
                                )
                            }
                            val fenBefore = pos.toFen()
                            val color = pos.sideToMove
                            val moveNumber = pos.fullmoveNumber
                            val next = pos.makeMove(move)
                            moves.add(
                                PgnMove(
                                    san = token,
                                    uci = move.toUci(),
                                    moveNumber = moveNumber,
                                    color = color,
                                    positionFenBefore = fenBefore,
                                    positionFenAfter = next.toFen()
                                )
                            )
                            posBeforeLastMove = pos
                            pos = next
                        }
                    }
                }
            }
        }

        return MoveSeqResult(moves, result)
    }

    // -----------------------------------------------------------------
    // Character-level helpers
    // -----------------------------------------------------------------

    private fun skipWhitespace(cursor: Cursor) {
        while (cursor.peek()?.isWhitespace() == true) cursor.advance()
    }

    private fun skipInlineWhitespace(cursor: Cursor) {
        while (cursor.peek() == ' ' || cursor.peek() == '\t') cursor.advance()
    }

    private class Cursor(val text: String) {
        var i = 0
        fun peek(): Char? = if (i < text.length) text[i] else null
        fun advance(): Char = text[i++]
    }
}
