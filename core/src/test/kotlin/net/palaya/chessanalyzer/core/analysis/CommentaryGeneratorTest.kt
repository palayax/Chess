package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §7: the commentary says only what the position proves, about the side that owns the
 * motif, and drops what it cannot make true. The positions are real ones from the two recorded games
 * (the Immortal Game and the Opera Game) and the motifs are what [MotifDetector] reports for them,
 * so every sentence here is one the app would really show. C1 added professional terms, each with a
 * proof (docs/COMMENTARY_STYLE.md), and deterministic variety ([Variety]): where a wording rotates,
 * the test asserts the claim and lets the phrasing be any of the template's variants.
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
        loss: Double = 25.0,
        wpBefore: Double? = null,
        wpAfter: Double? = null
    ): String = generator.generate(
        classification, s.san, s.move, s.before, s.after, loss, best, mateBefore, found, missed, threats,
        user, mateAfter, previous, wpBefore, wpAfter
    )

    /** What the detector reports when [san] is played from [fen] (static motifs only: no engine line). */
    private fun detected(fen: String, san: String, pv: List<String> = emptyList()): List<TacticInstance> {
        val pos = Position.fromFen(fen)
        return detector.detect(pos, pos.parseSan(san), pv)
    }

    private fun assertMatches(regex: String, actual: String) =
        assertTrue("expected /$regex/ but was: $actual", Regex(regex).matches(actual))

    // Immortal Game, 7...Nh5?! (c6 attacks the loose bishop on b5), and 20...Na6?? (allows Nxg7+ and mate).
    private val nh5Fen = "rnb1kb1r/p1pp1ppp/5n1q/1B6/4Pp2/3P1N2/PPP3PP/RNBQ1K1R b kq - 0 7"
    private val na6Fen = "rnb1k1nr/p2p1ppp/3B4/1p1NPN1P/6P1/3P1Q2/P1P1K3/q5b1 b kq - 1 20"

    private val mateThreat = TacticInstance(
        TacticType.MATE_NET, Color.WHITE, "f5g7", listOf(sq("e8")), listOf(sq("g7")), 10_000,
        "Nxg7+ begins a forced mate.", 0.95
    )

    private val inaccuracyLead = "(gives back ground|is not the most precise|concedes a little ground)"
    private val allowedLead = "(This lets|Now|This hands)"

    // -----------------------------------------------------------------------
    // Defect 1: the opponent's mate is not the mover's
    // -----------------------------------------------------------------------

    @Test
    fun `a blunder that allows mate says the opponent mates, with the engine's distance, not that the mover forces it`() {
        val s = setup(na6Fen, "Na6")
        val t = text(s, MoveClassification.BLUNDER, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5)
        assertEquals("Now White can play Nxg7+, which sets a forced mate in 5 in motion. Better was Ba6.", t)
        assertFalse(t, t.contains("forces mate"))
    }

    @Test
    fun `the beneficiary of the threat is you, your opponent or a colour as the viewer sees it`() {
        val s = setup(na6Fen, "Na6")
        fun ask(user: Color?) = text(s, MoveClassification.BLUNDER, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5, user = user)
        assertEquals("Now your opponent can play Nxg7+, which sets a forced mate in 5 in motion. Better was Ba6.", ask(Color.BLACK))
        assertEquals("Now you can play Nxg7+, which sets a forced mate in 5 in motion. Better was Ba6.", ask(Color.WHITE))
        assertEquals("Now White can play Nxg7+, which sets a forced mate in 5 in motion. Better was Ba6.", ask(null))
    }

    @Test
    fun `a forced mate against the mover is stated from the engine's number when no motif names the reply`() {
        val s = setup(na6Fen, "Na6")
        assertEquals("This allows a forced mate in 5. Better was Ba6.", text(s, MoveClassification.BLUNDER, best = "Ba6", mateAfter = -5))
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
        assertTrue(t, t.startsWith("Nxb5 sacrifices the knight on b5."))
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
    fun `a pin the better move would have made is said about the better move, as a relative pin`() {
        val s = setup(operaBc4Fen, "Bc4")
        val missed = detected(operaBc4Fen, "Qb3")
        val t = text(s, MoveClassification.INACCURACY, best = "Qb3", missed = missed)
        assertEquals("Bc4 is not the most precise. Better was Qb3: it ties the pawn on b7 to the knight on b8 with a relative pin.", t)
    }

    @Test
    fun `a missed attack on a loose piece attacks it, it does not drop it`() {
        val s = setup(nh5Fen, "Nh5")
        val t = text(s, MoveClassification.INACCURACY, best = "c6", missed = detected(nh5Fen, "c6"))
        assertEquals("Nh5 is not the most precise. Better was c6, which hits the loose bishop on b5.", t)
        assertFalse(t, t.contains("drops"))
        assertFalse(t, t.contains("keeping material level"))
    }

    @Test
    fun `a motif owned by the wrong side or belonging to another move is dropped`() {
        val s = setup(nh5Fen, "Nh5")
        val ownThreat = TacticInstance(TacticType.MATE_NET, Color.BLACK, "f6h5", listOf(sq("e1")), emptyList(), 10_000, "x", 0.95)
        assertEquals("Nh5 is not the most precise.", text(s, MoveClassification.INACCURACY, threats = listOf(ownThreat)))

        val elsewhere = TacticInstance(
            TacticType.FORK, Color.BLACK, "c7c6", listOf(sq("b5"), sq("d5")), listOf(sq("c6")), 500, "x", 0.95
        )
        // The played move is Nh5; a fork "by c6" is not something Nh5 did.
        assertMatches("Nh5 (is a sound move|is a reasonable move|is a solid choice)\\.", text(s, MoveClassification.GOOD, found = listOf(elsewhere)))
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
            assertFalse("$c: $t", t.contains("lets") || t.contains("allow", ignoreCase = true) || t.contains("hands") || t.contains("can play"))
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
            "cxb5 is not the most precise. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through.",
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
            "Bxg1 concedes a little ground. Better was Qxa1+, which picks up a rook.",
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
        assertFalse(t, t.contains("a rook"))
    }

    @Test
    fun `a minor piece taking a rook that is taken back wins the exchange, never a pawn or material`() {
        // Nxd5 exd5: the knight takes a rook and is taken back by the pawn, +180.
        val fen = "4k3/8/4p3/3r4/8/2N5/8/4K3 w - - 0 1"
        val s = setup(fen, "Nxd5")
        val t = text(s, MoveClassification.BEST, best = "Nxd5", loss = 0.0)
        assertMatches("Nxd5 .*\\. (This|It) (wins|picks up) the exchange\\.", t)
        // The same capture with the rook undefended is simply a rook.
        val free = setup("4k3/8/8/3r4/8/2N5/8/4K3 w - - 0 1", "Nxd5")
        assertMatches("Nxd5 .*\\. (This|It) (wins|picks up) a rook\\.", text(free, MoveClassification.BEST, best = "Nxd5", loss = 0.0))
        assertTrue(ExchangeEvaluator.isExchangeCapture(s.before, s.move))
    }

    // -----------------------------------------------------------------------
    // The terms added in C1, each verified on the board
    // -----------------------------------------------------------------------

    @Test
    fun `a pin against the king is an absolute pin, one against a bigger piece a relative pin`() {
        val fen = "4k3/8/2n5/8/8/8/8/4KB2 w - - 0 1"
        val s = setup(fen, "Bb5")
        val t = text(s, MoveClassification.BEST, best = "Bb5", found = detected(fen, "Bb5"), loss = 0.0)
        assertMatches("Bb5 .*\\. (This|It) (pins the knight on c6 to the king on e8|puts the knight on c6 in an absolute pin against the king on e8)\\.", t)
        assertFalse(t, t.contains("relative"))
    }

    @Test
    fun `a zwischenzug is said as the engine's line, and only for a forcing move with a capture waiting`() {
        // Bb5+ first, then Qxd4: the check comes before the capture the line still makes.
        val fen = "r3k2r/pp3ppp/8/8/3nP3/8/PPP2PPP/R2QKB1R w KQkq - 0 1"
        val pv = listOf("f1b5", "e8f8", "d1d4")
        val found = detected(fen, "Bb5+", pv).filter { it.type == TacticType.ZWISCHENZUG }
        assertEquals(1, found.size)
        assertEquals("Bb5+ is a zwischenzug: it comes first, and Qxd4 follows.", found.single().description)
        val s = setup(fen, "Bb5+")
        val t = text(s, MoveClassification.BEST, best = "Bb5+", found = found, loss = 0.0)
        assertMatches("Bb5\\+ .*\\. (In the engine's line, |The engine's line shows it: )Bb5\\+ is a zwischenzug: it comes first, and Qxd4 follows\\.", t)
        // The same motif on a quiet bishop move is refused: nothing forcing came first.
        val quiet = setup(fen, "Be2")
        val fake = found.single().copy(moveUci = "f1e2", description = "Be2 is a zwischenzug: it comes first, and Qxd4 follows.")
        assertMatches("Be2 .*\\.", text(quiet, MoveClassification.BEST, best = "Be2", found = listOf(fake), loss = 0.0))
        assertFalse(text(quiet, MoveClassification.BEST, best = "Be2", found = listOf(fake), loss = 0.0).contains("zwischenzug"))
    }

    @Test
    fun `an overloaded defender is named with both of its charges, as the engine's line`() {
        // The bishop on d8 guards the knights on c7 and f6; Nxf6 Bxf6 Rxc7.
        val fen = "3b2k1/2n4p/5n2/8/4N3/8/6PP/2R3K1 w - - 0 1"
        val pv = listOf("e4f6", "d8f6", "c1c7")
        val found = detected(fen, "Nxf6+", pv).filter { it.type == TacticType.OVERLOADED_PIECE }
        assertEquals(1, found.size)
        val s = setup(fen, "Nxf6+")
        val t = text(s, MoveClassification.BEST, best = "Nxf6+", found = found, loss = 0.0)
        assertMatches(
            "Nxf6\\+ .*\\. (In the engine's line, |The engine's line shows it: )Nxf6\\+ exploits the overloaded bishop on d8, which cannot guard (c7 and f6|f6 and c7) at once\\.", t
        )
        // The same motif with a target that has another defender is refused.
        val wrong = found.single().copy(targetSquares = listOf(sq("c7"), sq("h7")))
        assertFalse(text(s, MoveClassification.BEST, best = "Nxf6+", found = listOf(wrong), loss = 0.0).contains("overloaded"))
    }

    @Test
    fun `a desperado is a piece that was lost anyway and is still lost where it landed`() {
        // The queen on d5 is attacked by the knight and the pawn; Qxd8+ sells it for the rook.
        val fen = "3r2k1/6pp/2p1n3/3Q4/8/8/6PP/6K1 w - - 0 1"
        val found = detected(fen, "Qxd8+").filter { it.type == TacticType.DESPERADO }
        assertEquals(1, found.size)
        val s = setup(fen, "Qxd8+")
        val t = text(s, MoveClassification.BEST, best = "Qxd8+", found = found, loss = 0.0)
        assertMatches("Qxd8\\+ .*\\. (This|It) is a desperado: the queen was lost anyway, so it takes the rook on d8 on the way out\\.", t)
        // A queen that was safe where it stood is not a desperado, whatever the detector says.
        val safe = "3r2k1/6pp/8/3Q4/8/8/6PP/6K1 w - - 0 1"
        val fake = found.single()
        assertFalse(text(setup(safe, "Qxd8+"), MoveClassification.BEST, best = "Qxd8+", found = listOf(fake), loss = 0.0).contains("desperado"))
    }

    @Test
    fun `a back-rank mate threat names the mating move, and only when the king is boxed in by its own pawns`() {
        // Re1 threatens Re8#: the king on g8 is walled in by f7, g7 and h7.
        val fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"
        val found = detected(fen, "Re1").filter { it.type == TacticType.BACK_RANK_MATE }
        assertEquals(1, found.size)
        val s = setup(fen, "Re1")
        val t = text(s, MoveClassification.BEST, best = "Re1", found = found, loss = 0.0)
        assertMatches("Re1 .*\\. (This|It) (threatens Re8, mate on the back rank|sets up a back-rank mate: Re8 is the threat)\\.", t)
        // With luft (h6) there is no threat, however the motif is labelled.
        val luft = "6k1/5pp1/7p/8/8/8/8/R5K1 w - - 0 1"
        assertFalse(text(setup(luft, "Re1"), MoveClassification.BEST, best = "Re1", found = found, loss = 0.0).contains("back rank"))
    }

    @Test
    fun `a found forced mate carries the engine's distance`() {
        // Nxg7+ in the Immortal Game: mate in 3 before the move.
        val fen = "r1b1k1nr/p2p1ppp/n2B4/1p1NPN1P/6P1/3P1Q2/P1P1K3/q5b1 w kq - 2 21"
        val s = setup(fen, "Nxg7+")
        val net = TacticInstance(TacticType.MATE_NET, Color.WHITE, "f5g7", listOf(sq("e8")), listOf(sq("g7")), 10_000, "Nxg7+ begins a forced mate.", 0.95)
        val t = text(s, MoveClassification.GREAT, best = "Nxg7+", found = listOf(net), mateBefore = 3, mateAfter = 2, loss = 0.0)
        assertMatches("Nxg7\\+ .*\\. (This|It) (starts a forced mate in 3|begins a forced mate in 3|sets a forced mate in 3 in motion)\\.", t)
    }

    @Test
    fun `an error says what it did to the evaluation, in the spec's bands, mover-relative`() {
        val s = setup(nh5Fen, "Nh5")
        val t = text(s, MoveClassification.INACCURACY, best = "c6", wpBefore = 60.0, wpAfter = 50.0)
        assertMatches(
            "Nh5 $inaccuracyLead\\. (That takes Black from slightly better to about level|The position swings from slightly better to about level for Black" +
                "|From slightly better to about level in one move: that is what this cost Black)\\. Better was c6\\.", t
        )
        // Second person for the viewer, with no verb that has to agree.
        val you = text(s, MoveClassification.INACCURACY, best = "c6", wpBefore = 60.0, wpAfter = 50.0, user = Color.BLACK)
        assertEquals(t.replace("Black", "you"), you)
        // No sentence when the move stayed inside one band.
        val same = text(s, MoveClassification.INACCURACY, best = "c6", wpBefore = 60.0, wpAfter = 58.0)
        assertFalse(same, same.contains(" from "))
        // Never on a move the engine approved of.
        val good = text(s, MoveClassification.GOOD, wpBefore = 60.0, wpAfter = 50.0)
        assertFalse(good, good.contains(" from "))
    }

    @Test
    fun `the evaluation words follow the bands of the spec`() {
        assertEquals("decisively winning", CommentaryGenerator.standingWords(95.0))
        assertEquals("winning", CommentaryGenerator.standingWords(82.0))
        assertEquals("clearly better", CommentaryGenerator.standingWords(68.0))
        assertEquals("slightly better", CommentaryGenerator.standingWords(57.0))
        assertEquals("about level", CommentaryGenerator.standingWords(50.0))
        assertEquals("slightly worse", CommentaryGenerator.standingWords(42.9))
        assertEquals("clearly worse", CommentaryGenerator.standingWords(18.0))
        assertEquals("losing", CommentaryGenerator.standingWords(5.0))
        assertEquals("decisively lost", CommentaryGenerator.standingWords(4.9))
    }

    @Test
    fun `a missed win keeps a winning position below 95 percent and a decisive advantage from 95`() {
        val s = setup(na6Fen, "Na6")
        assertMatches("Better was Qb8\\+(, keeping|, which holds on to|: it keeps) a winning position\\.", text(s, MoveClassification.MISS, best = "Qb8+", wpBefore = 91.0, wpAfter = 91.0))
        assertMatches("Better was Qb8\\+(, keeping|, which holds on to|: it keeps) a decisive advantage\\.", text(s, MoveClassification.MISS, best = "Qb8+", wpBefore = 97.0, wpAfter = 97.0))
    }

    // -----------------------------------------------------------------------
    // Variety: deterministic, and never the same phrasing twice in a row
    // -----------------------------------------------------------------------

    @Test
    fun `the variant is a pure function of the ply and the template, and steps with the ply`() {
        for (size in 2..4) for (ply in 1..120) {
            val a = Variety(ply).index("t", size)
            assertEquals(a, Variety(ply).index("t", size))
            assertNotEquals("ply $ply, $size variants", a, Variety(ply + 1).index("t", size))
            assertTrue(a in 0 until size)
        }
        // Different templates on one card start at different offsets, so a card does not read as a rhyme.
        assertTrue((1..60).any { Variety(it).index("best", 3) != Variety(it).index("found", 2) })
    }

    @Test
    fun `the ply is read off the position, so the same annotation always gets the same text`() {
        assertEquals(1, CommentaryGenerator.plyOf(Position.startPosition()))
        assertEquals(2, CommentaryGenerator.plyOf(Position.startPosition().let { it.makeMove(it.parseSan("e4")) }))
        assertEquals(40, CommentaryGenerator.plyOf(Position.fromFen(na6Fen)))
        val s = setup(nh5Fen, "Nh5")
        assertEquals(text(s, MoveClassification.INACCURACY, best = "c6"), text(s, MoveClassification.INACCURACY, best = "c6"))
    }

    @Test
    fun `every lead of every class is one of its template's variants and names the move`() {
        val s = setup(nh5Fen, "Nh5")
        val leads = mapOf(
            MoveClassification.BEST to "Nh5 (matches the engine's top choice|is the engine's first choice|is the top engine move here)\\.",
            MoveClassification.EXCELLENT to "Nh5 (is very close to the best move|is nearly the engine's top choice|comes within a whisker of the best move)\\.",
            MoveClassification.GOOD to "Nh5 (is a sound move|is a reasonable move|is a solid choice)\\.",
            MoveClassification.GREAT to "Nh5 (was the only move that kept things on track|is the only move here: the next-best option gives up real ground|is an only move, and nothing else keeps the position on track)\\.",
            MoveClassification.BOOK to "Nh5 (follows known opening theory|is still opening theory|stays in book)\\.",
            MoveClassification.FORCED to "(Nh5 was the only legal move|Nh5 was forced: the only legal move|No choice here: Nh5 was the only legal move)\\."
        )
        for ((c, regex) in leads) assertMatches(regex, text(s, c, loss = 0.0))
        assertMatches("Nh5 $inaccuracyLead\\.", text(s, MoveClassification.INACCURACY))
        assertMatches("Nh5 (gives up real ground|goes wrong|lets the position slip)\\.", text(s, MoveClassification.MISTAKE))
        assertMatches("Nh5 (gives up a big chunk of the position|is a serious slip|throws a big chunk of the position away)\\.", text(s, MoveClassification.BLUNDER))
    }

    // -----------------------------------------------------------------------
    // The rest of the contract
    // -----------------------------------------------------------------------

    @Test
    fun `a missed win states the better move and the mate as one final sentence`() {
        val s = setup(na6Fen, "Na6")
        assertMatches("Better was Qb8\\+(, forcing mate in 3|, with a forced mate in 3|: mate in 3 was on the board)\\.", text(s, MoveClassification.MISS, best = "Qb8+", mateBefore = 3))
        assertMatches("Better was Qb8\\+(, keeping|, which holds on to|: it keeps) a decisive advantage\\.", text(s, MoveClassification.MISS, best = "Qb8+"))
        assertEquals("A forced mate in 2 was on the board.", text(s, MoveClassification.MISS, best = null, mateBefore = 2))
        assertEquals("A decisive advantage was on the board.", text(s, MoveClassification.MISS, best = null))
    }

    @Test
    fun `FORCED and BOOK produce sensible text with no tactic present`() {
        val s = setup(na6Fen, "Na6")
        assertMatches("(Na6 was the only legal move|Na6 was forced: the only legal move|No choice here: Na6 was the only legal move)\\.", text(s, MoveClassification.FORCED))
        assertMatches("Na6 (follows known opening theory|is still opening theory|stays in book)\\.", text(s, MoveClassification.BOOK))
    }

    @Test
    fun `no text opens with the class label the badge already shows`() {
        val classLabel = Regex(
            "^(forced|book|brilliant|great|best|excellent|good|inaccuracy|mistake|miss|missed|blunder)\\b",
            RegexOption.IGNORE_CASE
        )
        val s = setup(na6Fen, "Na6")
        for (c in MoveClassification.entries) {
            val t = text(s, c, best = "Ba6", threats = listOf(mateThreat), mateAfter = -5, missed = emptyList(), mateBefore = 3, wpBefore = 40.0, wpAfter = 0.0)
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
                val t = text(s, c, best = "c6", missed = missed, wpBefore = 60.0, wpAfter = 30.0)
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

    @Test
    fun `the exchange is counted by pieces, not by a centipawn band`() {
        val start = Position.fromFen("4k3/8/4p3/3r4/8/2N5/8/4K3 w - - 0 1")
        val afterPair = start.makeMove(start.parseSan("Nxd5")).let { it.makeMove(it.parseUci("e6d5")) }
        assertTrue(ExchangeEvaluator.winsTheExchange(start, afterPair, Color.WHITE))
        assertEquals("the exchange", ExchangeEvaluator.describeSettled(start, afterPair, Color.WHITE))
        // A bishop for two pawns is about the same value and is not the exchange.
        val bishopForPawns = Position.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        assertFalse(ExchangeEvaluator.winsTheExchange(Position.fromFen("4k3/8/8/8/8/8/pp6/B3K3 w - - 0 1"), bishopForPawns, Color.WHITE))
    }
}
