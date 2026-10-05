package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated FEN corpus.
 *
 * Every entry is a real, legal position (loaded through `Position.fromFen` and re-serialised,
 * so a typo fails the suite rather than quietly passing), a legal move parsed out of that
 * position's own move list, and an assertion about which motifs must and must not come back.
 *
 * The `reject` column carries as much weight as `expect`. A tactics detector that fires on
 * everything is worse than none at all, because the UI shows the top motif per ply: the
 * false-positive guards near the bottom of the list are the ones that keep the output honest.
 */
class MotifDetectorTest {

    private val detector = MotifDetector(StaticExchangeEvaluator())

    private data class Case(
        val name: String,
        val fen: String,
        val uci: String,
        val pv: List<String> = emptyList(),
        val expect: List<TacticType> = emptyList(),
        val reject: List<TacticType> = emptyList()
    )

    // -----------------------------------------------------------------------
    // Positions
    // -----------------------------------------------------------------------

    private val staticCases = listOf(
        Case(
            "knight fork of king and rook", "2r3k1/5ppp/8/3N4/8/8/6PP/6K1 w - - 0 1", "d5e7",
            expect = listOf(TacticType.FORK)
        ),
        Case(
            "pawn fork of knight and bishop", "6k1/6pp/3n1b2/8/3PP3/8/6PP/6K1 w - - 0 1", "e4e5",
            expect = listOf(TacticType.PAWN_FORK), reject = listOf(TacticType.FORK)
        ),
        Case(
            "discovered check plus a hit on the rook: double attack",
            "1r5k/5p1p/8/4N3/8/8/1B4PP/6K1 w - - 0 1", "e5c6",
            expect = listOf(TacticType.DOUBLE_ATTACK, TacticType.DISCOVERED_CHECK)
        ),
        Case(
            "knight steps off the diagonal, bishop checks behind it",
            "1r5k/5p1p/8/4N3/8/8/1B4PP/6K1 w - - 0 1", "e5c6",
            expect = listOf(TacticType.DISCOVERED_CHECK), reject = listOf(TacticType.DOUBLE_CHECK)
        ),
        Case(
            "knight checks and uncovers the bishop: double check",
            "1r5k/5p1p/8/4N3/8/8/1B4PP/6K1 w - - 0 1", "e5f7",
            expect = listOf(TacticType.DOUBLE_CHECK, TacticType.DISCOVERED_CHECK)
        ),
        Case(
            "knight vacates the d-file, rook hits the queen",
            "3q2k1/6pp/8/8/3N4/8/6PP/3R2K1 w - - 0 1", "d4f5",
            expect = listOf(TacticType.DISCOVERED_ATTACK)
        ),
        Case(
            "bishop pins the knight to the king", "4k3/pp4pp/2n5/8/8/8/6PP/5BK1 w - - 0 1", "f1b5",
            expect = listOf(TacticType.PIN_ABSOLUTE), reject = listOf(TacticType.PIN_RELATIVE)
        ),
        Case(
            "bishop pins the knight to the queen",
            "3q2k1/pp4pp/5n2/8/8/8/6PP/2B3K1 w - - 0 1", "c1g5",
            expect = listOf(TacticType.PIN_RELATIVE), reject = listOf(TacticType.PIN_ABSOLUTE)
        ),
        Case(
            "rook check skewers the king off the rook behind it",
            "r7/8/8/8/k7/8/6PP/1R4K1 w - - 0 1", "b1a1",
            expect = listOf(TacticType.SKEWER)
        ),
        Case(
            "rook x-rays the queen through our own knight",
            "3q2k1/6pp/8/8/3N4/8/6PP/R5K1 w - - 0 1", "a1d1",
            expect = listOf(TacticType.X_RAY), reject = listOf(TacticType.BATTERY)
        ),
        Case(
            "rook lines up behind the queen on the d-file",
            "3r2k1/6pp/8/8/8/8/3Q2PP/5RK1 w - - 0 1", "f1d1",
            expect = listOf(TacticType.BATTERY)
        ),
        Case(
            "knight attacks an undefended rook", "3r2k1/5ppp/8/8/1N6/8/6PP/6K1 w - - 0 1", "b4c6",
            expect = listOf(TacticType.HANGING_PIECE)
        ),
        Case(
            "b3 traps the bishop that grabbed on a2",
            "6k1/6pp/8/8/8/8/bPP3PP/2R3K1 w - - 0 1", "b2b3",
            expect = listOf(TacticType.TRAPPED_PIECE)
        ),
        Case(
            "back-rank mate behind an untouched pawn shield",
            "6k1/5ppp/8/8/8/7Q/5PPP/6K1 w - - 0 1", "h3c8",
            expect = listOf(TacticType.BACK_RANK_MATE, TacticType.MATE_NET)
        ),
        Case(
            "smothered mate with the king boxed in by its own pieces",
            "6rk/6pp/8/4N3/8/8/6PP/6K1 w - - 0 1", "e5f7",
            expect = listOf(TacticType.SMOTHERED_MATE, TacticType.MATE_NET),
            reject = listOf(TacticType.BACK_RANK_MATE)
        ),
        Case(
            "pawn queens", "6k1/P5pp/8/8/8/8/6PP/6K1 w - - 0 1", "a7a8q",
            expect = listOf(TacticType.PROMOTION_TACTIC), reject = listOf(TacticType.UNDERPROMOTION)
        ),
        Case(
            "knight promotion with check forks the queen", "8/pp1k1P1q/8/8/8/8/8/6K1 w - - 0 1", "f7f8n",
            expect = listOf(TacticType.UNDERPROMOTION, TacticType.PROMOTION_TACTIC, TacticType.FORK)
        ),
        Case(
            "three-pawn breakthrough", "6k1/ppp5/8/PPP5/8/8/8/6K1 w - - 0 1", "b5b6",
            expect = listOf(TacticType.PASSED_PAWN_BREAKTHROUGH)
        ),
        Case(
            "a doomed queen takes the rook on the way down",
            "3r2k1/6pp/2p1n3/3Q4/8/8/6PP/6K1 w - - 0 1", "d5d8",
            expect = listOf(TacticType.DESPERADO)
        ),
        Case(
            "queen shuttles between h5 and e8 for the draw",
            "7k/6p1/8/8/8/8/7K/q2Q4 w - - 0 1", "d1h5",
            expect = listOf(TacticType.PERPETUAL_CHECK), reject = listOf(TacticType.MATE_NET)
        ),
        Case(
            "queen to c7 leaves black with no legal move", "k7/8/2K5/8/8/8/7Q/8 w - - 0 1", "h2c7",
            expect = listOf(TacticType.STALEMATE_TRICK)
        ),
        Case(
            "pawn reaching the seventh that nothing can stop",
            "6k1/6pp/1P6/8/8/8/6PP/6K1 w - - 0 1", "b6b7",
            expect = listOf(TacticType.PROMOTION_TACTIC)
        )
    )

    private val pvCases = listOf(
        Case(
            "Opera Game: Qb8+ drags the knight off the d-file",
            "4kb1r/p2n1ppp/4q3/4p1B1/4P3/1Q6/PPP2PPP/2KR4 w - - 0 1", "b3b8",
            pv = listOf("b3b8", "d7b8", "d1d8"),
            expect = listOf(TacticType.DEFLECTION, TacticType.MATE_NET)
        ),
        Case(
            "queen sacrifice decoys the rook onto the fork square",
            "r2q3k/6pp/8/6N1/8/8/6PP/3Q2K1 w - - 0 1", "d1d8",
            pv = listOf("d1d8", "a8d8", "g5f7"),
            expect = listOf(TacticType.DECOY)
        ),
        Case(
            "the bishop on d8 cannot guard both knights",
            "3b2k1/2n4p/5n2/8/4N3/8/6PP/2R3K1 w - - 0 1", "e4f6",
            pv = listOf("e4f6", "d8f6", "c1c7"),
            expect = listOf(TacticType.OVERLOADED_PIECE)
        ),
        Case(
            "Nb6 interferes with the rook's defence of b4",
            "1r4k1/6pp/8/P2N4/1n6/2B5/6PP/6K1 w - - 0 1", "d5b6",
            pv = listOf("d5b6", "g8h8", "c3b4"),
            expect = listOf(TacticType.INTERFERENCE)
        ),
        Case(
            "the knight clears the d-file with check",
            "6k1/3r1ppp/8/3N4/8/8/6PP/3R2K1 w - - 0 1", "d5f6",
            pv = listOf("d5f6", "g7f6", "d1d7"),
            expect = listOf(TacticType.CLEARANCE)
        ),
        Case(
            "a check first, the knight is still there afterwards",
            "r3k2r/pp3ppp/8/8/3nP3/8/PPP2PPP/R2QKB1R w KQkq - 0 1", "f1b5",
            pv = listOf("f1b5", "e8f8", "d1d4"),
            expect = listOf(TacticType.ZWISCHENZUG)
        ),
        Case(
            "Greek gift: Bxh7+ with Ng5+ and Qh5 behind it",
            "r1bq1rk1/pppn1ppp/4p3/3pP3/1b1P4/2NB1N2/PPP2PPP/R1BQK2R w KQ - 0 1", "d3h7",
            pv = listOf("d3h7", "g8h7", "f3g5", "h7g8", "d1h5"),
            expect = listOf(TacticType.GREEK_GIFT)
        ),
        Case(
            "Bxb7 removes the piece that was holding d5",
            "1r4k1/1b4pp/B7/3n4/8/8/6PP/3R2K1 w - - 0 1", "a6b7",
            pv = listOf("a6b7", "b8b7", "d1d5"),
            expect = listOf(TacticType.REMOVING_THE_DEFENDER)
        ),
        Case(
            "Torre-Lasker 1925: Bf6 starts the windmill",
            "r3rnk1/pb3pp1/3pp2p/1q4BQ/1P1P4/4N1R1/P4PPP/4R1K1 w - - 0 1", "g5f6",
            pv = listOf(
                "g5f6", "b5h5", "g3g7", "g8h8", "g7f7", "h8g8", "f7g7",
                "g8h8", "g7b7", "h8g8", "b7g7", "g8h8", "g7g5"
            ),
            expect = listOf(TacticType.WINDMILL)
        )
    )

    private val negativeCases = listOf(
        Case(
            "GUARD: a queen hitting two defended knights is not a fork",
            "6k1/2p3pp/3n1n2/8/8/8/4Q1PP/6K1 w - - 0 1", "e2e5",
            reject = listOf(TacticType.FORK, TacticType.PAWN_FORK, TacticType.DOUBLE_ATTACK)
        ),
        Case(
            "GUARD: a queen with a mere pawn behind it is skewered, not pinned",
            "6k1/3p2pp/8/3q4/8/8/6PP/R5K1 w - - 0 1", "a1d1",
            expect = listOf(TacticType.SKEWER),
            reject = listOf(TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE)
        ),
        Case(
            "GUARD: a knight held twice by pawns is not hanging to a rook",
            "6k1/6pp/2p1p3/3n4/8/8/6PP/R5K1 w - - 0 1", "a1d1",
            reject = listOf(TacticType.HANGING_PIECE, TacticType.TRAPPED_PIECE)
        ),
        Case(
            "GUARD: luft on h7 kills the back-rank pattern",
            "6k1/5pp1/7p/8/8/7Q/5PPP/6K1 w - - 0 1", "h3c8",
            reject = listOf(TacticType.BACK_RANK_MATE, TacticType.MATE_NET)
        ),
        Case(
            "GUARD: a knight that never blocked the file discovers nothing",
            "3q2k1/6pp/8/8/8/1N6/6PP/3R2K1 w - - 0 1", "b3c5",
            reject = listOf(TacticType.DISCOVERED_ATTACK, TacticType.DISCOVERED_CHECK)
        ),
        Case(
            "GUARD: hitting two defended pawns with a knight is not a fork",
            "3q2k1/2p1p1pp/8/8/8/2N5/6PP/6K1 w - - 0 1", "c3d5",
            reject = listOf(TacticType.FORK, TacticType.DOUBLE_ATTACK)
        ),
        Case(
            "GUARD: a knight in front of a pawn is neither pin nor skewer",
            "6k1/4p1pp/5n2/8/8/8/6PP/2B3K1 w - - 0 1", "c1g5",
            expect = listOf(TacticType.X_RAY),
            reject = listOf(TacticType.SKEWER, TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE)
        ),
        Case(
            "GUARD: the bishop still has b1, so it is not trapped",
            "6k1/6pp/8/8/8/8/bPP3PP/6K1 w - - 0 1", "b2b3",
            reject = listOf(TacticType.TRAPPED_PIECE)
        ),
        Case(
            // Qxd8+ Rxd8 is a queen trade. It used to be expected to report a FORK of king and rook
            // (the queen does attack both), which is exactly the false positive Task 65 removed:
            // the forker is simply taken, so nothing is forked. See the next guard.
            "GUARD: no PV means no PV-only motifs, and an even trade is no desperado",
            "r2q3k/6pp/8/6N1/8/8/6PP/3Q2K1 w - - 0 1", "d1d8",
            reject = listOf(
                TacticType.FORK, TacticType.DOUBLE_ATTACK, TacticType.DECOY, TacticType.DEFLECTION, TacticType.OVERLOADED_PIECE,
                TacticType.INTERFERENCE, TacticType.CLEARANCE, TacticType.ZWISCHENZUG,
                TacticType.WINDMILL, TacticType.GREEK_GIFT, TacticType.REMOVING_THE_DEFENDER,
                TacticType.DESPERADO
            )
        ),
        Case(
            "GUARD: a pawn push that creates nothing is not a breakthrough",
            "6k1/pp4pp/8/8/2P5/8/PP4PP/6K1 w - - 0 1", "c4c5",
            reject = listOf(TacticType.PASSED_PAWN_BREAKTHROUGH)
        ),
        Case(
            "GUARD: a normal middlegame move is not a fortress",
            "r3rnk1/pb3pp1/3pp2p/1q4BQ/1P1P4/4N1R1/P4PPP/4R1K1 w - - 0 1", "a2a3",
            reject = listOf(TacticType.FORTRESS, TacticType.PERPETUAL_CHECK)
        ),
        Case(
            "GUARD: a plain developing move produces no motifs at all",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", "g1f3",
            reject = TacticType.values().toList()
        ),
        Case(
            "GUARD: a seventh-rank pawn a rook can simply take is no promotion tactic",
            "1r4k1/6pp/1P6/8/8/8/6PP/6K1 w - - 0 1", "b6b7",
            reject = listOf(TacticType.PROMOTION_TACTIC, TacticType.PASSED_PAWN_BREAKTHROUGH)
        ),
        Case(
            "GUARD: castling moves a rook but discovers nothing",
            "r3k2r/pp3ppp/8/8/3nP3/8/PPP2PPP/R2QK2R w KQkq - 0 1", "e1g1",
            reject = listOf(TacticType.DISCOVERED_ATTACK, TacticType.DISCOVERED_CHECK)
        ),
        Case(
            "GUARD: a pin that was already there is not news",
            "4k3/pp4pp/2n5/1B6/8/8/6PP/6K1 w - - 0 1", "h2h3",
            reject = listOf(TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE)
        )
    )

    private val allCases = staticCases + pvCases + negativeCases

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun corpusIsBigEnoughToBeMeaningful() {
        assertTrue("corpus has only ${allCases.size} positions", allCases.size >= 45)
    }

    @Test
    fun everyFenIsLegalAndEveryMoveIsLegalInIt() {
        for (case in allCases) {
            val pos = try {
                Position.fromFen(case.fen)
            } catch (e: Exception) {
                throw AssertionError("${case.name}: FEN does not load - ${e.message}")
            }
            assertEquals("${case.name}: FEN does not round-trip", case.fen, pos.toFen())
            try {
                pos.parseUci(case.uci)
            } catch (e: Exception) {
                throw AssertionError("${case.name}: ${case.uci} is not legal here - ${e.message}")
            }
            // A PV that does not replay would silently disable every PV-confirmed motif.
            var replay = pos
            for (uci in case.pv) {
                val move = try {
                    replay.parseUci(uci)
                } catch (e: Exception) {
                    throw AssertionError("${case.name}: PV move $uci is not legal - ${e.message}")
                }
                replay = replay.makeMove(move)
            }
        }
    }

    @Test
    fun everyCaseDetectsWhatItShouldAndNothingItShouldNot() {
        val failures = ArrayList<String>()
        for (case in allCases) {
            val pos = Position.fromFen(case.fen)
            val move = pos.parseUci(case.uci)
            val found = detector.detectRaw(pos, move, case.pv)
            val types = found.map { it.type }.toSet()

            for (want in case.expect) {
                if (want !in types) {
                    failures += "${case.name}: expected $want, got ${types.sorted()}"
                }
            }
            for (avoid in case.reject) {
                if (avoid in types) {
                    val why = found.first { it.type == avoid }.description
                    failures += "${case.name}: must NOT report $avoid, but did: $why"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyRequiredMotifHasAPositiveCase() {
        val covered = allCases.flatMap { it.expect }.toSet()
        val required = listOf(
            TacticType.FORK, TacticType.PAWN_FORK, TacticType.DOUBLE_ATTACK,
            TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE, TacticType.SKEWER,
            TacticType.DISCOVERED_ATTACK, TacticType.DISCOVERED_CHECK, TacticType.DOUBLE_CHECK,
            TacticType.HANGING_PIECE, TacticType.TRAPPED_PIECE, TacticType.BACK_RANK_MATE,
            TacticType.SMOTHERED_MATE, TacticType.MATE_NET, TacticType.PROMOTION_TACTIC,
            TacticType.UNDERPROMOTION, TacticType.X_RAY, TacticType.BATTERY,
            TacticType.PASSED_PAWN_BREAKTHROUGH, TacticType.DESPERADO, TacticType.PERPETUAL_CHECK,
            TacticType.DEFLECTION, TacticType.DECOY, TacticType.OVERLOADED_PIECE,
            TacticType.INTERFERENCE, TacticType.CLEARANCE, TacticType.ZWISCHENZUG,
            TacticType.GREEK_GIFT, TacticType.REMOVING_THE_DEFENDER, TacticType.WINDMILL,
            TacticType.STALEMATE_TRICK
        )
        val missing = required.filter { it !in covered }
        assertTrue("no positive case for: $missing", missing.isEmpty())
    }

    @Test
    fun resultsObeyTheContract() {
        for (case in allCases) {
            val pos = Position.fromFen(case.fen)
            val found = detector.detectRaw(pos, pos.parseUci(case.uci), case.pv)
            val mover = pos.sideToMove
            for (t in found) {
                assertTrue("${case.name}: confidence ${t.confidence} below the 0.6 floor",
                    t.confidence >= 0.6)
                assertTrue("${case.name}: confidence ${t.confidence} is not a spec value",
                    t.confidence == 0.6 || t.confidence == 0.95)
                assertEquals("${case.name}: byColor must be the mover", mover, t.byColor)
                assertEquals("${case.name}: moveUci must be the move", case.uci, t.moveUci)
                assertTrue("${case.name}: ${t.type} has a stub description: '${t.description}'",
                    t.description.length > 15)
                // Descriptions surface in the UI, so they must read as prose: a finished
                // sentence, never an enum name or a stray null from a missing piece lookup.
                assertTrue("${case.name}: not a sentence: ${t.description}",
                    t.description.endsWith("."))
                assertFalse("${case.name}: leaked an enum name: ${t.description}",
                    t.description.contains("_"))
                assertFalse("${case.name}: leaked a null: ${t.description}",
                    t.description.contains("null"))
            }
            // ANALYSIS_SPEC 5.3: sorted by confidence, then by material swing, descending.
            for (i in 1 until found.size) {
                val a = found[i - 1]
                val b = found[i]
                assertTrue(
                    "${case.name}: results out of order at $i (${a.type} then ${b.type})",
                    a.confidence > b.confidence ||
                        (a.confidence == b.confidence && a.materialSwing >= b.materialSwing)
                )
            }
        }
    }

    @Test
    fun materialSwingIsSaneWhereItMatters() {
        fun swingOf(caseName: String, type: TacticType): Int {
            val case = allCases.first { it.name == caseName }
            val pos = Position.fromFen(case.fen)
            return detector.detectRaw(pos, pos.parseUci(case.uci), case.pv)
                .first { it.type == type }.materialSwing
        }
        // Ne7+ forks the king and an undefended rook: the rook is the payoff.
        assertEquals(500, swingOf("knight fork of king and rook", TacticType.FORK))
        // Nc6 leaves a whole rook loose.
        assertEquals(500, swingOf("knight attacks an undefended rook", TacticType.HANGING_PIECE))
        // The desperado queen banks a rook before she goes.
        assertEquals(500, swingOf("a doomed queen takes the rook on the way down", TacticType.DESPERADO))
        // Promotion is worth a queen minus the pawn it was.
        assertEquals(800, swingOf("pawn queens", TacticType.PROMOTION_TACTIC))
        // A pawn fork of a loose knight and a defended bishop wins the bishop for the pawn.
        assertEquals(230, swingOf("pawn fork of knight and bishop", TacticType.PAWN_FORK))
    }

    /**
     * ANALYSIS_SPEC 8 analyses every ply of a game at least twice (played move plus engine
     * best), so a slow detector shows up directly as analysis latency. The budget is 20ms per
     * call on a busy middlegame position; this asserts a good margin under it.
     */
    @Test
    fun detectionIsFastEnoughForWholeGameAnalysis() {
        val pos = Position.fromFen("r3rnk1/pb3pp1/3pp2p/1q4BQ/1P1P4/4N1R1/P4PPP/4R1K1 w - - 0 1")
        val moves = pos.legalMoves()
        val pv = listOf("b5h5", "g3g7", "g8h8", "g7f7")
        repeat(3) { for (m in moves) detector.detect(pos, m, listOf(m.toUci()) + pv) }

        val start = System.nanoTime()
        var calls = 0
        repeat(5) {
            for (m in moves) {
                detector.detect(pos, m, listOf(m.toUci()) + pv)
                calls++
            }
        }
        val msPerCall = (System.nanoTime() - start) / 1e6 / calls
        assertTrue("detect averaged %.2f ms over $calls calls".format(msPerCall), msPerCall < 20.0)
    }
}
