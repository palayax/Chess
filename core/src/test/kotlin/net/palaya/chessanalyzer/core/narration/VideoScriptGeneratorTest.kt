package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.pgn.PgnGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the narrated-video script, run over the report
 * [NarrationFixture] builds from the real `chesscom_style_game.pgn`.
 *
 * The load-bearing one is [no segment narration contains algebraic notation] — the narration
 * track is spoken by a TTS engine, and notation in it is the single failure mode that makes the
 * whole feature unusable.
 */
class VideoScriptGeneratorTest {

    private val report: GameReport = NarrationFixture.report(Color.WHITE)
    private val game: PgnGame = NarrationFixture.game()

    private fun script(
        depth: NarrationDepth = NarrationDepth.HIGHLIGHTS,
        style: NarrationStyle = NarrationStyle.COACH,
        userColor: Color? = Color.WHITE,
        puzzles: Boolean = true
    ): VideoScript = VideoScriptGenerator(userColor).generate(
        report, game, NarrationOptions(depth = depth, style = style, includePuzzlePrompts = puzzles)
    )

    // -----------------------------------------------------------------------
    // The quality property the whole feature rests on
    // -----------------------------------------------------------------------

    /** SAN piece moves, pawn captures, and castling — everything a TTS voice cannot read aloud. */
    private val notation = Regex(
        "\\b[KQRBN][a-h]?[1-8]?x?[a-h][1-8]\\b" +
            "|\\b[a-h]x[a-h][1-8]\\b" +
            "|\\bO-O(-O)?\\b|\\b0-0(-0)?\\b"
    )

    /** Even a bare square is unspeakable; the generator promises "e four", never "e4". */
    private val bareSquare = Regex("\\b[a-h][1-8]\\b")

    @Test
    fun `no segment narration contains algebraic notation`() {
        val scripts = listOf(
            script(NarrationDepth.HIGHLIGHTS),
            script(NarrationDepth.EVERY_MOVE),
            script(NarrationDepth.MISTAKES_ONLY),
            script(style = NarrationStyle.ANALYST),
            script(userColor = null),
            script(userColor = Color.BLACK),
            script(puzzles = false)
        )
        for (s in scripts) {
            for (seg in s.segments) {
                val hit = notation.find(seg.narration)
                assertTrue(
                    "segment ${seg.index} (${seg.kind}) speaks notation '${hit?.value}': ${seg.narration}",
                    hit == null
                )
                val square = bareSquare.find(seg.narration)
                assertTrue(
                    "segment ${seg.index} (${seg.kind}) speaks a bare square '${square?.value}': ${seg.narration}",
                    square == null
                )
            }
        }
    }

    @Test
    fun `captions do carry the notation, since they are read not heard`() {
        val s = script()
        val moveCaptions = s.segments.filter { it.ply != null }.map { it.caption }
        assertTrue("expected at least one caption with real notation", moveCaptions.any { notation.containsMatchIn(it) })
    }

    // -----------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------

    @Test
    fun `script opens with an intro, names the opening and closes on the lessons`() {
        val s = script()
        assertEquals(SegmentKind.INTRO, s.segments.first().kind)
        assertEquals(SegmentKind.OPENING_SUMMARY, s.segments[1].kind)
        assertEquals(SegmentKind.OUTRO_LESSONS, s.segments.last().kind)
        assertTrue(s.segments.any { it.kind == SegmentKind.OUTRO_SUMMARY })
        assertTrue(s.segments.any { it.kind == SegmentKind.TURNING_POINT })
        assertTrue(s.segments[1].narration.contains("Philidor"))
    }

    @Test
    fun `a game with a missed tactic gets a missed tactic or key moment segment`() {
        assertTrue("fixture should have missed tactics", report.white.tacticsMissed.isNotEmpty())
        val s = script()
        val interesting = s.segments.filter {
            it.kind == SegmentKind.MISSED_TACTIC || it.kind == SegmentKind.KEY_MOMENT
        }
        assertTrue("expected a missed-tactic or key-moment segment", interesting.isNotEmpty())
    }

    @Test
    fun `segment indices are contiguous from zero`() {
        for (depth in NarrationDepth.values()) {
            val s = script(depth)
            s.segments.forEachIndexed { i, seg -> assertEquals("depth $depth", i, seg.index) }
        }
    }

    @Test
    fun `chapters are ordered, in range and cover the video`() {
        for (depth in NarrationDepth.values()) {
            val s = script(depth)
            assertTrue("depth $depth has no chapters", s.chapters.isNotEmpty())
            var previous = -1
            for (c in s.chapters) {
                assertTrue("chapter '${c.title}' out of range", c.startSegmentIndex in s.segments.indices)
                assertTrue("chapters out of order at '${c.title}'", c.startSegmentIndex > previous)
                assertTrue("chapter title blank", c.title.isNotBlank())
                previous = c.startSegmentIndex
            }
            assertEquals(0, s.chapters.first().startSegmentIndex)
        }
    }

    // -----------------------------------------------------------------------
    // Depth, timing, determinism
    // -----------------------------------------------------------------------

    @Test
    fun `depth changes the length of the script`() {
        val mistakes = script(NarrationDepth.MISTAKES_ONLY).segments.size
        val highlights = script(NarrationDepth.HIGHLIGHTS).segments.size
        val everyMove = script(NarrationDepth.EVERY_MOVE).segments.size
        assertTrue("MISTAKES_ONLY ($mistakes) should be shorter than HIGHLIGHTS ($highlights)", mistakes < highlights)
        assertTrue("HIGHLIGHTS ($highlights) should be shorter than EVERY_MOVE ($everyMove)", highlights < everyMove)
    }

    @Test
    fun `every move depth speaks every ply`() {
        val s = script(NarrationDepth.EVERY_MOVE)
        val spokenPlies = s.segments.mapNotNull { it.ply }.toSet()
        for (a in report.annotations) {
            assertTrue("ply ${a.ply} was never narrated", a.ply in spokenPlies)
        }
    }

    @Test
    fun `generation is deterministic`() {
        for (depth in NarrationDepth.values()) {
            for (style in NarrationStyle.values()) {
                val a = VideoScriptGenerator(Color.WHITE)
                    .generate(report, game, NarrationOptions(depth = depth, style = style))
                val b = VideoScriptGenerator(Color.WHITE)
                    .generate(report, game, NarrationOptions(depth = depth, style = style))
                assertEquals("$depth/$style segments differ", a.segments, b.segments)
                assertEquals("$depth/$style chapters differ", a.chapters, b.chapters)
                assertEquals(a.totalEstimatedMs, b.totalEstimatedMs)
            }
        }
        // And a second report built from the same fixture must produce the same script too.
        val rebuilt = NarrationFixture.report(Color.WHITE)
        assertEquals(
            script().segments.map { it.narration },
            VideoScriptGenerator(Color.WHITE).generate(rebuilt, game, NarrationOptions()).segments.map { it.narration }
        )
    }

    @Test
    fun `speech estimate is derived from word count and is never zero for real narration`() {
        val s = script()
        for (seg in s.segments) {
            assertTrue("segment ${seg.index} has empty narration", seg.narration.isNotBlank())
            assertTrue("segment ${seg.index} estimated at 0ms", seg.estimatedSpeechMs > 0)
        }
        // Monotonic in word count, and responsive to the configured rate.
        val short = VideoScriptGenerator.estimateSpeechMs("one two three.", 165)
        val long = VideoScriptGenerator.estimateSpeechMs("one two three four five six seven eight nine ten.", 165)
        assertTrue(long > short)
        assertTrue(VideoScriptGenerator.estimateSpeechMs("a b c d e f.", 100) >
            VideoScriptGenerator.estimateSpeechMs("a b c d e f.", 300))
        assertEquals(0L, VideoScriptGenerator.estimateSpeechMs("   ", 165))

        // Roughly right: ~165 wpm means ~10 words takes about 3.6 seconds.
        val tenWords = VideoScriptGenerator.estimateSpeechMs("one two three four five six seven eight nine ten.", 165)
        assertTrue("got ${tenWords}ms", tenWords in 3_000..5_000)
    }

    @Test
    fun `total estimated ms is speech plus holds`() {
        val s = script()
        val expected = s.segments.sumOf { it.estimatedSpeechMs + it.holdAfterMs }
        assertEquals(expected, s.totalEstimatedMs)
        assertTrue("a full script should be more than 30 seconds", s.totalEstimatedMs > 30_000)
    }

    // -----------------------------------------------------------------------
    // Puzzle prompts
    // -----------------------------------------------------------------------

    @Test
    fun `puzzle prompts pause, and are always followed by the reveal`() {
        val s = script()
        val prompts = s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }
        assertTrue("fixture should produce at least one puzzle prompt", prompts.isNotEmpty())
        for (p in prompts) {
            assertTrue("prompt ${p.index} has no pause", p.holdAfterMs in 2_500..4_000)
            val next = s.segments[p.index + 1]
            assertEquals("prompt ${p.index} is not followed by the reveal", SegmentKind.MISSED_TACTIC, next.kind)
            assertEquals(p.ply, next.ply)
            assertTrue(p.narration.lowercase().contains("pause") || p.narration.lowercase().contains("stop"))
        }
    }

    @Test
    fun `puzzle prompts can be turned off`() {
        val s = script(puzzles = false)
        assertTrue(s.segments.none { it.kind == SegmentKind.PUZZLE_PROMPT })
        assertTrue(s.segments.any { it.kind == SegmentKind.MISSED_TACTIC })
    }

    // -----------------------------------------------------------------------
    // Voice
    // -----------------------------------------------------------------------

    @Test
    fun `coach style addresses the user directly, analyst style never does`() {
        val coach = script(style = NarrationStyle.COACH).segments.joinToString(" ") { it.narration }
        assertTrue("coach style should use second person", Regex("\\byou\\b", RegexOption.IGNORE_CASE).containsMatchIn(coach))

        // ANALYST narrates the moves in neutral third person. The puzzle prompt and the closing
        // lessons still speak to the viewer, because that is who they are addressed to.
        val viewerFacing = setOf(SegmentKind.PUZZLE_PROMPT, SegmentKind.OUTRO_LESSONS)
        val analyst = script(style = NarrationStyle.ANALYST)
        for (seg in analyst.segments) {
            if (seg.kind in viewerFacing) continue
            assertTrue(
                "analyst segment ${seg.index} uses second person: ${seg.narration}",
                !Regex("\\byou\\b|\\byour\\b", RegexOption.IGNORE_CASE).containsMatchIn(seg.narration)
            )
        }
    }

    @Test
    fun `an unidentified user is narrated neutrally by colour`() {
        val s = VideoScriptGenerator(null).generate(report, game, NarrationOptions())
        assertEquals(null, s.userColor)
        for (seg in s.segments) {
            assertTrue(
                "segment ${seg.index} addressed a user that does not exist: ${seg.narration}",
                !Regex("\\byou\\b|\\byour\\b", RegexOption.IGNORE_CASE).containsMatchIn(seg.narration) ||
                    seg.kind == SegmentKind.OUTRO_LESSONS || seg.kind == SegmentKind.PUZZLE_PROMPT
            )
            assertTrue(seg.narration.isNotBlank())
        }
        assertTrue(s.segments.any { it.narration.contains("White") || it.narration.contains("Black") })
    }

    // -----------------------------------------------------------------------
    // Lessons
    // -----------------------------------------------------------------------

    @Test
    fun `outro lessons are built from what actually happened in this game`() {
        val s = script()
        val lessons = s.segments.filter { it.kind == SegmentKind.OUTRO_LESSONS }
        assertTrue("expected 2 to 4 lessons, got ${lessons.size}", lessons.size in 2..4)

        val text = lessons.joinToString(" ") { it.narration }
        // The fixture's missed motif is a fork; the lesson must name it rather than give
        // interchangeable advice.
        val missedTypes = report.white.tacticsMissed.map { it.type.displayName.lowercase() }.distinct()
        assertTrue("fixture has no missed tactics to learn from", missedTypes.isNotEmpty())
        assertTrue(
            "lessons never mention any motif that actually occurred ($missedTypes): $text",
            missedTypes.any { text.lowercase().contains(it) }
        )
        // And they cite a concrete count or move number from this game.
        assertTrue("lessons cite nothing concrete: $text", Regex("move \\d+|three|two|one").containsMatchIn(text.lowercase()))
    }

    @Test
    fun `lessons differ when the focus player differs`() {
        val asWhite = VideoScriptGenerator(Color.WHITE).generate(report, game, NarrationOptions())
            .segments.filter { it.kind == SegmentKind.OUTRO_LESSONS }.joinToString(" ") { it.narration }
        val asBlack = VideoScriptGenerator(Color.BLACK).generate(report, game, NarrationOptions())
            .segments.filter { it.kind == SegmentKind.OUTRO_LESSONS }.joinToString(" ") { it.narration }
        assertTrue("lessons should be player-specific", asWhite != asBlack)
    }

    // -----------------------------------------------------------------------
    // Side-panel data: header, eval bar, move numbers, accuracy
    // -----------------------------------------------------------------------

    @Test
    fun `header is populated from the real PGN tags`() {
        val h = script().header
        assertNotNull("header must be populated", h)
        h!!
        assertEquals("MorphyFan1857", h.whiteName)
        assertEquals("DukeAndCount", h.blackName)
        assertEquals(1487, h.whiteRating)
        assertEquals(1502, h.blackRating)
        assertEquals("1-0", h.result)
        assertEquals("Philidor Defense", h.openingName)
        assertEquals("C41", h.openingEco)
        assertEquals("2026-03-14", h.dateText)
    }

    @Test
    fun `header falls back gracefully when the tags are missing`() {
        val bare = game.copy(tags = emptyMap())
        val h = VideoScriptGenerator(Color.WHITE).generate(
            report.copy(white = report.white.copy(name = null), black = report.black.copy(name = null)),
            bare,
            NarrationOptions()
        ).header
        assertNotNull(h)
        assertEquals("White", h!!.whiteName)
        assertEquals("Black", h.blackName)
        assertEquals(null, h.whiteRating)
        assertEquals(null, h.blackRating)
        assertEquals(null, h.dateText)
    }

    @Test
    fun `accuracy and rating come straight off the report`() {
        val s = script()
        assertEquals(report.white.accuracy, s.whiteAccuracy)
        assertEquals(report.black.accuracy, s.blackAccuracy)
        assertEquals(report.white.estimatedRating, s.whiteEstimatedRating)
        assertEquals(report.black.estimatedRating, s.blackEstimatedRating)
    }

    /** Every segment that puts a real position on screen has to feed the eval bar. */
    private val positionBearing = setOf(
        SegmentKind.NORMAL_MOVE, SegmentKind.KEY_MOMENT, SegmentKind.BLUNDER,
        SegmentKind.MISSED_TACTIC, SegmentKind.FOUND_TACTIC, SegmentKind.THREAT_ALLOWED,
        SegmentKind.TURNING_POINT, SegmentKind.PUZZLE_PROMPT
    )

    @Test
    fun `every position-bearing segment carries an eval and a move number`() {
        for (depth in NarrationDepth.values()) {
            for (seg in script(depth).segments) {
                if (seg.kind !in positionBearing) continue
                assertNotNull("segment ${seg.index} (${seg.kind}) has no eval", seg.eval)
                assertNotNull("segment ${seg.index} (${seg.kind}) has no move number", seg.moveNumber)
                assertTrue(
                    "segment ${seg.index} win percent out of range: ${seg.eval!!.winPercentWhite}",
                    seg.eval!!.winPercentWhite in 0.0..100.0
                )
                seg.ply?.let { assertEquals(report.annotations[it - 1].moveNumber, seg.moveNumber) }
            }
        }
    }

    @Test
    fun `cards and the intro carry no eval`() {
        val s = script()
        for (seg in s.segments) {
            if (seg.kind == SegmentKind.INTRO || seg.kind == SegmentKind.OUTRO_SUMMARY ||
                seg.kind == SegmentKind.OUTRO_LESSONS
            ) {
                assertEquals("segment ${seg.index} (${seg.kind}) should have no eval", null, seg.eval)
            }
        }
    }

    /**
     * The sign trap. The eval bar is White-relative, but [MoveAnnotation.winPercentAfter] is
     * *mover*-relative, so copying it straight across makes the bar flip on every Black move.
     *
     * The fixture's synthetic evals are pure material counts, which makes two adjacent plies an
     * exact test of the perspective:
     *   - after 4... Bxf3 Black has won a knight for a pawn, so White is worse
     *   - after 5. Qxf3 White has taken the bishop back, so White is better
     * If the perspective is flipped, exactly one of these two assertions fails.
     */
    @Test
    fun `eval is white-relative, not mover-relative`() {
        val s = script(NarrationDepth.EVERY_MOVE)
        val afterBlackWinsAPiece = s.segments.first { it.ply == 8 && it.kind in positionBearing && it.board is BoardDirective.PlayMove }
        val afterWhiteTakesBack = s.segments.first { it.ply == 9 && it.kind in positionBearing && it.board is BoardDirective.PlayMove }

        assertEquals("4... Bxf3", "4... " + report.annotations[7].san)
        assertEquals("5. Qxf3", "5. " + report.annotations[8].san)

        val blackFavoured = afterBlackWinsAPiece.eval!!
        assertTrue(
            "after Black wins a piece White should be under 50%, got ${blackFavoured.winPercentWhite}",
            blackFavoured.winPercentWhite < 50.0
        )
        assertTrue(
            "after Black wins a piece the White-relative eval should be negative, got ${blackFavoured.evalCp}",
            blackFavoured.evalCp!! < 0
        )

        val whiteFavoured = afterWhiteTakesBack.eval!!
        assertTrue(
            "after White recaptures White should be over 50%, got ${whiteFavoured.winPercentWhite}",
            whiteFavoured.winPercentWhite > 50.0
        )
        assertTrue(
            "after White recaptures the White-relative eval should be positive, got ${whiteFavoured.evalCp}",
            whiteFavoured.evalCp!! > 0
        )

        // And the Black move's value is genuinely flipped, not the mover-relative number copied.
        val blackAnnotation = report.annotations[7]
        assertEquals(Color.BLACK, blackAnnotation.color)
        assertEquals(
            100.0 - blackAnnotation.winPercentAfter,
            blackFavoured.winPercentWhite,
            0.01
        )
    }

    @Test
    fun `eval tracks the position the segment actually shows`() {
        val s = script(NarrationDepth.EVERY_MOVE)
        for (seg in s.segments) {
            val ply = seg.ply ?: continue
            // An excursion shows positions that never occurred in the game, so it is scored by
            // the position it *departs from* — see VideoScriptGenerator.excursionEval, and
            // `every excursion ply plays exactly one move` for the rule that does apply.
            if (seg.kind == SegmentKind.MISSED_TACTIC) continue
            val a = report.annotations[ply - 1]
            val eval = seg.eval ?: continue
            when (seg.board) {
                // A played move shows the position AFTER it.
                is BoardDirective.PlayMove -> {
                    assertEquals("segment ${seg.index}", a.evalAfterCp, eval.evalCp)
                    assertEquals("segment ${seg.index}", report.evalGraph[ply], eval.winPercentWhite, 0.001)
                }
                // Everything else freezes the position BEFORE the move and points at it.
                is BoardDirective.Annotate, is BoardDirective.PlayLine -> {
                    assertEquals("segment ${seg.index}", a.evalBeforeCp, eval.evalCp)
                    assertEquals("segment ${seg.index}", report.evalGraph[ply - 1], eval.winPercentWhite, 0.001)
                }
                else -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------
    // The missed-tactic excursion
    // -----------------------------------------------------------------------

    /**
     * One missed-tactic detour, as the generator lays it out: a run of consecutive
     * MISSED_TACTIC segments (pivot-in, one per ply of the line, payoff), then the KEY_MOMENT
     * pivot-out, then the segment that shows the move actually played.
     */
    private class Excursion(
        val pivotIn: ScriptSegment,
        val plies: List<ScriptSegment>,
        val payoff: ScriptSegment,
        val pivotOut: ScriptSegment,
        val playedMove: ScriptSegment
    ) {
        val all: List<ScriptSegment> get() = listOf(pivotIn) + plies + listOf(payoff, pivotOut, playedMove)
    }

    private fun excursions(s: VideoScript): List<Excursion> {
        val out = ArrayList<Excursion>()
        var i = 0
        while (i < s.segments.size) {
            if (s.segments[i].kind != SegmentKind.MISSED_TACTIC) {
                i++
                continue
            }
            var end = i
            while (end + 1 < s.segments.size && s.segments[end + 1].kind == SegmentKind.MISSED_TACTIC) end++
            val body = s.segments.subList(i, end + 1)
            // pivot-in + at least one ply + payoff, and the two beats that bring us home.
            if (body.size >= 3 && end + 2 < s.segments.size) {
                out.add(
                    Excursion(
                        pivotIn = body.first(),
                        plies = body.subList(1, body.size - 1).toList(),
                        payoff = body.last(),
                        pivotOut = s.segments[end + 1],
                        playedMove = s.segments[end + 2]
                    )
                )
            }
            i = end + 1
        }
        return out
    }

    @Test
    fun `a missed tactic becomes a full narrated excursion, and comes back to the real move`() {
        val s = script()
        val found = excursions(s)
        assertTrue("expected at least one missed-tactic excursion", found.isNotEmpty())

        for (ex in found) {
            val a = report.annotations[ex.pivotIn.ply!! - 1]

            // 1. Pivot-in: the position freezes before the missed move, with the engine's move on it.
            val pivot = ex.pivotIn.board
            assertTrue("pivot-in must freeze a position, was ${pivot::class.simpleName}", pivot is BoardDirective.Annotate)
            pivot as BoardDirective.Annotate
            assertEquals("pivot-in shows the position before the move", a.fenBefore, pivot.fen)
            assertTrue(
                "pivot-in must arrow the move that should have been played",
                pivot.arrows.any { it.role == ArrowRole.BEST }
            )
            assertTrue(
                "pivot-in must announce the detour: ${ex.pivotIn.narration}",
                ex.pivotIn.narration.lowercase().let {
                    it.contains("rewind") || it.contains("hold") || it.contains("freeze") ||
                        it.contains("pause") || it.contains("set the game")
                }
            )

            // 2. At least one ply of the line, walked one move at a time.
            assertTrue("an excursion has to walk the line", ex.plies.isNotEmpty())

            // 3. Payoff, on the last position of the line rather than on a game position.
            assertTrue("payoff must sit on a position", ex.payoff.board is BoardDirective.Annotate)
            assertTrue("payoff must say what the line won: ${ex.payoff.narration}", ex.payoff.narration.isNotBlank())

            // 4. Pivot-out: back on the real board, pointing at the move about to be played.
            assertEquals(SegmentKind.KEY_MOMENT, ex.pivotOut.kind)
            val out = ex.pivotOut.board
            assertTrue("pivot-out must return to the real position", out is BoardDirective.Annotate)
            out as BoardDirective.Annotate
            assertEquals(a.fenBefore, out.fen)
            assertTrue(
                "pivot-out must say out loud that we are back in the game: ${ex.pivotOut.narration}",
                ex.pivotOut.narration.lowercase().let {
                    it.contains("real game") || it.contains("back to") || it.contains("returning to")
                }
            )

            // 5. And then the main line resumes with the move that was actually played.
            val board = ex.playedMove.board
            assertTrue("the move actually played must animate, was ${board::class.simpleName}", board is BoardDirective.PlayMove)
            board as BoardDirective.PlayMove
            assertEquals(a.uci, board.uci)
            assertEquals(a.san, board.san)
            assertEquals(a.fenBefore, board.fen)
            assertEquals(a.ply, ex.playedMove.ply)
            assertEquals(report.evalGraph[a.ply], ex.playedMove.eval!!.winPercentWhite, 0.001)

            // The whole thing is one contiguous, reachable block.
            ex.all.zipWithNext { x, y -> assertEquals("excursion is not contiguous", x.index + 1, y.index) }
            for (seg in ex.all) assertEquals(a.ply, seg.ply)
        }
    }

    @Test
    fun `every excursion ply plays exactly one move, with its own eval and move number`() {
        for (depth in NarrationDepth.values()) {
            val s = script(depth)
            assertTrue(
                "no segment should carry a whole PlayLine any more (depth $depth)",
                s.segments.none { it.board is BoardDirective.PlayLine }
            )
            val found = excursions(s)
            assertTrue("depth $depth produced no excursion", found.isNotEmpty())
            for (ex in found) {
                val a = report.annotations[ex.pivotIn.ply!! - 1]
                // The plies have to be a real, legal walk of the line from the frozen position.
                var pos = Position.fromFen(a.fenBefore)
                for (seg in ex.plies) {
                    val board = seg.board
                    assertTrue(
                        "excursion ply ${seg.index} must be a PlayMove, was ${board::class.simpleName}",
                        board is BoardDirective.PlayMove
                    )
                    board as BoardDirective.PlayMove
                    assertEquals("excursion ply ${seg.index} starts from the wrong position", pos.toFen(), board.fen)
                    val move = pos.parseUci(board.uci)
                    assertEquals("excursion ply ${seg.index} has the wrong SAN", pos.moveToSan(move), board.san)
                    pos = pos.makeMove(move)

                    assertNotNull("excursion ply ${seg.index} has no eval", seg.eval)
                    assertEquals("excursion ply ${seg.index} move number", a.moveNumber, seg.moveNumber)
                    assertTrue("excursion ply ${seg.index} is silent", seg.narration.isNotBlank())
                    assertTrue(seg.estimatedSpeechMs > 0)
                    // The line is the engine's own best play out of the frozen position, so in
                    // White-relative terms its evaluation is that position's evaluation.
                    assertEquals("excursion ply ${seg.index} eval", a.evalBeforeCp, seg.eval!!.evalCp)
                    assertEquals(report.evalGraph[a.ply - 1], seg.eval!!.winPercentWhite, 0.001)
                }
                for (seg in listOf(ex.pivotIn, ex.payoff, ex.pivotOut)) {
                    assertNotNull("segment ${seg.index} has no eval", seg.eval)
                    assertEquals(a.moveNumber, seg.moveNumber)
                }
            }
        }
    }

    @Test
    fun `the excursion of the opponent's missed tactic is narrated too`() {
        // The fixture hands Black a missed fork as well, so both sides get a detour.
        val blackMissed = report.annotations.filter { it.color == Color.BLACK && it.tacticsMissed.isNotEmpty() }
        assertTrue("fixture should give Black a missed tactic too", blackMissed.isNotEmpty())
        val s = script(NarrationDepth.EVERY_MOVE)
        val plies = excursions(s).map { it.pivotIn.ply }
        assertTrue(
            "the opponent's missed tactic never became an excursion",
            blackMissed.any { it.ply in plies }
        )
    }

    @Test
    fun `a long principal variation cannot produce an endless detour`() {
        // Round 13 pacing (ANALYSIS_SPEC 9.7): only a FULL moment walks the whole eight plies, and a
        // book move is never one (it is SKIP), so the first missed tactic in the file is no longer the
        // right subject. The highest-loss non-book missed tactic always ranks first for a FULL slot.
        val index = report.annotations
            .filter { a ->
                a.bestMoveUci != null && a.bestMoveUci != a.uci && a.tacticsMissed.any { it.confidence >= 0.6 } &&
                    a.classification != MoveClassification.BOOK
            }
            .maxByOrNull { it.loss }?.let { it.ply - 1 } ?: -1
        assertTrue("fixture has no missed tactic to lengthen", index >= 0)
        val a = report.annotations[index]
        val tactic = a.tacticsMissed.maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))!!

        // 24 plies of legal, quiet moves out of the frozen position. No captures, so the payoff
        // rule can never truncate the line and the only thing left to stop it is the cap.
        val pv = ArrayList<String>()
        val sans = ArrayList<String>()
        var pos = Position.fromFen(a.fenBefore)
        while (pv.size < 24) {
            val move = pos.legalMoves().firstOrNull { it.toUci() == a.bestMoveUci && !it.isCapture }
                ?: pos.legalMoves().firstOrNull { !it.isCapture && it.promotion == null }
                ?: break
            pv.add(move.toUci())
            sans.add(pos.moveToSan(move))
            pos = pos.makeMove(move)
        }
        assertTrue("need a long line to test the cap, got ${pv.size}", pv.size >= 16)

        val stretched = report.annotations.toMutableList()
        stretched[index] = a.copy(
            simulation = TacticSimulation(
                startFen = a.fenBefore,
                pvUci = pv,
                pvSan = sans,
                perPlyExplanation = sans.map { "$it: on-screen text" },
                tactic = tactic,
                payoffDescription = "wins a rook"
            )
        )
        val s = VideoScriptGenerator(Color.WHITE).generate(report.copy(annotations = stretched), game, NarrationOptions())
        val ex = excursions(s).firstOrNull { it.pivotIn.ply == a.ply }
        assertNotNull("the stretched missed tactic produced no excursion", ex)
        assertEquals("a 24-ply PV must be capped at 8 plies", 8, ex!!.plies.size)
        // And a detour stays a detour. The cap is what holds the worst case — a full eight-ply
        // line, every ply explained — under the two-minute mark; the fixture's real excursions
        // run three plies and about half a minute.
        val spoken = ex.all.sumOf { it.estimatedSpeechMs }
        assertTrue("worst-case excursion ran to ${spoken}ms", spoken < 120_000)
        println("capped excursion: ${ex.plies.size} plies, ${ex.all.size} segments, ${spoken / 1000}s spoken")
    }

    @Test
    fun `depths still order correctly with excursions in the script`() {
        val counts = LinkedHashMap<NarrationDepth, Triple<Int, Long, Int>>()
        for (depth in NarrationDepth.values()) {
            val s = script(depth)
            counts[depth] = Triple(s.segments.size, s.totalEstimatedMs, excursions(s).size)
            assertTrue("depth $depth lost its excursions", excursions(s).isNotEmpty())
            s.segments.forEachIndexed { i, seg -> assertEquals(i, seg.index) }
        }
        val mistakes = counts[NarrationDepth.MISTAKES_ONLY]!!
        val highlights = counts[NarrationDepth.HIGHLIGHTS]!!
        val everyMove = counts[NarrationDepth.EVERY_MOVE]!!
        assertTrue("MISTAKES_ONLY ${mistakes.first} vs HIGHLIGHTS ${highlights.first}", mistakes.first < highlights.first)
        assertTrue("HIGHLIGHTS ${highlights.first} vs EVERY_MOVE ${everyMove.first}", highlights.first < everyMove.first)
        assertTrue(mistakes.second < highlights.second)
        assertTrue(highlights.second < everyMove.second)

        val table = StringBuilder("\nSEGMENT COUNTS WITH EXCURSIONS\n")
        for ((depth, v) in counts) {
            table.appendLine(
                "%-14s %3d segments, %4ds estimated, %d excursions".format(depth.name, v.first, v.second / 1000, v.third)
            )
        }
        println(table)
    }

    @Test
    fun `print one excursion verbatim for human review`() {
        val s = script()
        val ex = excursions(s).first()
        val out = StringBuilder()
        out.appendLine()
        out.appendLine("#".repeat(100))
        out.appendLine("EXCURSION at ply ${ex.pivotIn.ply} (segments ${ex.pivotIn.index}..${ex.playedMove.index})")
        out.appendLine("#".repeat(100))
        for (seg in ex.all) {
            out.appendLine()
            out.appendLine("[${seg.index}] ${seg.kind} ply ${seg.ply} (${seg.estimatedSpeechMs}ms" +
                (if (seg.holdAfterMs > 0) " + ${seg.holdAfterMs}ms hold" else "") + ")")
            out.appendLine("  BOARD:   ${describe(seg.board)}")
            seg.eval?.let { out.appendLine("  EVAL:    white ${"%.1f".format(it.winPercentWhite)}% / ${it.evalCp}cp") }
            out.appendLine("  CAPTION: ${seg.caption}")
            out.appendLine("  SPOKEN:  ${seg.narration}")
        }
        out.appendLine("#".repeat(100))
        println(out)
        assertTrue(ex.plies.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Human-readable output
    // -----------------------------------------------------------------------

    @Test
    fun `print the full generated script for human review`() {
        val s = script()
        val out = StringBuilder()
        out.appendLine()
        out.appendLine("=".repeat(100))
        out.appendLine("TITLE:    ${s.title}")
        out.appendLine("SUBTITLE: ${s.subtitle}")
        out.appendLine("LENGTH:   ${s.totalEstimatedMs / 1000}s over ${s.segments.size} segments")
        out.appendLine("CHAPTERS: " + s.chapters.joinToString(" | ") { "${it.startSegmentIndex}:${it.title}" })
        s.header?.let {
            out.appendLine("HEADER:   ${it.whiteName} (${it.whiteRating}) vs ${it.blackName} (${it.blackRating}) " +
                "- ${it.result} - ${it.openingName} ${it.openingEco} - ${it.dateText}")
        }
        out.appendLine("ACCURACY: White ${s.whiteAccuracy}% (${s.whiteEstimatedRating}) / " +
            "Black ${s.blackAccuracy}% (${s.blackEstimatedRating})")
        out.appendLine("=".repeat(100))
        for (seg in s.segments) {
            out.appendLine()
            out.appendLine("[${seg.index}] ${seg.kind}${seg.ply?.let { " ply $it" } ?: ""}  " +
                "(${seg.estimatedSpeechMs}ms${if (seg.holdAfterMs > 0) " + ${seg.holdAfterMs}ms hold" else ""})")
            out.appendLine("  BOARD:   ${describe(seg.board)}")
            seg.eval?.let {
                out.appendLine("  EVAL:    white ${"%.1f".format(it.winPercentWhite)}% / ${it.evalCp}cp" +
                    (it.mateIn?.let { m -> " / mate $m" } ?: ""))
            }
            out.appendLine("  CAPTION: ${seg.caption}")
            out.appendLine("  SPOKEN:  ${seg.narration}")
        }
        out.appendLine()
        out.appendLine("-".repeat(100))
        for (depth in NarrationDepth.values()) {
            val d = script(depth)
            out.appendLine(
                "%-14s %2d segments, %3ds estimated (%d chapters)".format(
                    depth.name, d.segments.size, d.totalEstimatedMs / 1000, d.chapters.size
                )
            )
        }
        out.appendLine("=".repeat(100))
        println(out)
        assertNotNull(s)
    }

    private fun describe(board: BoardDirective): String = when (board) {
        is BoardDirective.Hold -> "Hold"
        is BoardDirective.PlayMove -> "PlayMove ${board.san} (${board.classification?.displayName})"
        is BoardDirective.PlayLine -> "PlayLine ${board.sanMoves.joinToString(" ")} [${board.label}]"
        is BoardDirective.Annotate -> "Annotate arrows=${board.arrows.map { "${it.fromSquare}-${it.toSquare}/${it.role}" }}"
        is BoardDirective.Card -> "Card '${board.heading}'"
    }
}
