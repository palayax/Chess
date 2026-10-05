package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import java.io.File
import java.io.FileReader

/**
 * A *real* analysis, not a synthetic one: a recorded Stockfish run over one of the repository's
 * fixture games, replayed through the production pipeline (`GameAnalyzer` + `MotifDetector`).
 *
 * The recordings were produced by the desktop pipeline (`palaya-review --dry-run`, Stockfish 19,
 * MultiPV 3) and live in `core/src/test/resources/pacing/`:
 *  - `chesscom_style_game`: depth 20, 30 s cap, 8 threads (the 17-move miniature).
 *  - `immortal`: depth 12, 4 threads (23 moves).
 *
 * Kept as data so the pacing tests exercise the tactic detector and the narration on genuine
 * engine lines (the synthetic [NarrationFixture] cannot: it has no real PVs).
 */
object RealGameFixture {

    class Game(val pgn: PgnGame, val evals: List<PositionEval>) {
        fun report(userColor: Color? = null): GameReport {
            val see = StaticExchangeEvaluator()
            val book = FileReader(locate("app/src/main/assets/openings.tsv")).use { OpeningBook.load(it) }
            return GameAnalyzer(MoveClassifier(see), MotifDetector(see)).analyze(pgn, evals, userColor, book)
        }

        /**
         * The first [plies] plies of this game as a game of its own (result "*"): the recording is one
         * engine pass per position, so the first n positions of a long recording *are* the recording of
         * the shorter game, and a short game costs no second engine run.
         */
        fun firstPlies(plies: Int): Game {
            require(plies in 1..pgn.moves.size)
            val moves = pgn.moves.take(plies)
            val tags = pgn.tags + ("Result" to "*")
            return Game(pgn.copy(tags = tags, moves = moves, result = "*"), evals.take(plies + 1))
        }
    }

    val chesscom: Game by lazy { load("chesscom_style_game") }
    val immortal: Game by lazy { load("immortal") }

    /** 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6 4.Qxf7#: the four-move game that produced a 3 min 43 s video. */
    val scholars: Game by lazy { load("scholars_mate") }

    /** Byrne-Fischer 1956, 41 moves ending in mate (depth 12, MultiPV 3, `scripts/record_analysis.py`). */
    val byrneFischer: Game by lazy { load("byrne_fischer") }

    private fun load(name: String): Game {
        val pgn = PgnParser.parse(locate("fixtures/$name.pgn").readText()).single()
        val text = RealGameFixture::class.java.getResourceAsStream("/pacing/$name.analysis.json")!!
            .readBytes().toString(Charsets.UTF_8)

        @Suppress("UNCHECKED_CAST")
        val root = RealGameJson(text).value() as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val evals = (root["evals"] as List<Map<String, Any?>>).map { e ->
            @Suppress("UNCHECKED_CAST")
            val lines = (e["lines"] as List<Map<String, Any?>>).map { l ->
                EngineLineInput(
                    multiPv = (l["multiPv"] as Number).toInt(),
                    scoreCp = (l["scoreCp"] as Number?)?.toInt(),
                    mateIn = (l["mateIn"] as Number?)?.toInt(),
                    depth = (l["depth"] as Number).toInt(),
                    pvUci = (l["pvUci"] as List<*>).map { it as String }
                )
            }
            PositionEval(e["fen"] as String, lines, (e["depth"] as Number).toInt())
        }
        return Game(pgn, evals)
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
}

/** A deliberately tiny JSON reader: `:core` has no JSON dependency and should not grow one for a test. */
internal class RealGameJson(private val s: String) {
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
        i++
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
        i++
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
