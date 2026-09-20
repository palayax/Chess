package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.ArrowRole
import net.palaya.chessanalyzer.core.narration.ArrowSpec
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptChapter
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentEval
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoGameHeader
import net.palaya.chessanalyzer.core.narration.VideoScript

/**
 * Hand-built [VideoScript] fixtures shared by the video instrumented tests. Lifted verbatim out of
 * [VideoExporterInstrumentedTest] (which still uses [syntheticScript] unchanged) so
 * [VideoExportServiceInstrumentedTest] can reuse the same shapes instead of growing a second,
 * subtly-different copy.
 */
internal object TestScripts {

    val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val afterE4E5Fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"

    /** A short, hand-built script exercising every [BoardDirective] kind the renderer supports. */
    fun syntheticScript(): VideoScript {
        val segments = listOf(
            ScriptSegment(
                index = 0,
                kind = SegmentKind.INTRO,
                ply = null,
                narration = "Let's review this short test game.",
                caption = "Test review",
                board = BoardDirective.Card(
                    heading = "Chess Analyzer — Test Review",
                    lines = listOf("A short synthetic script for the export pipeline."),
                ),
                estimatedSpeechMs = 1200,
            ),
            ScriptSegment(
                index = 1,
                kind = SegmentKind.OPENING_SUMMARY,
                ply = 1,
                moveNumber = 1,
                narration = "White opens with the king's pawn.",
                caption = "1. e4",
                board = BoardDirective.Hold(startFen),
                estimatedSpeechMs = 1200,
                // Roughly balanced — a good frame to contrast against the clearly Black-favoured
                // one below, per the done-condition ("include at least one frame where the
                // evaluation favours Black, so the direction is actually tested").
                eval = SegmentEval(winPercentWhite = 50.0, evalCp = 0),
            ),
            ScriptSegment(
                index = 2,
                kind = SegmentKind.NORMAL_MOVE,
                ply = 1,
                moveNumber = 1,
                narration = "White pushes the pawn two squares to e4.",
                caption = "1. e4",
                board = BoardDirective.PlayMove(fen = startFen, uci = "e2e4", san = "e4"),
                estimatedSpeechMs = 1200,
                speakerColor = Color.WHITE,
                eval = SegmentEval(winPercentWhite = 54.0, evalCp = 25),
            ),
            ScriptSegment(
                index = 3,
                kind = SegmentKind.KEY_MOMENT,
                ply = 2,
                moveNumber = 1,
                narration = "Developing the knight to f3 is the natural follow-up here.",
                caption = "Idea: Nf3",
                board = BoardDirective.Annotate(
                    fen = afterE4E5Fen,
                    arrows = listOf(ArrowSpec("g1", "f3", ArrowRole.BEST)),
                    highlightSquares = listOf("f3"),
                ),
                estimatedSpeechMs = 1500,
                speakerColor = Color.BLACK,
                eval = SegmentEval(winPercentWhite = 52.0, evalCp = 15),
            ),
            ScriptSegment(
                index = 4,
                kind = SegmentKind.MISSED_TACTIC,
                ply = 2,
                moveNumber = 1,
                narration = "Here is the Ruy Lopez setup worth aiming for.",
                caption = "Line: Nf3 Nc6 Bb5",
                board = BoardDirective.PlayLine(
                    fen = afterE4E5Fen,
                    uciMoves = listOf("g1f3", "b8c6", "f1b5"),
                    sanMoves = listOf("Nf3", "Nc6", "Bb5"),
                    label = "What you could aim for",
                ),
                estimatedSpeechMs = 1800,
                holdAfterMs = 400,
                speakerColor = Color.BLACK,
                // Deliberately, clearly Black-favoured — this is the frame that proves the eval
                // bar's direction is correct rather than assumed: it must render MOSTLY BLACK.
                eval = SegmentEval(winPercentWhite = 18.0, evalCp = -320),
            ),
            ScriptSegment(
                index = 5,
                kind = SegmentKind.OUTRO_LESSONS,
                ply = null,
                narration = "Develop your pieces quickly and fight for the center.",
                caption = "Lessons",
                board = BoardDirective.Card(
                    heading = "Lessons",
                    lines = listOf("Develop knights before bishops.", "Control the center early."),
                ),
                estimatedSpeechMs = 1500,
            ),
        )
        val totalEstimated = segments.sumOf { it.estimatedSpeechMs + it.holdAfterMs }
        val chapters = listOf(
            ScriptChapter("Intro", 0),
            ScriptChapter("Opening", 1),
            ScriptChapter("Key moment", 3),
            ScriptChapter("Lessons", 5),
        )
        return VideoScript(
            title = "Test Review",
            subtitle = "Synthetic export test",
            segments = segments,
            chapters = chapters,
            totalEstimatedMs = totalEstimated,
            userColor = Color.WHITE,
            header = VideoGameHeader(
                whiteName = "TestWhite",
                blackName = "TestBlack",
                whiteRating = 1500,
                blackRating = 1450,
                result = "1-0",
                openingName = "King's Pawn Opening",
                openingEco = "C20",
                dateText = "2026.09.17",
            ),
            whiteAccuracy = 91.4,
            blackAccuracy = 78.2,
            whiteEstimatedRating = 1620,
            blackEstimatedRating = 1400,
        )
    }

    /**
     * A deliberately **truncated** two-segment script — roughly 2 s of video instead of the ~9 s
     * of [syntheticScript] (let alone the ~9 minutes of a real game review, which takes ~23 min of
     * wall clock to render on this emulator). The service tests are about *who owns the coroutine*
     * and *whether cancel works*, not about encoder fidelity — that is [VideoExporterInstrumentedTest]'s
     * job — so the shortest script that still exercises the full narration -> encode -> mux ->
     * publish path is the right fixture, and it keeps the suite usable.
     */
    fun shortScript(): VideoScript {
        val segments = listOf(
            ScriptSegment(
                index = 0,
                kind = SegmentKind.INTRO,
                ply = null,
                narration = "Short export test.",
                caption = "Short export test",
                board = BoardDirective.Card(
                    heading = "Palaya Chess",
                    lines = listOf("Foreground-service export test."),
                ),
                estimatedSpeechMs = 800,
            ),
            ScriptSegment(
                index = 1,
                kind = SegmentKind.NORMAL_MOVE,
                ply = 1,
                moveNumber = 1,
                narration = "White plays e4.",
                caption = "1. e4",
                board = BoardDirective.PlayMove(fen = startFen, uci = "e2e4", san = "e4"),
                estimatedSpeechMs = 800,
                speakerColor = Color.WHITE,
                eval = SegmentEval(winPercentWhite = 54.0, evalCp = 25),
            ),
        )
        return VideoScript(
            title = "Short Test Review",
            subtitle = "Foreground-service export test",
            segments = segments,
            chapters = listOf(ScriptChapter("Intro", 0)),
            totalEstimatedMs = segments.sumOf { it.estimatedSpeechMs + it.holdAfterMs },
            userColor = Color.WHITE,
            header = VideoGameHeader(
                whiteName = "TestWhite",
                blackName = "TestBlack",
                whiteRating = 1500,
                blackRating = 1450,
                result = "1-0",
                openingName = "King's Pawn Opening",
                openingEco = "C20",
                dateText = "2026.09.20",
            ),
            whiteAccuracy = 91.4,
            blackAccuracy = 78.2,
            whiteEstimatedRating = 1620,
            blackEstimatedRating = 1400,
        )
    }
}
