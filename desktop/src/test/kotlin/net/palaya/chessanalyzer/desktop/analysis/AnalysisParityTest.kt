package net.palaya.chessanalyzer.desktop.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.desktop.TestEnv
import net.palaya.chessanalyzer.desktop.engine.EngineSettings
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * `fixtures/immortal.pgn` through the whole ANALYZE stage (real Stockfish, depth 14, MultiPV 3),
 * checked against the committed regression file `desktop/src/test/resources/immortal_depth14.json`
 * (design §13). Re-record deliberately, when Stockfish or `:core` classification changes, with
 * `./gradlew :desktop:test -Ppalaya.record=true`.
 *
 * **Threads 1** here, not 4: Stockfish's multi-threaded (Lazy SMP) search is not reproducible at a
 * fixed depth, and classification sits on eval thresholds, so a 4-thread run can flip a label
 * between runs. A single-threaded fixed-depth search from `ucinewgame` is deterministic.
 */
class AnalysisParityTest {

    @Serializable
    data class Regression(
        val schema: String = "palaya.regression.classifications/1",
        val fixture: String,
        val engine: String,
        val depth: Int,
        val multiPv: Int,
        val threads: Int,
        val white: Map<String, Int>,
        val black: Map<String, Int>,
        val perPly: List<String>,
    )

    @Test
    fun immortalClassificationsMatchTheRecordedRegression() {
        val pgnPath = TestEnv.fixture("immortal.pgn")
        val pgnText = Files.readString(pgnPath)
        val game = PgnParser.parse(pgnText).first()
        val tmp = Files.createTempDirectory("palaya-parity")
        val settings = EngineSettings(depth = DEPTH, multiPv = MULTIPV, movetimeCapMs = null, threads = THREADS, hashMb = TestEnv.HASH_MB)
        val t0 = System.nanoTime()
        val outcome = AnalysisStage(TestEnv.stockfish, settings).run(
            workDir = WorkDir(tmp, WorkDir.gameId(game)),
            game = game,
            gameIndex = 1,
            pgnText = pgnText,
            userName = null,
            thresholdCp = 50,
        )
        println(String.format("immortal depth %d: %.1f s", DEPTH, (System.nanoTime() - t0) / 1e9))
        assertTrue(outcome.ranEngine)
        assertEquals(game.moves.size + 1, outcome.evals.size)
        outcome.evals.dropLast(1).forEach { assertTrue("every searched position reaches depth $DEPTH", it.depth >= DEPTH) }

        // The artifacts read back from disk, not the in-memory objects.
        val file = AnalysisStage.readAnalysis(outcome.workDir)!!
        assertTrue(file.checkpoint.complete)
        assertEquals(outcome.evals, file.evals.map { it.toPositionEval() })
        val report = WorkJson.decodeFromString(ReportDto.serializer(), Files.readString(outcome.workDir.reportJson))
        assertEquals("checkmate", report.termination)
        assertTrue("23.Be7# flagged as checkmate", report.plies.last().checkmate)

        val actual = Regression(
            fixture = "fixtures/immortal.pgn",
            engine = file.engine.name,
            depth = DEPTH,
            multiPv = MULTIPV,
            threads = THREADS,
            white = report.white.classificationCounts,
            black = report.black.classificationCounts,
            perPly = report.plies.map { "${it.label} ${it.classification}" },
        )
        val regressionFile = TestEnv.repoRoot.resolve("desktop/src/test/resources/immortal_depth14.json")
        if (TestEnv.record) {
            Files.writeString(regressionFile, WorkJson.encodeToString(actual) + "\n")
            println("RECORDED $regressionFile")
        }
        if (!Files.isRegularFile(regressionFile)) {
            throw AssertionError("regression file missing: $regressionFile (record it once with -Ppalaya.record=true)")
        }
        val expected = WorkJson.decodeFromString(Regression.serializer(), Files.readString(regressionFile))
        println("white ${actual.white}\nblack ${actual.black}")
        val diffs = expected.perPly.zip(actual.perPly).filter { (e, a) -> e != a }
        assertEquals("per-ply classifications differ: $diffs", expected.perPly, actual.perPly)
        assertEquals(expected.white, actual.white)
        assertEquals(expected.black, actual.black)
        assertEquals(expected.engine, actual.engine)
    }

    companion object {
        const val DEPTH = 14
        const val MULTIPV = 3
        const val THREADS = 1
    }
}
