package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.pgn.PgnGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.7 - Round 13, task 64: pacing tiers and the length budget.
 *
 * Two kinds of evidence. The *real-game* tests replay recorded Stockfish analyses of the two fixture
 * games through the production pipeline ([RealGameFixture]) and pin the symptoms the owner measured:
 * a 17-move miniature that came out at eleven minutes, eight-ply walks for mere inaccuracies, and a
 * twenty-three minute Immortal Game. The *synthetic* tests plant exactly the situations a rule is
 * about (five blunders for a cap of three) so a failure names the rule that broke.
 */
class PacingTiersTest {

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun script(
        game: RealGameFixture.Game,
        options: NarrationOptions = NarrationOptions(),
        userColor: Color? = null
    ): Pair<GameReport, VideoScript> {
        val report = game.report(userColor)
        return report to VideoScriptGenerator(userColor).generate(report, game.pgn, options)
    }

    /** One missed-tactic detour: the contiguous run of MISSED_TACTIC segments about one ply. */
    private class Detour(val ply: Int, val plies: Int, val hasPuzzle: Boolean)

    private fun detours(s: VideoScript): List<Detour> {
        val out = ArrayList<Detour>()
        val bodyPlies = s.segments.mapNotNull { it.ply }.distinct()
        for (ply in bodyPlies) {
            val mine = s.segments.filter { it.ply == ply }
            val walked = mine.count { it.kind == SegmentKind.MISSED_TACTIC && it.board is BoardDirective.PlayMove }
            val isDetour = mine.any { it.kind == SegmentKind.MISSED_TACTIC }
            if (isDetour) out.add(Detour(ply, walked, mine.any { it.kind == SegmentKind.PUZZLE_PROMPT }))
        }
        return out
    }

    private fun budgetMs(plies: Int): Long = VideoScriptGenerator.budgetMs((plies + 1) / 2)

    private val games = listOf("chesscom_style_game" to RealGameFixture.chesscom, "immortal" to RealGameFixture.immortal)

    // ---------------------------------------------------------------------------------------------
    // The owner's measurements
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a seventeen-move game lands between three and six minutes, not eleven`() {
        val (_, s) = script(RealGameFixture.chesscom)
        val minutes = s.totalEstimatedMs / 60_000.0
        assertTrue("17-move game ran $minutes min, ${s.segments.size} beats", s.totalEstimatedMs in 180_000..360_000)
        assertTrue("was 70 beats before the rework; got ${s.segments.size}", s.segments.size <= 40)
    }

    @Test
    fun `a twenty-three move game stays inside its budget, far below twenty-three minutes`() {
        val (report, s) = script(RealGameFixture.immortal)
        assertTrue(
            "ran ${s.storyMs / 1000}s against a ${budgetMs(report.annotations.size) / 1000}s budget",
            s.storyMs <= budgetMs(report.annotations.size)
        )
        assertTrue("was 160 beats before the rework; got ${s.segments.size}", s.segments.size <= 80)
    }

    @Test
    fun `no real game ever exceeds twelve minutes`() {
        for ((name, g) in games) {
            val (_, s) = script(g)
            assertTrue("$name: ${s.storyMs / 1000}s", s.storyMs <= 720_000)
        }
    }

    @Test
    fun `an inaccuracy is never walked and never gets a puzzle`() {
        for ((name, g) in games) {
            val (report, s) = script(g)
            val inaccuracies = report.annotations.filter { it.classification == MoveClassification.INACCURACY }
            assertTrue("$name should contain inaccuracies", inaccuracies.isNotEmpty())
            for (a in inaccuracies) {
                val walked = s.segments.filter {
                    it.ply == a.ply && (it.kind == SegmentKind.MISSED_TACTIC || it.kind == SegmentKind.PUZZLE_PROMPT)
                }
                assertTrue("$name: ply ${a.ply} (${a.san}, an inaccuracy) was walked: ${walked.map { it.kind }}", walked.isEmpty())
            }
        }
    }

    @Test
    fun `the inaccuracy that used to take seventy seconds takes a sentence or two`() {
        // 4...Bxf3 in the 17-move game: an inaccuracy with a missed deflection.
        val (report, s) = script(RealGameFixture.chesscom)
        val ply = report.annotations.first { it.san == "Bxf3" && it.color == Color.BLACK }.ply
        val spent = s.segments.filter { it.ply == ply }.sumOf { it.estimatedSpeechMs + it.holdAfterMs }
        assertTrue("took ${spent}ms", spent in 1..12_000)
    }

    @Test
    fun `book moves, forced moves and recaptures get no beat of their own`() {
        for ((name, g) in games) {
            val (report, s) = script(g)
            val moves = g.pgn.moves
            for (a in report.annotations) {
                val recapture = a.ply >= 2 &&
                    moves[a.ply - 1].san.contains('x') && moves[a.ply - 2].san.contains('x') &&
                    moves[a.ply - 1].uci.substring(2, 4) == moves[a.ply - 2].uci.substring(2, 4)
                val routine = a.classification == MoveClassification.BOOK ||
                    a.classification == MoveClassification.FORCED ||
                    (recapture && a.classification in setOf(
                        MoveClassification.BEST, MoveClassification.EXCELLENT, MoveClassification.GOOD
                    ))
                if (!routine) continue
                val own = s.segments.filter { it.ply == a.ply && it.kind != SegmentKind.OPENING_SUMMARY }
                assertTrue("$name: ply ${a.ply} ${a.san} (${a.classification}) got ${own.map { it.kind }}", own.isEmpty())
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FULL cap, DWELL length, the turning point and the mate
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `no detour is longer than eight plies, and only one with a puzzle is longer than four`() {
        for ((name, g) in games) {
            val (_, s) = script(g)
            for (d in detours(s)) {
                assertTrue("$name: ply ${d.ply} walked ${d.plies} plies", d.plies <= 8)
                if (!d.hasPuzzle) assertTrue("$name: ply ${d.ply} walked ${d.plies} plies without a puzzle", d.plies <= 4)
            }
        }
    }

    @Test
    fun `at most three full moments per real game`() {
        for ((name, g) in games) {
            val (_, s) = script(g)
            val full = s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }.mapNotNull { it.ply }.toSet() +
                detours(s).filter { it.plies > 4 }.map { it.ply }
            assertTrue("$name: full moments at ${full.sorted()}", full.size <= 3)
        }
    }

    @Test
    fun `the turning point is always told in full, and the checkmate always has a beat`() {
        for ((name, g) in games) {
            val (report, s) = script(g)
            val tp = report.annotations
                .filter { it.loss > 0.5 && it.classification != MoveClassification.BOOK && it.classification != MoveClassification.FORCED }
                .maxWithOrNull(compareBy({ it.loss }, { -it.ply }))!!
            assertTrue(
                "$name: no TURNING_POINT beat at ply ${tp.ply}",
                s.segments.any { it.kind == SegmentKind.TURNING_POINT && it.ply == tp.ply }
            )
            val mate = report.annotations.last()
            assertTrue(mate.san.endsWith("#"))
            assertTrue("$name: the mate has no beat", s.segments.any { it.ply == mate.ply })
        }
    }

    @Test
    fun `the checkmate survives even when speech is so slow that everything else has to go`() {
        for ((name, g) in games) {
            val slow = NarrationOptions(speechWpm = 60)
            val (report, s) = script(g, slow)
            val mate = report.annotations.last()
            assertTrue("$name: mate beat lost at 60 wpm", s.segments.any { it.ply == mate.ply })
            assertTrue("$name: no body at all at 60 wpm", s.segments.any { it.ply != null })
        }
    }

    @Test
    fun `slower speech gives up beats instead of overrunning, and never pads`() {
        val (_, normal) = script(RealGameFixture.immortal)
        val (_, slow) = script(RealGameFixture.immortal, NarrationOptions(speechWpm = 110))
        val normalBody = normal.segments.count { it.ply != null }
        val slowBody = slow.segments.count { it.ply != null }
        assertTrue("slower voice should narrate no more beats ($slowBody vs $normalBody)", slowBody <= normalBody)

        // A threshold nothing clears leaves only the structural beats, the mate and one fallback:
        // the script is as short as the game is quiet, not stretched to the budget.
        val (_, quiet) = script(RealGameFixture.immortal, NarrationOptions(significanceThresholdCp = 1_000_000))
        assertTrue("a quiet script ran ${quiet.totalEstimatedMs / 1000}s", quiet.totalEstimatedMs < 200_000)
    }

    @Test
    fun `three or more skipped plies become exactly one skip-ahead connective`() {
        for ((name, g) in games) {
            val (report, s) = script(g)
            // Body beats in order, with the connectives between them (ply == null, NORMAL_MOVE).
            val body = s.segments.filter {
                it.ply != null && it.kind != SegmentKind.OPENING_SUMMARY
            }
            val first = body.first().index
            val last = body.last().index
            val connectivesAt = s.segments
                .filter { it.index in first..last && it.ply == null && it.kind == SegmentKind.NORMAL_MOVE }
                .map { it.index }
            val bodyPlies = body.mapNotNull { it.ply }.distinct()
            var expected = 0
            for ((p, q) in bodyPlies.zipWithNext()) if (q - p - 1 >= 3) expected++
            assertEquals("$name: connectives between ${bodyPlies}", expected, connectivesAt.size)
            assertTrue(report.annotations.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The tactic tags on the two cases the owner named (task 65, end to end)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `17-Rd8 mate carries only its mating pattern and 15-Bxd7 is no windmill or trapped piece`() {
        val report = RealGameFixture.chesscom.report()
        val mate = report.annotations.last()
        assertEquals("Rd8#", mate.san)
        assertEquals(listOf(TacticType.MATE_NET), mate.tacticsFound.map { it.type })

        val bxd7 = report.annotations.first { it.san == "Bxd7+" && it.color == Color.WHITE && it.ply > 20 }
        val types = bxd7.tacticsFound.map { it.type }
        assertFalse(types.toString(), TacticType.WINDMILL in types)
        assertFalse(types.toString(), TacticType.TRAPPED_PIECE in types)
        assertFalse(types.toString(), TacticType.FORK in types)
    }

    @Test
    fun `no real move carries more than two tactics, and a mating move carries only mating motifs`() {
        for ((name, g) in games) {
            val report = g.report()
            for (a in report.annotations) {
                for ((label, list) in listOf("found" to a.tacticsFound, "missed" to a.tacticsMissed, "threat" to a.threatsAllowed)) {
                    assertTrue("$name ply ${a.ply} ${a.san}: $label ${list.map { it.type }}", list.size <= 2)
                }
            }
            val mate = report.annotations.last()
            assertTrue(mate.tacticsFound.isNotEmpty() && mate.tacticsFound.all {
                it.type in setOf(TacticType.MATE_NET, TacticType.BACK_RANK_MATE, TacticType.SMOTHERED_MATE)
            })
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic: the cap and the tiers, with exactly the situation each rule is about
    // ---------------------------------------------------------------------------------------------

    private val game: PgnGame = NarrationFixture.game()
    private val baseline: GameReport = NarrationFixture.report(Color.WHITE)

    /** A legal alternative to the played move, and a quiet line of [plies] moves starting with it. */
    private fun alternative(a: MoveAnnotation, plies: Int): Pair<String, List<String>> {
        var pos = Position.fromFen(a.fenBefore)
        val first = pos.legalMoves().first { it.toUci() != a.uci && !it.isCapture && it.promotion == null }
        val sans = ArrayList<String>()
        var move = first
        while (sans.size < plies) {
            sans.add(pos.moveToSan(move))
            pos = pos.makeMove(move)
            move = pos.legalMoves().firstOrNull { !it.isCapture && it.promotion == null } ?: break
        }
        return first.toUci() to sans
    }

    private class Planted(
        val loss: Double,
        val classification: MoveClassification,
        val missesAFork: Boolean,
        val findsAFork: Boolean = false
    )

    /**
     * Every ply flat and GOOD, except the plies in [planted], which carry the given loss and
     * classification, a 300cp swing, and (when asked) a missed fork worth a rook with an 8-ply line.
     */
    private fun planted(planted: Map<Int, Planted>): GameReport {
        val annotations = baseline.annotations.map { a ->
            val p = planted[a.ply]
            if (p == null) {
                a.copy(
                    classification = MoveClassification.GOOD, loss = 0.0, winPercentBefore = 50.0, winPercentAfter = 50.0,
                    evalBeforeCp = 0, evalAfterCp = 0, mateInBefore = null, mateInAfter = null, evalSecondBestCp = null,
                    tacticsFound = emptyList(), tacticsMissed = emptyList(), threatsAllowed = emptyList(),
                    simulation = null, bestMoveUci = a.uci, bestMoveSan = a.san, bestLineSan = emptyList(),
                    san = a.san.removeSuffix("#")
                )
            } else {
                val (alt, line) = alternative(a, 8)
                val sign = if (a.color == Color.WHITE) -1 else 1
                a.copy(
                    classification = p.classification, loss = p.loss, winPercentBefore = 60.0, winPercentAfter = 60.0 - p.loss,
                    evalBeforeCp = 0, evalAfterCp = sign * 300, mateInBefore = null, mateInAfter = null,
                    evalSecondBestCp = null,
                    tacticsFound = if (p.findsAFork) listOf(
                        TacticInstance(TacticType.FORK, a.color, a.uci, materialSwing = 500, confidence = 0.95)
                    ) else emptyList(),
                    tacticsMissed = if (p.missesAFork) listOf(
                        TacticInstance(TacticType.FORK, a.color, alt, materialSwing = 500, confidence = 0.95)
                    ) else emptyList(),
                    threatsAllowed = emptyList(), simulation = null,
                    bestMoveUci = alt, bestMoveSan = line.first(), bestLineSan = line,
                    san = a.san.removeSuffix("#")
                )
            }
        }
        return baseline.copy(
            annotations = annotations,
            evalGraph = List(annotations.size + 1) { 50.0 },
            keyMoments = emptyList(),
            white = baseline.white.copy(classificationCounts = emptyMap(), tacticsFound = emptyList(), tacticsMissed = emptyList()),
            black = baseline.black.copy(classificationCounts = emptyMap(), tacticsFound = emptyList(), tacticsMissed = emptyList())
        )
    }

    /** 400 wpm keeps these small games far under the length budget, so only the tier rules act. */
    private fun fast() = NarrationOptions(speechWpm = 400)

    private fun generate(report: GameReport, options: NarrationOptions = fast()) =
        VideoScriptGenerator(Color.WHITE).generate(report, game, options)

    @Test
    fun `five puzzle-worthy blunders get three full treatments, ranked by loss with ties to the earlier move`() {
        val report = planted(
            mapOf(
                12 to Planted(30.0, MoveClassification.BLUNDER, true),
                14 to Planted(40.0, MoveClassification.BLUNDER, true),
                20 to Planted(40.0, MoveClassification.BLUNDER, true),
                24 to Planted(40.0, MoveClassification.BLUNDER, true),
                28 to Planted(25.0, MoveClassification.BLUNDER, true)
            )
        )
        val s = generate(report)
        val puzzles = s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }.mapNotNull { it.ply }
        // Turning point = the first of the 40s (ply 14); the next two by loss, earlier first: 20, 24.
        assertEquals(listOf(14, 20, 24), puzzles)

        val byPly = detours(s).associateBy { it.ply }
        for (full in listOf(14, 20, 24)) {
            assertTrue("ply $full walks the full line (${byPly[full]?.plies})", (byPly[full]?.plies ?: 0) > 4)
        }
        // The two beyond the cap are still told, as a short variation with no puzzle.
        for (demoted in listOf(12, 28)) {
            val d = byPly[demoted]
            assertTrue("ply $demoted should still have its variation", d != null)
            assertFalse("ply $demoted must not stop the video to ask", d!!.hasPuzzle)
            assertTrue("ply $demoted walked ${d.plies} plies", d.plies in 1..4)
        }
    }

    @Test
    fun `the turning point is full even when it is not a puzzle, and takes a slot from the blunders`() {
        // A mistake with no missed tactic is the biggest loss, so it is the turning point; it is
        // told in full (error beat plus the turning-point beat) and costs one of the three slots.
        val report = planted(
            mapOf(
                16 to Planted(35.0, MoveClassification.MISTAKE, false),
                12 to Planted(22.0, MoveClassification.BLUNDER, true),
                20 to Planted(21.0, MoveClassification.BLUNDER, true),
                24 to Planted(20.0, MoveClassification.BLUNDER, true)
            )
        )
        val s = generate(report)
        assertTrue(s.segments.any { it.kind == SegmentKind.TURNING_POINT && it.ply == 16 })
        assertEquals(listOf(12, 20), s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }.mapNotNull { it.ply })
        val d24 = detours(s).first { it.ply == 24 }
        assertFalse(d24.hasPuzzle)
        assertTrue(d24.plies in 1..4)
    }

    @Test
    fun `a mistake that missed a winning tactic can be full, one that missed nothing is only dwelt on`() {
        val report = planted(
            mapOf(
                14 to Planted(15.0, MoveClassification.MISTAKE, true),
                22 to Planted(14.0, MoveClassification.MISTAKE, false)
            )
        )
        val s = generate(report)
        // Both are told. The first earns the puzzle; the second never walks anything.
        assertTrue(s.segments.any { it.kind == SegmentKind.PUZZLE_PROMPT && it.ply == 14 })
        assertTrue(s.segments.any { it.ply == 22 })
        assertTrue(s.segments.none { it.ply == 22 && it.kind == SegmentKind.MISSED_TACTIC })
    }

    @Test
    fun `an inaccuracy that missed a rook is one short beat`() {
        // A bigger mistake elsewhere is the turning point, so the inaccuracy is just an inaccuracy.
        val report = planted(
            mapOf(
                14 to Planted(7.0, MoveClassification.INACCURACY, true),
                20 to Planted(30.0, MoveClassification.MISTAKE, false)
            )
        )
        val s = generate(report)
        val mine = s.segments.filter { it.ply == 14 }
        assertEquals(1, mine.size)
        assertTrue(mine.single().estimatedSpeechMs < 12_000)
        assertTrue(s.segments.none { it.kind == SegmentKind.PUZZLE_PROMPT })
    }

    @Test
    fun `an inaccuracy that is the turning point is still never walked`() {
        // The cleanest game there is: its biggest loss is a mere inaccuracy. That is the turning
        // point, so it is told in full - but "in full" for an inaccuracy is a beat and the
        // turning-point beat, never a puzzle and never a walked line.
        val s = generate(planted(mapOf(14 to Planted(7.0, MoveClassification.INACCURACY, true))))
        val kinds = s.segments.filter { it.ply == 14 }.map { it.kind }
        assertTrue(kinds.toString(), SegmentKind.TURNING_POINT in kinds)
        assertTrue(kinds.toString(), kinds.none { it == SegmentKind.MISSED_TACTIC || it == SegmentKind.PUZZLE_PROMPT })
    }

    @Test
    fun `puzzle pauses can still be turned off, without touching the tiers`() {
        val report = planted(mapOf(14 to Planted(40.0, MoveClassification.BLUNDER, true)))
        val s = generate(report, NarrationOptions(speechWpm = 400, includePuzzlePrompts = false))
        assertTrue(s.segments.none { it.kind == SegmentKind.PUZZLE_PROMPT })
        assertTrue(detours(s).any { it.ply == 14 && it.plies > 4 })
    }

    @Test
    fun `a game too short for its blunders gives up its other full moments before the turning point`() {
        // Truncate to a six-move game with three puzzle blunders. Three full detours alone are far
        // over the 106-second budget a six-move game now gets (ANALYSIS_SPEC 9.7), so the cheaper
        // ones fall to dwell length first; the turning point is the last body beat to give way, and
        // is still told however far it has to fall.
        val short = planted(
            mapOf(
                4 to Planted(30.0, MoveClassification.BLUNDER, true),
                8 to Planted(35.0, MoveClassification.BLUNDER, true),
                10 to Planted(25.0, MoveClassification.BLUNDER, true)
            )
        )
        val trimmed = short.copy(
            annotations = short.annotations.take(12),
            evalGraph = short.evalGraph.take(13)
        )
        val s = VideoScriptGenerator(Color.WHITE).generate(trimmed, game, NarrationOptions())
        val budget = budgetMs(12)
        assertEquals(106_000L, budget)
        assertTrue("ran ${s.storyMs / 1000}s vs budget ${budget / 1000}s", s.storyMs <= budget)
        val puzzles = s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }.mapNotNull { it.ply }
        assertTrue("a puzzle other than the turning point's survived: $puzzles", puzzles.all { it == 8 })
        assertTrue("the turning point is still told", s.segments.any { it.ply == 8 })
    }

    @Test
    fun `book moves are skipped even when the engine disagrees with them`() {
        // Ply 4 is in the opening book. Mark it a big missed tactic: it still gets no beat.
        // A real blunder elsewhere keeps the quiet-game fallback (largest swing) out of the picture.
        val report = planted(mapOf(20 to Planted(30.0, MoveClassification.MISTAKE, false)))
        val tweaked = report.copy(
            annotations = report.annotations.map { a ->
                if (a.ply == 4) {
                    val (alt, line) = alternative(a, 3)
                    a.copy(
                        classification = MoveClassification.BOOK, loss = 20.0, evalBeforeCp = 0, evalAfterCp = -300,
                        bestMoveUci = alt, bestMoveSan = line.first(), bestLineSan = line,
                        tacticsMissed = listOf(TacticInstance(TacticType.FORK, a.color, alt, materialSwing = 500, confidence = 0.95))
                    )
                } else a
            }
        )
        val s = generate(tweaked)
        assertTrue(s.segments.none { it.ply == 4 && it.kind != SegmentKind.OPENING_SUMMARY })
    }


    // ---------------------------------------------------------------------------------------------
    // Brilliant and great moves are never the budget's victims
    // ---------------------------------------------------------------------------------------------

    private fun spentOn(s: VideoScript, ply: Int): Long =
        s.segments.filter { it.ply == ply }.sumOf { it.estimatedSpeechMs + it.holdAfterMs }

    @Test
    fun `the Immortal Game's famous sacrifices are told at dwell length or more`() {
        val (report, s) = script(RealGameFixture.immortal)
        fun ply(san: String, color: Color) = report.annotations.first { it.san == san && it.color == color }.ply
        // 22.Qf6+ is the queen sacrifice, 21.Nxg7+ the knight sacrifice that opens the mate, and 18.Bd6
        // the move that leaves both rooks en prise (the rook sacrifice). A DWELL beat is at least nine
        // seconds; a BRIEF one is about five.
        for ((label, p) in listOf(
            "queen sacrifice 22.Qf6+" to ply("Qf6+", Color.WHITE),
            "knight sacrifice 21.Nxg7+" to ply("Nxg7+", Color.WHITE),
            "rook sacrifice 18.Bd6" to ply("Bd6", Color.WHITE)
        )) {
            assertTrue("$label got ${spentOn(s, p)}ms", spentOn(s, p) >= 9_000)
        }
        // Slower speech squeezes the budget harder; the protected beats still stand.
        val (_, slow) = script(RealGameFixture.immortal, NarrationOptions(speechWpm = 110))
        for (san in listOf("Qf6+", "Nxg7+")) {
            val p = report.annotations.first { it.san == san && it.color == Color.WHITE }.ply
            assertTrue("$san at 110 wpm got ${spentOn(slow, p)}ms", spentOn(slow, p) >= 9_000)
        }
    }

    @Test
    fun `every brilliant move in the real games is told at dwell length or more`() {
        for ((name, g) in games) {
            for (wpm in listOf(165, 110)) {
                val (report, s) = script(g, NarrationOptions(speechWpm = wpm))
                for (a in report.annotations.filter { it.classification == MoveClassification.BRILLIANT }) {
                    assertTrue("$name ply ${a.ply} ${a.san} at $wpm wpm got ${spentOn(s, a.ply)}ms", spentOn(s, a.ply) >= 9_000)
                }
            }
        }
    }

    @Test
    fun `a budget-demoted beat never says a pleasantry`() {
        // Flavour lines that say nothing. They survive only in EVERY_MOVE, the complete walkthrough.
        val filler = listOf(
            "Top of the engine's list", "Best move on the board", "The engine agrees", "No complaints",
            "Sensible.", "Fine.", "Safety first", "Good, the king's off the middle"
        )
        for ((name, g) in games) {
            for (wpm in listOf(165, 110)) {
                val (_, s) = script(g, NarrationOptions(speechWpm = wpm))
                for (seg in s.segments) {
                    for (f in filler) assertFalse("$name ($wpm wpm) beat ${seg.index}: '$f' in ${seg.narration}", f in seg.narration)
                }
            }
        }
    }

    @Test
    fun `four blunders and one brilliancy - the brilliancy is told, and can take a full slot`() {
        val report = planted(
            mapOf(
                12 to Planted(40.0, MoveClassification.BLUNDER, true),
                16 to Planted(30.0, MoveClassification.BLUNDER, true),
                20 to Planted(22.0, MoveClassification.BLUNDER, true),
                28 to Planted(21.0, MoveClassification.BLUNDER, true),
                24 to Planted(0.0, MoveClassification.BRILLIANT, false, findsAFork = true)
            )
        )
        val s = generate(report)
        // Turning point (40), then 30, then the brilliancy (scored as a 25-point loss) beats 22 and 21.
        assertEquals(listOf(12, 16), s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }.mapNotNull { it.ply })
        val brilliant = s.segments.filter { it.ply == 24 }
        assertTrue("the brilliancy was not told", brilliant.isNotEmpty())
        assertTrue(brilliant.any { it.narration.lowercase().contains("brilliant") })
        // The two smallest blunders lost the slots, but are still told as short variations.
        for (p in listOf(20, 28)) assertTrue(detours(s).any { it.ply == p && it.plies in 1..4 })
    }

    @Test
    fun `a brilliancy survives a budget that has no room for it`() {
        // Slowest speech: the budget is hopeless. Everything that can give way does; the brilliancy and
        // the turning point do not.
        val report = planted(
            mapOf(
                12 to Planted(40.0, MoveClassification.BLUNDER, true),
                16 to Planted(30.0, MoveClassification.BLUNDER, true),
                20 to Planted(22.0, MoveClassification.BLUNDER, true),
                28 to Planted(21.0, MoveClassification.BLUNDER, true),
                24 to Planted(0.0, MoveClassification.BRILLIANT, false, findsAFork = true)
            )
        )
        val s = generate(report, NarrationOptions(speechWpm = 60))
        assertTrue(spentOn(s, 24) >= 9_000)
        // The turning point gives way last, and only as far as the budget forces: it may fall to a
        // single beat of its own (the separate "turning point" segment then folds into it), but it is
        // still told.
        assertTrue(s.segments.any { it.ply == 12 })
    }

    @Test
    fun `generation is still deterministic with pacing and demotions`() {
        for ((_, g) in games) {
            val report = g.report()
            val a = VideoScriptGenerator(null).generate(report, g.pgn, NarrationOptions())
            val b = VideoScriptGenerator(null).generate(report, g.pgn, NarrationOptions())
            assertEquals(a, b)
        }
    }
}
