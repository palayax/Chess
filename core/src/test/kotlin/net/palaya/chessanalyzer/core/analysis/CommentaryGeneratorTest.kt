package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §7: the commentary says only what the position proves, about the side that owns the
 * motif, and drops what it cannot make true. The positions are real ones from the two recorded games
 * (the Immortal Game and the Opera Game) and the motifs are what [MotifDetector] reports for them,
 * so every sentence here is one the app would really show.
 */
class CommentaryGeneratorTest {

    private val generator = CommentaryGenerator()
    private val detector = MotifDetector()

    private fun sq(name: String) = Square.fromAlgebraic(name)

    private class Setup(val before: Position, val move: Move, val after: Position, val san: String)

    private fun setup(fen: String, san: String): Setup {
        val before = Position.fromFen(fen)
        val move = before.parseSan(san)
        return Setup(before, move, before.makeMove(move), san)
    }

    private fun text(
        s: Setup,
        classification: MoveClassification,
        best: String? = null,
        found: List<TacticInstance> = emptyList(),
        missed: List<TacticInstance> = emptyList(),
        threats: List<TacticInstance> = emptyList(),
        mateBefore: Int? = null,
        mateAfter: Int? = null,
        user: Color? = null,
        previous: Move? = null,
        loss: Double = 25.0
    ): String = generator.generate(
        classification, s.san, s.move, s.before, s.after, loss, best, mateBefore, found, missed, threats,
        user, mateAfter, previous
    )

    /** What the detector reports when [san] is played from [fen] (static motifs only: no engine line). */
    private fun detected(fen: String, san: String): List<TacticInstance> {
        val pos = Position.fromFen(fen)
        return detector.detect(pos, pos.parseSan(san))
    }

    // Immortal Game, 7...Nh5?! (c6 attacks the loose bishop on b5), and 20...Na6?? (allows Nxg7+ and mate).
    private val nh5Fen = "rnb1kb1r/p1pp1ppp/5n1q/1B6/4Pp2/3P1N2/PPP3PP/RNBQ1K1R b kq - 0 7"
    private val na6Fen = "rnb1k1nr/p2p1ppp/3B4/1p1NPN1P/6P1/3P1Q2/P1P1K3/q5b1 b kq - 1 20"

    private val mateThreat = TacticInstance(
        TacticType.MATE_NET, Color.WHITE, "f5g7", listOf(sq("e8")), listOf(sq("g7")), 10_000,
        "Nxg7+ begins a forced mate.", 0.95
    )

    // -----------------------------------------------------------------------
    // Defect 1: the opponent's mate is not the mover's
    // -----------------------------------------------------------------------

    @Test
    fun `a blunder that allows mate says the opponent mates, not that the mover forces it`() {
        val s = setup(na6Fen, "Na6")
        val t = text(s, MoveClassification.BLUNDER, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5)
        assertEquals("This lets White play Nxg7+, which starts a forced mate. Better was Ba6.", t)
        assertFalse(t, t.contains("forces mate"))
    }

    @Test
    fun `the beneficiary of the threat is you, your opponent or a colour as the viewer sees it`() {
        val s = setup(na6Fen, "Na6")
        fun ask(user: Color?) = text(s, MoveClassification.BLUNDER, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5, user = user)
        assertEquals("This lets your opponent play Nxg7+, which starts a forced mate. Better was Ba6.", ask(Color.BLACK))
        assertEquals("This lets you play Nxg7+, which starts a forced mate. Better was Ba6.", ask(Color.WHITE))
        assertEquals("This lets White play Nxg7+, which starts a forced mate. Better was Ba6.", ask(null))
    }

    @Test
    fun `a forced mate against the mover is stated from the engine's number when no motif names the reply`() {
        val s = setup(na6Fen, "Na6")
        assertEquals(
            "This allows a forced mate. Better was Ba6.",
            text(s, MoveClassification.BLUNDER, best = "Ba6", mateAfter = -5)
        )
    }

    // -----------------------------------------------------------------------
    // Defect 2: a brilliant move does not "allow" anything, and is only a sacrifice when it is one
    // -----------------------------------------------------------------------

    private val nxb5Fen = "rn2kb1r/p3qppp/2p2n2/1p2p1B1/2B1P3/1QN5/PPP2PPP/R3K2R w KQkq b6 0 10"

    @Test
    fun `a brilliant move never allows the motif it sets up`() {
        val s = setup(nxb5Fen, "Nxb5")
        val deflection = TacticInstance(
            TacticType.DEFLECTION, Color.WHITE, "c3b5", listOf(sq("b4")), listOf(sq("e7"), sq("b5")), 0,
            "Nxb5 deflects the queen on e7 away from guarding b4.", 0.95
        )
        val reply = TacticInstance(
            TacticType.DEFLECTION, Color.BLACK, "e7b4", listOf(sq("b4")), listOf(sq("b3"), sq("b4")), 900,
            "Qb4+ deflects the queen on b3 away from guarding b4.", 0.95
        )
        val t = text(s, MoveClassification.BRILLIANT, found = listOf(deflection), threats = listOf(reply), loss = 0.0)
        assertTrue(t, t.startsWith("Nxb5 is a sacrifice: it offers the knight on b5."))
        assertFalse(t, t.contains("allow", ignoreCase = true) || t.contains("lets"))
    }

    @Test
    fun `a piece left open to an even trade is not called a sacrifice`() {
        // 1.Rd1: the rook can be taken, but Kxd1 takes back - an even trade, nothing is given up.
        val s = setup("3r2k1/8/8/8/8/8/8/R3K3 w - - 0 1", "Rd1")
        assertEquals(
            "Rd1 leaves the rook on d1 open to capture, and the engine still rates it among the best moves.",
            text(s, MoveClassification.BRILLIANT, loss = 0.0)
        )
    }

    @Test
    fun `a brilliant move with nothing en prise is described by what the classifier proved`() {
        val start = Position.startPosition()
        val e4 = start.parseSan("e4")
        val t = generator.generate(
            MoveClassification.BRILLIANT, "e4", e4, start, start.makeMove(e4), 0.0, null, null,
            emptyList(), emptyList(), emptyList()
        )
        assertEquals("e4 is among the engine's best moves here.", t)
    }

    // -----------------------------------------------------------------------
    // Defect 3: a motif the engine's best move would have had is not credited to the played move
    // -----------------------------------------------------------------------

    private val operaNf6Fen = "rn1qkbnr/ppp2ppp/8/4p3/2B1P3/5Q2/PPP2PPP/RNB1K2R b KQkq - 1 6"
    private val operaBc4Fen = "rn1qkbnr/ppp2ppp/8/4p3/4P3/5Q2/PPP2PPP/RNB1KB1R w KQkq - 0 6"

    @Test
    fun `the pin Black allowed is White's, said about White's reply, never about the move Nf6`() {
        val pinReply = TacticInstance(
            TacticType.PIN_RELATIVE, Color.WHITE, "f3b3", listOf(sq("b7")), listOf(sq("b3"), sq("b8")), 100,
            "The queen on b3 pins the pawn on b7 against the knight on b8.", 0.95
        )
        val missedSkewer = TacticInstance(
            TacticType.SKEWER, Color.BLACK, "d8f6", listOf(sq("f3"), sq("f2")), listOf(sq("f6")), 0,
            "The queen on f6 skewers the queen on f3; when it moves, the pawn on f2 behind it is attacked.", 0.6
        )
        val s = setup(operaNf6Fen, "Nf6")
        val t = text(s, MoveClassification.MISTAKE, best = "Qf6", missed = listOf(missedSkewer), threats = listOf(pinReply))
        assertEquals("This lets White play Qb3, which pins the pawn on b7 to the knight on b8. Better was Qf6.", t)
    }

    @Test
    fun `a pin the better move would have made is said about the better move`() {
        val s = setup(operaBc4Fen, "Bc4")
        val missed = detected(operaBc4Fen, "Qb3")
        val t = text(s, MoveClassification.INACCURACY, best = "Qb3", missed = missed)
        assertEquals("Bc4 gives back ground. Better was Qb3, which pins the pawn on b7 to the knight on b8.", t)
    }

    @Test
    fun `a missed attack on a loose piece attacks it, it does not drop it`() {
        val s = setup(nh5Fen, "Nh5")
        val t = text(s, MoveClassification.INACCURACY, best = "c6", missed = detected(nh5Fen, "c6"))
        assertEquals("Nh5 gives back ground. Better was c6, which attacks the undefended bishop on b5.", t)
        assertFalse(t, t.contains("drops"))
        assertFalse(t, t.contains("keeping material level"))
    }

    @Test
    fun `a motif owned by the wrong side or belonging to another move is dropped`() {
        val s = setup(nh5Fen, "Nh5")
        val ownThreat = TacticInstance(TacticType.MATE_NET, Color.BLACK, "f6h5", listOf(sq("e1")), emptyList(), 10_000, "x", 0.95)
        assertEquals("Nh5 gives back ground.", text(s, MoveClassification.INACCURACY, threats = listOf(ownThreat)))

        val elsewhere = TacticInstance(
            TacticType.FORK, Color.BLACK, "c7c6", listOf(sq("b5"), sq("d5")), listOf(sq("c6")), 500, "x", 0.95
        )
        // The played move is Nh5; a fork "by c6" is not something Nh5 did.
        assertEquals("Nh5 is a sound move.", text(s, MoveClassification.GOOD, found = listOf(elsewhere)))
    }

    // -----------------------------------------------------------------------
    // "Allowed" is a charge, made only against a move that cost something
    // -----------------------------------------------------------------------

    @Test
    fun `a move the engine approves of is never charged with allowing anything`() {
        val s = setup(na6Fen, "Na6")
        for (c in listOf(
            MoveClassification.BEST, MoveClassification.GREAT, MoveClassification.BRILLIANT,
            MoveClassification.EXCELLENT, MoveClassification.GOOD, MoveClassification.BOOK
        )) {
            val t = text(s, c, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5)
            assertFalse("$c: $t", t.contains("lets") || t.contains("allow", ignoreCase = true))
        }
    }

    @Test
    fun `a move after which the opponent mates is not credited with the motifs of that line`() {
        // The detector read a deflection out of the line in which Black is mated; it is White's.
        val s = setup(na6Fen, "Na6")
        val deflection = TacticInstance(
            TacticType.DEFLECTION, Color.BLACK, s.move.toUci(), listOf(sq("b8")), listOf(sq("c8")), 900,
            "Na6 deflects the queen on b3 away from guarding b8.", 0.95
        )
        assertEquals("Na6 is a sound move.", text(s, MoveClassification.GOOD, found = listOf(deflection), mateAfter = -5))
    }

    // -----------------------------------------------------------------------
    // The engine's line is said as the engine's line
    // -----------------------------------------------------------------------

    @Test
    fun `a motif proved by the engine's line is stated as that line`() {
        val fen = "rn2kb1r/p3qppp/2p2n2/1N2p1B1/2B1P3/1Q6/PPP2PPP/R3K2R b KQkq - 0 10"
        val clearance = TacticInstance(
            TacticType.CLEARANCE, Color.BLACK, "e7b4", listOf(sq("b4")), listOf(sq("e7"), sq("f8")), 900,
            "Qb4+ clears e7 so that Bxb4+ can come through.", 0.95
        )
        val s = setup(fen, "cxb5")
        assertEquals(
            "cxb5 gives back ground. Better was Qb4+; in the engine's line it clears e7 so that Bxb4+ can come through.",
            text(s, MoveClassification.INACCURACY, best = "Qb4+", missed = listOf(clearance))
        )
    }

    // -----------------------------------------------------------------------
    // What a capture wins, and what only takes back
    // -----------------------------------------------------------------------

    private val bxg1Fen = "rnb1k1nr/p2p1ppp/3B4/1pbN1N1P/4P1P1/3P1Q2/PqP5/R4KR1 b kq - 1 18"

    @Test
    fun `a better capture that wins a rook says so`() {
        val s = setup(bxg1Fen, "Bxg1")
        val missed = detected(bxg1Fen, "Qxa1+")
        assertEquals(
            "Bxg1 gives back ground. Better was Qxa1+, which wins a rook.",
            text(s, MoveClassification.INACCURACY, best = "Qxa1+", missed = missed)
        )
    }

    @Test
    fun `a capture that only takes back wins nothing`() {
        val s = setup(bxg1Fen, "Bxg1")
        val missed = detected(bxg1Fen, "Qxa1+")
        // Had the opponent just captured on a1, Qxa1+ would be the second half of a trade.
        val justCaptured = Move(sq("b2"), sq("a1"), net.palaya.chessanalyzer.core.chess.PieceType.ROOK, Color.WHITE, isCapture = true)
        val t = text(s, MoveClassification.INACCURACY, best = "Qxa1+", missed = missed, previous = justCaptured)
        assertFalse(t, t.contains("wins a rook"))
    }

    // -----------------------------------------------------------------------
    // The rest of the contract
    // -----------------------------------------------------------------------

    @Test
    fun `a missed win states the better move and the mate as one final sentence`() {
        val s = setup(na6Fen, "Na6")
        assertEquals("Better was Qb8+, forcing mate in 3.", text(s, MoveClassification.MISS, best = "Qb8+", mateBefore = 3))
        assertEquals("Better was Qb8+, keeping a decisive advantage.", text(s, MoveClassification.MISS, best = "Qb8+"))
        assertEquals("A forced mate in 2 was on the board.", text(s, MoveClassification.MISS, best = null, mateBefore = 2))
        assertEquals("A decisive advantage was on the board.", text(s, MoveClassification.MISS, best = null))
    }

    @Test
    fun `FORCED and BOOK produce sensible text with no tactic present`() {
        val s = setup(na6Fen, "Na6")
        assertEquals("Na6 was the only legal move.", text(s, MoveClassification.FORCED))
        assertEquals("Na6 follows known opening theory.", text(s, MoveClassification.BOOK))
    }

    @Test
    fun `no text opens with the class label the badge already shows`() {
        val classLabel = Regex(
            "^(forced|book|brilliant|great|best|excellent|good|inaccuracy|mistake|miss|missed|blunder)\\b",
            RegexOption.IGNORE_CASE
        )
        val s = setup(na6Fen, "Na6")
        for (c in MoveClassification.entries) {
            val t = text(s, c, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5, missed = emptyList(), mateBefore = 3)
            assertTrue("$c: $t", !classLabel.containsMatchIn(t))
            assertTrue("$c: $t", t.isNotBlank() && t.trim() == t && !t.contains("  ") && t.endsWith("."))
            assertTrue("$c: $t", !t.contains(".."))
        }
    }

    @Test
    fun `Better was appears exactly once and is the final sentence for the classes with a better move`() {
        val s = setup(nh5Fen, "Nh5")
        for (c in listOf(MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.BLUNDER)) {
            for (missed in listOf(emptyList(), detected(nh5Fen, "c6"))) {
                val t = text(s, c, best = "c6", missed = missed)
                assertEquals(t, 1, Regex("Better was").findAll(t).count())
                assertTrue(t, t.substring(t.indexOf("Better was")).startsWith("Better was c6"))
                assertEquals("nothing may follow the better move in: $t", 1, t.substring(t.indexOf("Better was")).count { it == '.' })
            }
        }
    }

    @Test
    fun `an underpromotion and a promotion are introduced with the right article`() {
        val fen = "8/P7/8/8/8/8/8/k1K5 w - - 0 1"
        val pos = Position.fromFen(fen)
        for (san in listOf("a8=N", "a8=Q")) {
            val move = pos.parseSan(san)
            val type = if (san.endsWith("Q")) TacticType.PROMOTION_TACTIC else TacticType.UNDERPROMOTION
            val tactic = TacticInstance(type, Color.WHITE, move.toUci(), listOf(sq("a8")), emptyList(), 600, "", 0.95)
            val t = generator.generate(
                MoveClassification.BEST, san, move, pos, pos.makeMove(move), 0.0, san, null, listOf(tactic), emptyList(), emptyList()
            )
            val wrong = Regex("\\ba (?=[aeiou])|\\ban (?![aeiou])", RegexOption.IGNORE_CASE)
            assertFalse("wrong article in: $t", wrong.containsMatchIn(t))
            assertTrue(t, t.contains("a knight") || t.contains("a queen"))
        }
    }
}
