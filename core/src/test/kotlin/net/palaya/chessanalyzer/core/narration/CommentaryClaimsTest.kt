package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLineCaption
import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.CandidateLine
import net.palaya.chessanalyzer.core.analysis.CommentaryGenerator
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.chess.parseUci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §7.2 / docs/COMMENTARY_AUDIT.md: every card text of the two recorded real games, for
 * no side, White and Black, is checked against the position it is about. The independent
 * (python-chess) audit lives in `scripts/audit_commentary.py`; these are the rules it found
 * broken, kept as tests so they stay fixed.
 */
class CommentaryClaimsTest {

    private val games = listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom)
    private val sides = listOf<Color?>(null, Color.WHITE, Color.BLACK)

    private val reports: Map<Pair<String, Color?>, GameReport> by lazy {
        buildMap { for ((n, g) in games) for (s in sides) put(n to s, g.report(s)) }
    }

    private fun every(block: (name: String, side: Color?, a: MoveAnnotation) -> Unit) {
        for ((name, _) in games) for (side in sides) for (a in reports.getValue(name to side).annotations) block(name, side, a)
    }

    private val sentenceSplit = Regex("(?<=\\.)\\s+(?=[A-Z])")

    private val errorClasses = setOf(MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.BLUNDER)

    // -----------------------------------------------------------------------
    // The defects the audit found, on the real positions
    // -----------------------------------------------------------------------

    private fun textAt(game: String, ply: Int, side: Color? = null): String =
        reports.getValue(game to side).annotations.first { it.ply == ply }.text

    @Test
    fun `defect 1 - 20 Na6 allows White's mate and does not force one`() {
        assertEquals(
            "This lets White play Nxg7+, which starts a forced mate. Better was Ba6.",
            textAt("immortal", 40)
        )
        assertEquals(
            "This lets your opponent play Nxg7+, which starts a forced mate. Better was Ba6.",
            textAt("immortal", 40, Color.BLACK)
        )
        assertEquals(
            "This lets you play Nxg7+, which starts a forced mate. Better was Ba6.",
            textAt("immortal", 40, Color.WHITE)
        )
    }

    @Test
    fun `defect 2 - a brilliant move says what it offers and allows nothing`() {
        val a = reports.getValue("chesscom" to null).annotations.first { it.ply == 19 }
        assertEquals(MoveClassification.BRILLIANT, a.classification)
        assertEquals("Nxb5 is a sacrifice: it offers the knight on b5.", a.text)
    }

    @Test
    fun `defect 2b - a rook that cannot be taken, because the only piece attacking it is pinned, is not a sacrifice`() {
        // 14.Rd1 in the Opera Game: Rxd1 is illegal, the rook on d7 is pinned to its king by Bb5.
        val a = reports.getValue("chesscom" to null).annotations.first { it.ply == 27 }
        assertNotEquals("Rd1 is not a brilliancy", MoveClassification.BRILLIANT, a.classification)
        assertFalse(a.text, a.text.contains("sacrifice"))
    }

    @Test
    fun `defect 3 - 6 Nf6 does not pin anything, it lets White play Qb3`() {
        assertEquals(
            "This lets White play Qb3, which pins the pawn on b7 to the knight on b8. Better was Qf6.",
            textAt("chesscom", 12)
        )
    }

    @Test
    fun `an attack on a loose piece is an attack, never a win`() {
        // 5...Nf6 attacks the e4 pawn; 7.Nf3 attacked the queen, which then left. Neither "wins" anything.
        assertEquals("Nf6 matches the engine's top choice. This attacks the undefended pawn on e4.", textAt("immortal", 10))
        assertEquals("Nf3 matches the engine's top choice. This attacks the undefended queen on h4.", textAt("immortal", 11))
    }

    // -----------------------------------------------------------------------
    // Properties of every text, for every side
    // -----------------------------------------------------------------------

    @Test
    fun `none of the unprovable wordings survives anywhere`() {
        val banned = listOf(
            "forces mate", "allowed", "drops the", "wins the ", "traps the", "sets up a", "sets up an",
            "keeping material level", "stunning", "opens a discovered attack", "The point becomes clear"
        )
        every { name, side, a ->
            for (b in banned) assertFalse("$name/$side ply ${a.ply}: '$b' in [${a.text}]", b in a.text)
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

    @Test
    fun `a reply or a charge appears only on an error, names a legal opponent move, and the right beneficiary`() {
        val lets = Regex("^This lets (you|your opponent|White|Black) play (\\S+?)(?:,|;) ")
        every { name, side, a ->
            val pos = Position.fromFen(a.fenBefore)
            val after = pos.makeMove(pos.parseUci(a.uci))
            val m = lets.find(a.text)
            if (a.classification !in errorClasses) {
                assertFalse("$name/$side ply ${a.ply}: a charge on ${a.classification}: [${a.text}]", "lets" in a.text || "allows" in a.text)
            }
            if (m != null) {
                val reply = after.parseSan(m.groupValues[2]) // throws unless it is a legal move for the opponent
                assertEquals(a.color.opposite(), reply.color)
                val opponent = a.color.opposite()
                val expected = when {
                    side == null -> if (opponent == Color.WHITE) "White" else "Black"
                    opponent == side -> "you"
                    else -> "your opponent"
                }
                assertEquals("$name/$side ply ${a.ply}: [${a.text}]", expected, m.groupValues[1])
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
                val square = net.palaya.chessanalyzer.core.chess.Square.fromAlgebraic(m.groupValues[2])
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
        every { name, side, a ->
            if (a.classification in approved) {
                assertFalse("$name/$side ply ${a.ply}: [${a.text}]", "lets" in a.text || "allow" in a.text || "gives back" in a.text)
            }
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
                if (a.color == Color.BLACK && "lets" in a.text) assertTrue(a.text, "This lets you play" in a.text)
                if (a.color == Color.WHITE && "lets" in a.text) assertTrue(a.text, "This lets your opponent play" in a.text)
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
        // A rook for a bishop (+170) is "material", not "a pawn" or "a piece".
        val exchange = bestLine("4k3/8/8/4p3/3r4/8/8/B3K3 w - - 0 1", listOf("a1d4", "e5d4"), cp = 150)
        assertEquals(170, exchange.settledGainCp)
        assertEquals("The engine rates this line +1.5. In this line White wins material.", BestLineCaption.text(exchange, null))
    }

    @Test
    fun `every line caption of the recorded games plus game01 is proved by the recording and the board`() {
        val fixtures = listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom, "game01" to RealGameFixture.game01)
        var checked = 0
        var materialClaims = 0
        for ((name, g) in fixtures) {
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
                        // 4. who it is about: colours with no side, "you" / "your opponent" otherwise
                        if (side == null) assertFalse(text, text.contains("you")) else assertFalse(text, text.contains("White") || text.contains("Black"))
                        // 5. never the classification's name, never a hedge
                        for (word in listOf("Blunder", "Mistake", "Inaccuracy", "probably", "might")) assertFalse(text, text.contains(word))
                    }
                }
            }
        }
        assertTrue("$checked captions, $materialClaims material claims", checked > 300 && materialClaims > 0)
    }
}

