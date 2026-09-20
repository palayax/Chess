package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-validates `fixtures/tactic_references.json` — the corpus `scripts/verify_tactic_references.py`
 * produced with python-chess — against **this project's own** perft-verified move generator, FEN
 * parser and SAN writer. Two independent engines have to agree on every position before it is
 * shown to a learner as "this is a fork"; if they ever disagree, one of them has a bug worth knowing
 * about, and this test is where it surfaces.
 *
 * Also pins the generated [TacticReferenceCorpus] to the JSON, so the app cannot ship a position the
 * script did not verify.
 */
class TacticReferenceCorpusTest {

    private data class JsonEntry(val tactic: String, val fen: String, val solution: List<String>, val teachingPoint: String)

    private val json: List<JsonEntry> = parseCorpus(locate("fixtures/tactic_references.json").readText())

    @Test
    fun `the corpus is non-trivial`() {
        assertTrue("expected a real corpus, got ${json.size} entries", json.size >= 15)
    }

    @Test
    fun `every FEN parses and round-trips through core`() {
        for (e in json) {
            val pos = Position.fromFen(e.fen)
            assertEquals("${e.tactic}: FEN round-trip", e.fen, pos.toFen())
            // Nobody who is not to move may already be in check — an illegal position.
            assertFalse("${e.tactic}: side not to move is in check", pos.isInCheck(pos.sideToMove.opposite()))
        }
    }

    @Test
    fun `every SAN move is legal in core and spelled exactly as core spells it`() {
        for (e in json) {
            var pos = Position.fromFen(e.fen)
            for ((i, san) in e.solution.withIndex()) {
                val move = try {
                    pos.parseSan(san)
                } catch (t: Throwable) {
                    throw AssertionError("${e.tactic}: ply ${i + 1} '$san' is not legal in core at ${pos.toFen()}", t)
                }
                // python-chess wrote the SAN; core must re-derive the identical string (check and
                // mate suffixes included), or the two disagree about the position after the move.
                assertEquals("${e.tactic}: ply ${i + 1} SAN spelling", san, pos.moveToSan(move))
                pos = pos.makeMove(move)
            }
        }
    }

    @Test
    fun `terminal claims agree - a hash means mate, and mate means a hash`() {
        for (e in json) {
            var pos = Position.fromFen(e.fen)
            for ((i, san) in e.solution.withIndex()) {
                pos = pos.makeMove(pos.parseSan(san))
                val claimsMate = san.endsWith("#")
                assertEquals("${e.tactic}: ply ${i + 1} '$san' mate claim vs core", claimsMate, pos.isCheckmate())
                val claimsCheck = san.endsWith("+") || claimsMate
                assertEquals("${e.tactic}: ply ${i + 1} '$san' check claim vs core", claimsCheck, pos.isInCheck())
                if (i < e.solution.lastIndex) {
                    assertFalse("${e.tactic}: line continues past a finished game", pos.isGameOver())
                }
            }
        }
    }

    @Test
    fun `the stalemate trick really ends in stalemate and the perpetual really repeats`() {
        val stalemate = json.first { it.tactic == "STALEMATE_TRICK" }
        assertTrue(replay(stalemate).isStalemate())

        val perpetual = json.first { it.tactic == "PERPETUAL_CHECK" }
        var pos = Position.fromFen(perpetual.fen)
        val seen = HashMap<String, Int>()
        for (san in perpetual.solution) {
            pos = pos.makeMove(pos.parseSan(san))
            // Compare on placement + side to move; move counters are not part of a repetition.
            val key = pos.toFen().split(" ").take(4).joinToString(" ")
            seen[key] = (seen[key] ?: 0) + 1
        }
        assertTrue("the perpetual's final position must have occurred before", (seen.values.maxOrNull() ?: 0) >= 2)
    }

    @Test
    fun `the generated Kotlin corpus is exactly the verified JSON`() {
        val kotlin = TacticReferenceLibrary.all
        assertEquals("entry count", json.size, kotlin.size)
        for ((j, k) in json.zip(kotlin)) {
            assertEquals(j.tactic, k.type.name)
            assertEquals("${j.tactic}: fen", j.fen, k.fen)
            assertEquals("${j.tactic}: solution", j.solution, k.solutionSan)
            assertEquals("${j.tactic}: teaching point", j.teachingPoint, k.teachingPoint)
        }
    }

    @Test
    fun `each tactic type appears at most once`() {
        val types = json.map { it.tactic }
        assertEquals(types.size, types.toSet().size)
        for (t in types) TacticType.valueOf(t) // must be a real TacticType
    }

    @Test
    fun `every reference builds a full-length simulation with an explanation per ply`() {
        for (e in json) {
            val type = TacticType.valueOf(e.tactic)
            val sim = TacticReferenceLibrary.simulation(type)
            assertNotNull("${e.tactic}: no simulation", sim)
            sim!!
            assertEquals("${e.tactic}: reference lines are never truncated", e.solution.size, sim.pvUci.size)
            assertEquals(e.solution, sim.pvSan)
            assertEquals(e.solution.size, sim.perPlyExplanation.size)
            assertTrue(sim.perPlyExplanation.all { it.isNotBlank() })
            assertTrue("${e.tactic}: payoff must be stated", sim.payoffDescription.isNotBlank())
            assertEquals(type, sim.tactic.type)
            assertEquals(e.fen, sim.startFen)
        }
    }

    @Test
    fun `types without a reference are known and reported`() {
        val missing = TacticType.entries.filter { TacticReferenceLibrary.forType(it) == null }
        // Not a failure — the UI simply offers no example for these — but the set is pinned so a
        // silent regression (an entry dropped from the corpus) is caught.
        assertEquals(
            setOf(TacticType.INTERFERENCE, TacticType.X_RAY, TacticType.BATTERY, TacticType.FORTRESS),
            missing.toSet()
        )
    }

    /**
     * A third opinion: the app's own detector, run on the reference position with the reference
     * line as its "PV", recognises the motif it is meant to illustrate. Restricted to the motifs
     * whose detectors are static-pattern based (spec 5.3); the PV-dependent ones are judged on
     * real engine lines and are not asserted here. HANGING_PIECE is also left out on purpose: the
     * detector reports the move that *leaves* a piece hanging (spec 5.3 is evaluated on the
     * position after the move), whereas the reference teaches the capture itself.
     */
    @Test
    fun `the app's own detector recognises the static-pattern references`() {
        val detector = MotifDetector()
        val expected = listOf(
            TacticType.FORK, TacticType.PAWN_FORK, TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE,
            TacticType.SKEWER, TacticType.BACK_RANK_MATE, TacticType.SMOTHERED_MATE, TacticType.DOUBLE_CHECK,
            TacticType.DISCOVERED_CHECK, TacticType.DISCOVERED_ATTACK,
            TacticType.TRAPPED_PIECE, TacticType.PROMOTION_TACTIC, TacticType.UNDERPROMOTION
        )
        val failures = ArrayList<String>()
        for (type in expected) {
            val ref = TacticReferenceLibrary.forType(type)!!
            val pos = Position.fromFen(ref.fen)
            val move = pos.parseSan(ref.solutionSan.first())
            val pv = ArrayList<String>()
            var p = pos
            for (san in ref.solutionSan) {
                val m = p.parseSan(san); pv.add(m.toUci()); p = p.makeMove(m)
            }
            val found = detector.detect(pos, move, pv).map { it.type }
            if (type !in found) failures.add("$type -> detector saw $found")
        }
        assertTrue("detector disagrees with the corpus: $failures", failures.isEmpty())
    }

    // -----------------------------------------------------------------------

    private fun replay(e: JsonEntry): Position {
        var pos = Position.fromFen(e.fen)
        for (san in e.solution) pos = pos.makeMove(pos.parseSan(san))
        return pos
    }

    private fun locate(relative: String): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate $relative")
    }

    /**
     * A deliberately tiny JSON reader for the one shape the fixture has (an array of flat objects
     * with string and string-array fields) — `:core` has no JSON dependency and should not grow one
     * for a test.
     */
    private fun parseCorpus(text: String): List<JsonEntry> {
        val p = MiniJson(text)
        val raw = p.value()
        @Suppress("UNCHECKED_CAST")
        return (raw as List<Map<String, Any?>>).map { o ->
            @Suppress("UNCHECKED_CAST")
            JsonEntry(
                tactic = o["tactic"] as String,
                fen = o["fen"] as String,
                solution = o["solution"] as List<String>,
                teachingPoint = o["teachingPoint"] as String
            )
        }
    }

    private class MiniJson(private val s: String) {
        private var i = 0

        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val key = str()
                ws(); require(s[i] == ':'); i++
                out[key] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++
                return out
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++
                return out
            }
        }

        private fun str(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val c = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(c)
                    }
                    i++
                } else {
                    sb.append(s[i]); i++
                }
            }
            i++
            return sb.toString()
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)); i += word.length; return v
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}
