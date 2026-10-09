package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLineCaption
import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.BoardFacts
import net.palaya.chessanalyzer.core.analysis.CandidateLine
import net.palaya.chessanalyzer.core.analysis.CommentaryGenerator
import net.palaya.chessanalyzer.core.analysis.ExchangeEvaluator
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.text.CommentaryVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §7.2 / docs/COMMENTARY_AUDIT.md: every card text of the three recorded real games, for
 * no side, White and Black, is checked against the position it is about. The independent
 * (python-chess) audit lives in `scripts/audit_commentary.py`; these are the rules it found
 * broken, kept as tests so they stay fixed, plus the C1 rules: every sentence is one of the catalogued
 * templates (docs/COMMENTARY_STYLE.md), every professional term is re-verified on the board here too,
 * and the variety is deterministic.
 */
class CommentaryClaimsTest {

    private val games = listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom, "game01" to RealGameFixture.game01)
    private val sides = listOf<Color?>(null, Color.WHITE, Color.BLACK)

    private val reports: Map<Pair<String, Color?>, GameReport> by lazy {
        buildMap { for ((n, g) in games) for (s in sides) put(n to s, g.report(s)) }
    }

    private fun every(block: (name: String, side: Color?, a: MoveAnnotation) -> Unit) {
        for ((name, _) in games) for (side in sides) for (a in reports.getValue(name to side).annotations) block(name, side, a)
    }

    private val sentenceSplit = Regex("(?<=\\.)\\s+")

    private val errorClasses = setOf(MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.BLUNDER)

    // -----------------------------------------------------------------------
    // The defects the audit found, on the real positions
    // -----------------------------------------------------------------------

    private fun textAt(game: String, ply: Int, side: Color? = null): String =
        reports.getValue(game to side).annotations.first { it.ply == ply }.text

    @Test
    fun `defect 1 - 20 Na6 allows White's mate and does not force one`() {
        val charge = Regex("^(This lets White play|Now White can play|This hands White) Nxg7\\+, which (starts a forced mate in 5|begins a forced mate in 5|sets a forced mate in 5 in motion)\\.")
        val t = textAt("immortal", 40)
        assertTrue(t, charge.containsMatchIn(t))
        assertTrue(t, t.endsWith(" Better was Ba6."))
        assertFalse(t, "forces" in t)
        assertEquals(t.replace("White", "you").replace("Black", "your opponent"), textAt("immortal", 40, Color.WHITE))
        assertEquals(t.replace("White", "your opponent").replace("Black", "you"), textAt("immortal", 40, Color.BLACK))
    }

    @Test
    fun `defect 2 - a brilliant move says what it offers and allows nothing`() {
        val a = reports.getValue("chesscom" to null).annotations.first { it.ply == 19 }
        assertEquals(MoveClassification.BRILLIANT, a.classification)
        assertTrue(a.text, Regex("^Nxb5 (is a sacrifice: it offers|sacrifices|offers) the knight on b5").containsMatchIn(a.text))
        assertFalse(a.text, "lets" in a.text || "allow" in a.text)
    }

    @Test
    fun `defect 2b - a rook that cannot be taken, because the only piece attacking it is pinned, is not a sacrifice`() {
        // 14.Rd1 in the Opera Game: Rxd1 is illegal, the rook on d7 is pinned to its king by Bb5.
        val a = reports.getValue("chesscom" to null).annotations.first { it.ply == 27 }
        assertNotEquals("Rd1 is not a brilliancy", MoveClassification.BRILLIANT, a.classification)
        assertFalse(a.text, a.text.contains("sacrific"))
    }

    @Test
    fun `defect 3 - 6 Nf6 does not pin anything, it lets White play Qb3`() {
        val t = textAt("chesscom", 12)
        assertTrue(t, Regex("^(This lets White play|Now White can play|This hands White) Qb3, which (pins the pawn on b7 to the knight on b8|ties the pawn on b7 to the knight on b8 with a relative pin)\\.").containsMatchIn(t))
        assertTrue(t, t.endsWith(" Better was Qf6."))
    }

    @Test
    fun `an attack on a loose piece is an attack, never a win`() {
        // 5...Nf6 attacks the e4 pawn; 7.Nf3 attacked the queen, which then left. Neither "wins" anything.
        val loose = "(attacks the undefended|hits the loose|attacks the) (pawn on e4|queen on h4)(, which is en prise)?\\."
        assertTrue(textAt("immortal", 10), Regex("^Nf6 .*\\. (This|It) $loose$").matches(textAt("immortal", 10)))
        assertTrue(textAt("immortal", 11), Regex("^Nf3 .*\\. (This|It) $loose$").matches(textAt("immortal", 11)))
    }

    // -----------------------------------------------------------------------
    // Properties of every text, for every side
    // -----------------------------------------------------------------------

    @Test
    fun `none of the unprovable wordings survives anywhere`() {
        val banned = CommentaryVocabulary.C1_BANNED
        val winsAPieceOnASquare = CommentaryVocabulary.WINS_A_PIECE_ON_A_SQUARE
        every { name, side, a ->
            for (b in banned) assertFalse("$name/$side ply ${a.ply}: '$b' in [${a.text}]", b in a.text)
            assertFalse("$name/$side ply ${a.ply}: [${a.text}]", winsAPieceOnASquare.containsMatchIn(a.text))
        }
    }

    @Test
    fun `Better was names the engine's top move, once, as the final sentence of the classes that have one`() {
        every { name, side, a ->
            val t = a.text
            if (a.classification in errorClasses && a.bestMoveSan != null) {
                assertEquals("$name/$side ply ${a.ply}: [$t]", 1, Regex("Better was").findAll(t).count())
                val last = sentenceSplit.split(t).last()
                assertTrue("$name/$side ply ${a.ply}: final sentence of [$t]", last.startsWith("Better was ${a.bestMoveSan}"))
            }
            if (a.classification !in errorClasses && a.classification != MoveClassification.MISS) {
                assertFalse("$name/$side ply ${a.ply}: [$t]", "Better was" in t)
            }
        }
    }

    private val chargeLead = Regex("^(?:This lets (you|your opponent|White|Black) play (\\S+?)|Now (you|your opponent|White|Black) can play (\\S+?)|This hands (you|your opponent|White|Black) (\\S+?))(?:,|;) ")

    @Test
    fun `a reply or a charge appears only on an error, names a legal opponent move, and the right beneficiary`() {
        every { name, side, a ->
            val pos = Position.fromFen(a.fenBefore)
            val after = pos.makeMove(pos.parseUci(a.uci))
            val m = chargeLead.find(a.text)
            if (a.classification !in errorClasses) {
                assertFalse("$name/$side ply ${a.ply}: a charge on ${a.classification}: [${a.text}]", "lets" in a.text || "allows" in a.text || "can play" in a.text || "hands" in a.text)
            }
            if (m != null) {
                val who = m.groupValues[1].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[5] }
                val replySan = m.groupValues[2].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[6] }
                val reply = after.parseSan(replySan) // throws unless it is a legal move for the opponent
                assertEquals(a.color.opposite(), reply.color)
                val opponent = a.color.opposite()
                val expected = when {
                    side == null -> if (opponent == Color.WHITE) "White" else "Black"
                    opponent == side -> "you"
                    else -> "your opponent"
                }
                assertEquals("$name/$side ply ${a.ply}: [${a.text}]", expected, who)
            }
        }
    }

    @Test
    fun `every piece named on a square is the piece standing there in one of the positions the sentence is about`() {
        val mention = Regex("the (pawn|knight|bishop|rook|queen|king) on ([a-h][1-8])")
        every { name, side, a ->
            val before = Position.fromFen(a.fenBefore)
            val move = before.parseUci(a.uci)
            val after = before.makeMove(move)
            val positions = ArrayList<Position>()
            positions += before
            positions += after
            // after the opponent's reply, and after the better move (what "which ..." sentences describe)
            a.threatsAllowed.forEach { t -> runCatching { after.makeMove(after.parseUci(t.moveUci)) }.getOrNull()?.let(positions::add) }
            a.bestMoveUci?.let { u -> runCatching { before.makeMove(before.parseUci(u)) }.getOrNull()?.let(positions::add) }
            for (m in mention.findAll(a.text)) {
                val type = PieceType.valueOf(m.groupValues[1].uppercase())
                val square = Square.fromAlgebraic(m.groupValues[2])
                assertTrue(
                    "$name/$side ply ${a.ply}: '${m.value}' is not true in any position of [${a.text}]",
                    positions.any { it.pieceAt(square)?.type == type }
                )
            }
        }
    }

    @Test
    fun `second person appears only when a side is chosen, and colours only when none is`() {
        every { name, side, a ->
            val t = a.text
            val colourWords = Regex("\\b(White|Black)\\b").containsMatchIn(t)
            val youWords = Regex("\\b(you|your opponent)\\b").containsMatchIn(t)
            if (side == null) {
                assertFalse("$name ply ${a.ply} must not use you/your opponent: [$t]", youWords)
            } else {
                assertFalse("$name/$side ply ${a.ply} still names a colour: [$t]", colourWords)
            }
        }
    }

    @Test
    fun `a move the engine approves of carries no negative sentence at all`() {
        val approved = setOf(
            MoveClassification.BEST, MoveClassification.GREAT, MoveClassification.BRILLIANT,
            MoveClassification.EXCELLENT, MoveClassification.GOOD, MoveClassification.BOOK, MoveClassification.FORCED
        )
        // The GREAT lead says "the next-best option gives up real ground" about the alternatives, which is praise.
        val negative = Regex(
            "^\\S+ (gives back ground|is not the most precise|concedes a little ground|gives up real ground|goes wrong|lets the position slip" +
                "|gives up a big chunk of the position|is a serious slip|throws a big chunk of the position away)\\." +
                "|lets |allow|hands |That takes|swings from|that is what this cost|Better was"
        )
        every { name, side, a ->
            if (a.classification in approved) {
                assertFalse("$name/$side ply ${a.ply}: [${a.text}]", negative.containsMatchIn(a.text))
            }
        }
    }

    // -----------------------------------------------------------------------
    // C1: every sentence is a catalogued template, and every term is re-verified here
    // -----------------------------------------------------------------------

    private val bands = CommentaryVocabulary.BANDS

    /** The template catalogue (docs/COMMENTARY_STYLE.md), moved to main in C2 so the rephrase checker reads the same words. */
    private val templates = CommentaryVocabulary.TEMPLATES

    @Test
    fun `every sentence of every text is one of the catalogued templates`() {
        var sentences = 0
        every { name, side, a ->
            for (s in sentenceSplit.split(a.text)) {
                sentences++
                assertTrue("$name/$side ply ${a.ply}: no template matches [$s] in [${a.text}]", templates.any { it.matches(s) })
            }
        }
        assertTrue("$sentences sentences", sentences > 500)
    }

    @Test
    fun `the professional terms are re-verified on the board wherever they appear`() {
        var terms = 0
        every { name, side, a ->
            val before = Position.fromFen(a.fenBefore)
            val move = before.parseUci(a.uci)
            val after = before.makeMove(move)
            val t = a.text
            val tag = "$name/$side ply ${a.ply}: [$t]"
            fun found(regex: String) = Regex("(?:This|It) $regex\\.").find(t)
            found("(?:wins|picks up) the exchange")?.let {
                terms++
                assertTrue(tag, ExchangeEvaluator.isExchangeCapture(before, move))
                assertTrue(tag, ExchangeEvaluator.see(before, move) in ExchangeEvaluator.EXCHANGE_MIN_CP..ExchangeEvaluator.EXCHANGE_MAX_CP)
            }
            found("puts the (\\w+) on ([a-h][1-8]) in an absolute pin against the king on ([a-h][1-8])")?.let { m ->
                terms++
                assertEquals(tag, PieceType.KING, after.pieceAt(Square.fromAlgebraic(m.groupValues[3]))?.type)
            }
            Regex("(?:This|It) (?:hits the loose|attacks the undefended) (\\w+) on ([a-h][1-8])\\.|(?:This|It) attacks the (\\w+) on ([a-h][1-8]), which is en prise\\.").find(t)?.let { m ->
                terms++
                val sq = Square.fromAlgebraic(m.groupValues[2].ifEmpty { m.groupValues[4] })
                assertTrue(tag, BoardFacts.defenders(after, sq).isEmpty())
                assertTrue(tag, move.to in BoardFacts.attackers(after, sq, a.color))
            }
            Regex("forced mate in (\\d+)").find(t)?.let { m ->
                terms++
                val n = m.groupValues[1].toInt()
                val moverBefore = a.mateInBefore?.let { if (a.color == Color.WHITE) it else -it }
                val moverAfter = a.mateInAfter?.let { if (a.color == Color.WHITE) it else -it }
                val isCharge = chargeLead.containsMatchIn(t) || t.contains("allows a forced mate") || t.contains("walks into") || t.contains("has a forced mate")
                if (isCharge) assertEquals(tag, -n, moverAfter) else assertEquals(tag, n, moverBefore)
            }
            Regex("from ($bands) to ($bands)").find(t)?.let { m ->
                terms++
                assertEquals(tag, CommentaryGenerator.standingWords(a.winPercentBefore), m.groupValues[1])
                assertEquals(tag, CommentaryGenerator.standingWords(a.winPercentAfter), m.groupValues[2])
                assertTrue(tag, a.classification in errorClasses || a.classification == MoveClassification.MISS)
            }
            Regex("(?:is the only move here|is an only move|only move that kept)").find(t)?.let {
                terms++
                assertEquals(tag, MoveClassification.GREAT, a.classification)
            }
            Regex("keeping a winning position|holds on to a winning position|it keeps a winning position").find(t)?.let {
                terms++
                assertEquals(tag, MoveClassification.MISS, a.classification)
                assertTrue(tag, a.winPercentBefore in 82.0..95.0)
            }
            Regex("a decisive advantage").find(t)?.let {
                assertEquals(tag, MoveClassification.MISS, a.classification)
                assertTrue(tag, a.winPercentBefore >= 95.0)
            }
        }
        assertTrue("$terms term uses checked", terms >= 40)
    }

    @Test
    fun `the three games use the new vocabulary`() {
        val all = reports.filterKeys { it.second == null }.values.flatMap { it.annotations }.joinToString(" ") { it.text }
        val used = listOf("the exchange", "absolute pin", "relative pin", "en prise", "loose", "forced mate in", "only move", "zwischenzug", "overloaded", "desperado", "back rank", " from ")
            .filter { it in all }
        // Which terms the three games happen to use depends on the games; the ones every game has are these.
        for (term in listOf("forced mate in", "only move", " from ")) assertTrue("'$term' never appears in the three games", term in all)
        assertTrue("terms used: $used", used.size >= 5)
    }

    @Test
    fun `no two consecutive cards of the same class share a lead phrasing, and the wording is stable`() {
        for ((name, _) in games) {
            val annotations = reports.getValue(name to null).annotations
            for (i in 1 until annotations.size) {
                val a = annotations[i - 1]
                val b = annotations[i]
                if (a.classification != b.classification) continue
                fun lead(x: MoveAnnotation) = sentenceSplit.split(x.text).first().replace(x.san, "SAN")
                assertNotEquals("$name plies ${a.ply} and ${b.ply}: [${a.text}] / [${b.text}]", lead(a), lead(b))
            }
            // Byte-identical on a second analysis of the same game.
            assertEquals(annotations.map { it.text }, games.first { it.first == name }.second.report(null).annotations.map { it.text })
        }
    }

    // -----------------------------------------------------------------------
    // Defect 7 / stretch: the text is written again when the side is chosen
    // -----------------------------------------------------------------------

    @Test
    fun `regenerating for a side gives exactly the text an analysis for that side would have written`() {
        val generator = CommentaryGenerator()
        for ((name, _) in games) {
            val neutral = reports.getValue(name to null)
            for (side in sides) {
                val regenerated = generator.regenerate(neutral, side)
                val direct = reports.getValue(name to side)
                assertEquals("$name/$side", direct.annotations.map { it.text }, regenerated.annotations.map { it.text })
                assertEquals("$name/$side key moments", direct.keyMoments.map { it.summary }, regenerated.keyMoments.map { it.summary })
            }
        }
    }

    @Test
    fun `regenerating changes wording only, and can go back and forth`() {
        val generator = CommentaryGenerator()
        for ((name, _) in games) {
            val neutral = reports.getValue(name to null)
            val white = generator.regenerate(neutral, Color.WHITE)
            val black = generator.regenerate(white, Color.BLACK)
            val back = generator.regenerate(black, null)
            assertEquals(neutral.annotations.map { it.text }, back.annotations.map { it.text })
            assertEquals(neutral.annotations.map { it.copy(text = "") }, white.annotations.map { it.copy(text = "") })
            assertEquals(neutral.copy(annotations = emptyList(), keyMoments = emptyList()), white.copy(annotations = emptyList(), keyMoments = emptyList()))
        }
    }

    @Test
    fun `after the user chooses White no card says White allowed, and the opponent's cards say your opponent`() {
        val generator = CommentaryGenerator()
        for ((name, _) in games) {
            val white = generator.regenerate(reports.getValue(name to null), Color.WHITE)
            for (a in white.annotations) {
                assertFalse("$name ply ${a.ply}: [${a.text}]", a.text.contains("White allowed") || a.text.contains("White "))
                val charge = chargeLead.find(a.text)
                if (charge != null) {
                    val who = charge.groupValues[1].ifEmpty { charge.groupValues[3] }.ifEmpty { charge.groupValues[5] }
                    assertEquals(a.text, if (a.color == Color.BLACK) "you" else "your opponent", who)
                }
            }
            for (k in white.keyMoments) {
                assertEquals(white.annotations.first { it.ply == k.ply }.text, k.summary)
            }
        }
    }

    @Test
    fun `the played-move motifs are kept whole on the annotation, so the text can be written again`() {
        every { name, side, a ->
            assertTrue("$name/$side ply ${a.ply}", a.tacticsFound.all { it in a.tacticsPlayed })
            if (a.classification !in setOf(MoveClassification.BEST, MoveClassification.GREAT, MoveClassification.BRILLIANT)) {
                assertTrue("$name/$side ply ${a.ply}: the found bucket is empty for ${a.classification}", a.tacticsFound.isEmpty())
            }
        }
        // a GOOD / EXCELLENT move that still tripped a detector keeps what it did
        val keeps = reports.getValue("immortal" to null).annotations.any {
            it.classification in setOf(MoveClassification.GOOD, MoveClassification.EXCELLENT) && it.tacticsPlayed.isNotEmpty()
        }
        assertTrue("the fixture should hold a GOOD/EXCELLENT move with a played motif", keeps)
    }

    @Test
    fun `a replayed annotation names the same SAN the game recorded`() {
        every { name, _, a ->
            val pos = Position.fromFen(a.fenBefore)
            assertEquals("$name ply ${a.ply}", a.san.trimEnd('+', '#'), pos.moveToSan(pos.parseUci(a.uci)).trimEnd('+', '#'))
        }
    }

    // -----------------------------------------------------------------------
    // V2: the caption under a displayed engine line (ANALYSIS_SPEC 6.2)
    // -----------------------------------------------------------------------

    private fun bestLine(fen: String, pv: List<String>, cp: Int? = null, mate: Int? = null, depth: Int = 12) =
        BestLines.build(fen, CandidateLine(1, pv.first(), null, cp, mate, pv, depth))!!

    @Test
    fun `a line caption gives the engine's score as the engine's, White-relative, and never says wins for it`() {
        // Black to move, the engine rates Black's line +2.3 for Black: the app prints scores White-relative.
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 3"
        val l = bestLine(fen, listOf("g8f6", "b1c3"), cp = 230)
        assertEquals("The engine rates this line -2.3.", BestLineCaption.text(l, null))
        assertEquals("The engine rates this line 0.0.", BestLineCaption.text(bestLine(fen, listOf("g8f6"), cp = 2), null))
    }

    @Test
    fun `a mate is said as the engine's mate, for whoever mates, and checkmate only when the shown moves give it`() {
        val fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"
        val mating = bestLine(fen, listOf("a1a8"), mate = 1)
        assertEquals("The engine sees a forced mate in 1 for White. The line ends in checkmate.", BestLineCaption.text(mating, null))
        assertEquals("The engine sees a forced mate in 1 for you. The line ends in checkmate.", BestLineCaption.text(mating, Color.WHITE))
        assertEquals("The engine sees a forced mate in 1 for your opponent. The line ends in checkmate.", BestLineCaption.text(mating, Color.BLACK))
        // The engine's mate in 3 whose shown plies stop before it: the mate is the engine's, not the board's.
        val slow = bestLine(fen, listOf("a1a2"), mate = 3)
        assertEquals("The engine sees a forced mate in 3 for White.", BestLineCaption.text(slow, null))
        // Even the best line loses to mate: the mate belongs to the other side.
        val lost = bestLine(fen, listOf("g1f1"), mate = -2)
        assertEquals("The engine sees a forced mate in 2 for Black.", BestLineCaption.text(lost, null))
    }

    @Test
    fun `material is claimed only when the line nets it after the take-back, and named by the 40 cp rule`() {
        val free = bestLine("4k3/8/8/8/3n4/8/8/3QK3 w - - 0 1", listOf("d1d4", "e8f7"), cp = 900)
        assertEquals("The engine rates this line +9.0. In this line White wins a piece.", BestLineCaption.text(free, null))
        assertEquals("The engine rates this line +9.0. In this line you win a piece.", BestLineCaption.text(free, Color.WHITE))
        assertEquals("The engine rates this line +9.0. In this line your opponent wins a piece.", BestLineCaption.text(free, Color.BLACK))
        // The line stops right after Qxd4 and exd4 takes the queen back: no gain is claimed.
        val takenBack = bestLine("4k3/8/8/4p3/3n4/8/8/3QK3 w - - 0 1", listOf("d1d4"), cp = -500)
        assertEquals("The engine rates this line -5.0.", BestLineCaption.text(takenBack, null))
        // A free rook is "a rook".
        val rook = bestLine("4k3/8/8/8/3r4/8/8/3QK3 w - - 0 1", listOf("d1d4", "e8f7"), cp = 1200)
        assertEquals("The engine rates this line +12.0. In this line White wins a rook.", BestLineCaption.text(rook, null))
        // A rook for a bishop (+170) is "the exchange" (C1), not "a pawn" or "a piece".
        val exchange = bestLine("4k3/8/8/4p3/3r4/8/8/B3K3 w - - 0 1", listOf("a1d4", "e5d4"), cp = 150)
        assertEquals(170, exchange.settledGainCp)
        assertEquals("The engine rates this line +1.5. In this line White wins the exchange.", BestLineCaption.text(exchange, null))
        // A bishop for a rook that is never taken back is a rook, not the exchange: the pieces are counted.
        val wholeRook = bestLine("4k3/8/8/8/3r4/8/8/B3K3 w - - 0 1", listOf("a1d4", "e8f7"), cp = 500)
        assertEquals("The engine rates this line +5.0. In this line White wins a rook.", BestLineCaption.text(wholeRook, null))
        // A line that stops after the capture, with the take-back still to come, is settled first: Bxg7 Kxg7 is the exchange.
        val stops = bestLine("4k3/6R1/7K/8/3b4/8/8/8 b - - 0 1", listOf("d4g7"), cp = 170)
        assertEquals("The engine rates this line -1.7. In this line Black wins the exchange.", BestLineCaption.text(stops, null))
    }

    @Test
    fun `every line caption of the recorded games plus game01 is proved by the recording and the board`() {
        var checked = 0
        var materialClaims = 0
        var exchangeClaims = 0
        for ((name, g) in games) {
            for (side in sides) {
                for (a in g.report(side).annotations) {
                    for (l in BestLines.linesFor(a)) {
                        val text = BestLineCaption.text(l, side)
                        checked++
                        // 1. the engine's number, exactly as recorded, White-relative
                        val recorded = g.evals[a.ply - 1].lines.single { it.multiPv == l.multiPv }
                        val mate = recorded.mateIn
                        val first = text.substringBefore(". ").let { if (it == text) it else "$it." }
                        if (mate != null) {
                            assertTrue("$name ${a.ply}: $text", first.startsWith("The engine sees a forced mate in ${kotlin.math.abs(mate)} for "))
                        } else {
                            val white = if (Position.fromFen(a.fenBefore).sideToMove == Color.WHITE) recorded.scoreCp!! else -recorded.scoreCp!!
                            assertEquals("$name ${a.ply}", "The engine rates this line ${net.palaya.chessanalyzer.core.analysis.EvalFormat.score(white)}.", first)
                        }
                        // 2. checkmate exactly when the shown moves end in it
                        var pos = Position.fromFen(l.startFen)
                        for (u in l.ucis) pos = pos.makeMove(pos.parseUci(u))
                        assertEquals("$name ${a.ply}: $text", pos.isCheckmate(), text.contains("ends in checkmate"))
                        // 3. "wins" only for a settled gain of at least a pawn, named only when it is that piece
                        val claimsGain = text.contains("In this line")
                        if (claimsGain) materialClaims++
                        assertEquals("$name ${a.ply}: $text", !pos.isCheckmate() && l.settledGainCp >= 100, claimsGain)
                        for ((word, value) in listOf("a queen" to 900, "a rook" to 500, "a piece" to 325, "a pawn" to 100)) {
                            if (text.contains("wins $word") || text.contains("win $word")) {
                                assertTrue("$name ${a.ply}: $text (${l.settledGainCp})", kotlin.math.abs(l.settledGainCp - value) <= 40)
                            }
                        }
                        if (text.contains("the exchange")) {
                            exchangeClaims++
                            assertTrue("$name ${a.ply}: $text", ExchangeEvaluator.winsTheExchange(Position.fromFen(l.startFen), pos, l.mover))
                        }
                        // 4. who it is about: colours with no side, "you" / "your opponent" otherwise
                        if (side == null) assertFalse(text, text.contains("you")) else assertFalse(text, text.contains("White") || text.contains("Black"))
                        // 5. never the classification's name, never a hedge
                        for (word in listOf("Blunder", "Mistake", "Inaccuracy", "probably", "might")) assertFalse(text, text.contains(word))
                    }
                }
            }
        }
        assertTrue("$checked captions, $materialClaims material claims, $exchangeClaims exchange claims", checked > 300 && materialClaims > 0)
    }
}
