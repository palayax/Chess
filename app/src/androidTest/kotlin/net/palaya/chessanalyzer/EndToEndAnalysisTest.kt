package net.palaya.chessanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.ui.model.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real user journey, end to end, on a device: a chess.com-format PGN goes in, Stockfish
 * actually analyses every ply, and a complete [net.palaya.chessanalyzer.core.analysis.GameReport]
 * comes out.
 *
 * This is deliberately headless rather than UI-driven. The value here is proving that parsing,
 * the engine loop, classification, tactics and the report all work together against a real game;
 * driving the same thing through the SAF picker would test Android's file picker, not this app.
 *
 * Nothing is skipped: the Stockfish net and the Kokoro voice are installed from the test APK's seed
 * assets ([TestApp.ensureSetUp], the same store tails the first-run download uses; seeded from D2d on).
 * If they were missing or damaged this test fails.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndAnalysisTest {

    /** The Opera Game in chess.com's export format, clocks and all — ends in checkmate. */
    private val pgn = """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [Date "2026.03.14"]
        [White "MorphyFan1857"]
        [Black "DukeAndCount"]
        [Result "1-0"]
        [ECO "C41"]
        [WhiteElo "1487"]
        [BlackElo "1502"]
        [TimeControl "600"]
        [Termination "MorphyFan1857 won by checkmate"]

        1. e4 {[%clk 0:09:58.3]} 1... e5 {[%clk 0:09:57.1]} 2. Nf3 {[%clk 0:09:55.9]} 2... d6
        3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5
        10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7
        16. Qb8+ Nxb8 17. Rd8# 1-0
    """.trimIndent()

    @Test
    fun analysesARealChessComGameEndToEnd() = runBlocking {
        val app = TestApp.app
        val service = TestApp.analysisService()

        TestApp.ensureSetUp()
        var lastMove = 0
        var sawAnalyzingPhase = false

        val started = System.currentTimeMillis()
        val outcome = service.analyze(
            pgnText = pgn,
            username = "MorphyFan1857",
            // Depth 12 keeps the emulator run tractable; the logic under test is depth-independent.
            settings = EngineSettings(depth = 12, multiPv = 3, username = "MorphyFan1857"),
            onProgress = { p ->
                if (p.currentMoveIndex > 0) { lastMove = p.currentMoveIndex; sawAnalyzingPhase = true }
            },
        )
        val elapsedMs = System.currentTimeMillis() - started

        assertTrue("the net must be installed under filesDir/nets", app.engineController.isNetPresent())
        assertTrue("the voice must be installed", app.voiceStore.isInstalled())
        assertTrue(
            "analysis did not succeed: $outcome",
            outcome is AnalysisService.Outcome.Success
        )
        val success = outcome as AnalysisService.Outcome.Success
        val report = success.report

        android.util.Log.i(
            "E2E",
            "depth=12 plies=${report.annotations.size} elapsedMs=$elapsedMs " +
                "elapsedSec=${"%.1f".format(elapsedMs / 1000.0)} " +
                "whiteAcc=${report.white.accuracy} blackAcc=${report.black.accuracy} " +
                "whiteElo=${report.white.estimatedRating} blackElo=${report.black.estimatedRating} " +
                "opening=${report.openingName} eco=${report.openingEco}"
        )

        // The game is 33 plies; every one must be annotated.
        assertEquals("expected one annotation per ply", 33, report.annotations.size)
        assertTrue("progress must have reported the analysing phase", sawAnalyzingPhase)
        assertTrue("progress should have reached the last ply", lastMove >= 33)

        // The user was White and won by mate.
        assertEquals(Color.WHITE, success.userColor)
        assertEquals("1-0", report.result)

        // Accuracy and rating must be real, in-range numbers — not defaults.
        assertTrue("white accuracy out of range: ${report.white.accuracy}", report.white.accuracy in 0.0..100.0)
        assertTrue("black accuracy out of range: ${report.black.accuracy}", report.black.accuracy in 0.0..100.0)
        assertTrue("white rating out of range", report.white.estimatedRating in 100..3000)

        // The opening must have been identified from the bundled book.
        assertNotNull("opening should be identified", report.openingName)

        // Every annotation must carry a real evaluation and a classification.
        report.annotations.forEach { a ->
            assertTrue("empty san at ply ${a.ply}", a.san.isNotBlank())
            assertTrue("loss must be non-negative at ply ${a.ply}", a.loss >= 0.0)
            assertTrue("commentary must not be empty at ply ${a.ply}", a.text.isNotBlank())
        }

        // The eval graph drives the report chart.
        assertTrue("eval graph should have a point per ply", report.evalGraph.size >= report.annotations.size)

        // Black walked into mate: somewhere in this game there must be a non-'good' move,
        // otherwise the classifier is not actually discriminating.
        val anyMistake = report.annotations.any { it.classification.isMistake }
        assertTrue("expected at least one inaccuracy/mistake/blunder in this game", anyMistake)

        // And at least one move should be recognised as strong.
        val anyGood = report.annotations.any {
            it.classification == MoveClassification.BEST ||
                it.classification == MoveClassification.BRILLIANT ||
                it.classification == MoveClassification.GREAT
        }
        assertTrue("expected at least one best/great/brilliant move", anyGood)

        // Tactics: this game famously ends with a deflection and a back-rank mate, so the
        // detector must have found *something* across the game.
        val totalTactics = report.annotations.sumOf { it.tacticsFound.size + it.tacticsMissed.size }
        android.util.Log.i("E2E", "tacticsFoundOrMissed=$totalTactics")
        assertTrue("expected the tactics detector to fire somewhere in this game", totalTactics > 0)
    }
}
