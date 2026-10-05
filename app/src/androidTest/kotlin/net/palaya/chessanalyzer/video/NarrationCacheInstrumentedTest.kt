package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PieceValues
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.analysis.SeeEvaluator
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.analysis.TacticsDetector
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the work-avoidance claims behind "pre-calculate the narration" end to end, through the
 * REAL [VideoScriptGenerator] output for two different games (not a hand-built synthetic script):
 *
 *  1. Re-preparing the same script a second time costs nothing — every segment is a whole-segment
 *     cache hit and the provider is never called.
 *  2. Narrating a SECOND, different game reuses some of the first game's boilerplate sentences
 *     (intro/outro scaffolding, puzzle-prompt phrasing, per-ply reason clauses) — the actual point
 *     of caching per SENTENCE rather than per segment. The measured hit rate is logged and
 *     asserted to be non-zero; see the class returning this work's notes for the honest number.
 *  3. The store is genuinely unaffected by clearing `cacheDir` — it lives in `filesDir`.
 *
 * A [FakeInstantVoiceProvider] stands in for the real voice: instant, deterministic, and it counts
 * its own calls, so "zero synthesis calls" is asserted directly rather than inferred from timing.
 * What is under test here is the CACHE, not any one provider — a real one would only make this
 * slower and less deterministic without proving anything extra about the hit rates.
 */
@RunWith(AndroidJUnit4::class)
class NarrationCacheInstrumentedTest {

    @Test
    fun reprepareSameScriptIsFullyCachedWithZeroSynthesisCalls(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = TwoGameNarrationFixture.script(context, TwoGameNarrationFixture.CHESSCOM_PGN, TwoGameNarrationFixture.CHESSCOM_OVERRIDES)
        val store = NarrationStore.forDirectory(freshDir(context, "reprepare_store"))
        val outputDir = freshDir(context, "reprepare_scratch")
        val spokenSegments = script.segments.count { it.narration.isNotBlank() }

        try {
            val firstPass = NarrationCoordinator(FakeInstantVoiceProvider(), DeviceTtsProvider(context), store)
                .synthesizeAll(script, outputDir)
            assertEquals("first pass should serve every spoken segment via the primary provider", spokenSegments, firstPass.primaryUsedCount)

            val secondProvider = FakeInstantVoiceProvider()
            val secondPass = NarrationCoordinator(secondProvider, DeviceTtsProvider(context), store)
                .synthesizeAll(script, outputDir)

            android.util.Log.i(
                "NarrationCacheTest",
                "reprepare: primaryUsedCount=${secondPass.primaryUsedCount}/$spokenSegments " +
                    "cacheHits=${secondPass.cacheHits} sentenceCacheHits=${secondPass.sentenceCacheHits} " +
                    "sentenceMisses=${secondPass.sentenceSynthesisCalls} providerCalls=${secondProvider.synthesizeCallCount.get()}",
            )

            assertEquals("re-preparing must still serve every segment", spokenSegments, secondPass.primaryUsedCount)
            assertEquals("re-preparing the same script must never call the provider", 0, secondProvider.synthesizeCallCount.get())
            assertEquals("every segment should be a whole-segment cache hit the second time", spokenSegments, secondPass.cacheHits)
            assertEquals("no sentence-level provider calls on the second pass", 0, secondPass.sentenceSynthesisCalls)
        } finally {
            store.dir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun crossGameSentenceReuseHasANonZeroHitRate(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scriptA = TwoGameNarrationFixture.script(context, TwoGameNarrationFixture.CHESSCOM_PGN, TwoGameNarrationFixture.CHESSCOM_OVERRIDES)
        val scriptB = TwoGameNarrationFixture.script(context, TwoGameNarrationFixture.IMMORTAL_PGN, TwoGameNarrationFixture.IMMORTAL_OVERRIDES)
        val store = NarrationStore.forDirectory(freshDir(context, "crossgame_store"))
        val outputDir = freshDir(context, "crossgame_scratch")

        try {
            val outcomeA = NarrationCoordinator(FakeInstantVoiceProvider(), DeviceTtsProvider(context), store)
                .synthesizeAll(scriptA, outputDir)
            val outcomeB = NarrationCoordinator(FakeInstantVoiceProvider(), DeviceTtsProvider(context), store)
                .synthesizeAll(scriptB, outputDir)

            val attemptedB = outcomeB.sentenceCacheHits + outcomeB.sentenceSynthesisCalls
            val hitRatePercent = outcomeB.sentenceCacheHitRatePercent ?: 0.0

            android.util.Log.i(
                "NarrationCacheTest",
                "cross-game reuse — game A: sentenceHits=${outcomeA.sentenceCacheHits} " +
                    "sentenceMisses=${outcomeA.sentenceSynthesisCalls} segmentCacheHits=${outcomeA.cacheHits}; " +
                    "game B: sentenceHits=${outcomeB.sentenceCacheHits} sentenceMisses=${outcomeB.sentenceSynthesisCalls} " +
                    "segmentCacheHits=${outcomeB.cacheHits} sentencesAttempted=$attemptedB " +
                    "hitRate=${"%.1f".format(hitRatePercent)}%",
            )

            assertTrue(
                "game B should attempt at least one sentence through the sentence-cache path " +
                    "(i.e. have at least one segment whose whole text differs from game A's)",
                attemptedB > 0,
            )
            assertTrue(
                "game B's narration should reuse at least one sentence verbatim from game A's " +
                    "narration (shared scaffolding/template text) — measured hit rate was " +
                    "${"%.1f".format(hitRatePercent)}% ($outcomeB)",
                outcomeB.sentenceCacheHits > 0,
            )
        } finally {
            store.dir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun storeSurvivesASimulatedCacheDirClear(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = TwoGameNarrationFixture.script(context, TwoGameNarrationFixture.CHESSCOM_PGN, TwoGameNarrationFixture.CHESSCOM_OVERRIDES)
        val store = NarrationStore.forApp(context) // the REAL app store, under filesDir
        val outputDir = freshDir(context, "cachedir_survival_scratch")

        // Sanity: the store really is under filesDir, not cacheDir.
        assertTrue(
            "NarrationStore must live under filesDir, not cacheDir",
            store.dir.canonicalPath.startsWith(context.filesDir.canonicalPath),
        )

        try {
            val provider = FakeInstantVoiceProvider()
            val outcome = NarrationCoordinator(provider, DeviceTtsProvider(context), store)
                .synthesizeAll(script, outputDir)
            val sizeBeforeClear = store.totalSizeBytes()
            assertTrue("store should hold real bytes after preparing a script", sizeBeforeClear > 0)
            assertTrue("at least one segment should have been cached", outcome.primaryUsedCount > 0)

            // Simulate exactly the kind of storage-pressure eviction the OS is free to perform on
            // cacheDir at any time, with no warning to the app.
            context.cacheDir.deleteRecursively()

            val sizeAfterClear = store.totalSizeBytes()
            assertEquals(
                "clearing cacheDir must not touch anything in the persistent narration store",
                sizeBeforeClear,
                sizeAfterClear,
            )

            // And it's still genuinely usable — a fresh coordinator against the same store hits
            // the cache with zero new provider calls, exactly like the re-prepare test above.
            val providerAfterClear = FakeInstantVoiceProvider()
            val outcomeAfterClear = NarrationCoordinator(providerAfterClear, DeviceTtsProvider(context), store)
                .synthesizeAll(script, freshDir(context, "cachedir_survival_scratch_2"))
            assertEquals(
                "cached audio must still be usable after cacheDir was cleared",
                0,
                providerAfterClear.synthesizeCallCount.get(),
            )
            assertEquals(
                "every segment should still be served after cacheDir was cleared",
                outcome.primaryUsedCount,
                outcomeAfterClear.primaryUsedCount,
            )
        } finally {
            store.clear()
        }
    }

    private fun freshDir(context: android.content.Context, name: String): File =
        File(context.filesDir, name).apply { deleteRecursively(); mkdirs() }
}

/**
 * Deterministic, instant stand-in for a real voice: no network, no real TTS engine, just a
 * silent WAV whose length depends on the text so results stay non-trivial — and a call counter, so
 * "the provider was never called" is a direct assertion rather than something inferred from timing.
 */
internal class FakeInstantVoiceProvider(
    override val displayName: String = "FakeInstantVoice",
) : NarrationVoiceProvider {
    val synthesizeCallCount = AtomicInteger(0)

    override suspend fun prepare(): Boolean = true

    override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
        synthesizeCallCount.incrementAndGet()
        val durationMs = 400L + text.length * 8L
        WavUtil.writeSilentWav(outFile, durationMs, 16000)
        return SynthesisResult.Success(outFile, durationMs)
    }

    override fun release() = Unit
}

/**
 * Builds a real [VideoScript] (via the actual [VideoScriptGenerator], not a hand-written stub) for
 * one of two different fixture games, using the same "no engine, synthetic evals with a couple of
 * deliberately-preferred alternates" technique as `core`'s own `NarrationFixture` test helper (that
 * one lives in `core/src/test`, a different module's test sourceset, and is therefore not
 * reachable from here — this reimplements the same small technique against `core`'s public
 * analysis API instead of duplicating `core` itself).
 */
internal object TwoGameNarrationFixture {

    data class Overrides(val whitePlies: List<Int>, val blackPlies: List<Int>)

    const val CHESSCOM_PGN = """[Event "Live Chess"]
[Site "Chess.com"]
[Date "2026.03.14"]
[Round "-"]
[White "MorphyFan1857"]
[Black "DukeAndCount"]
[Result "1-0"]

1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
"""

    const val IMMORTAL_PGN = """[Event "Casual Game"]
[Site "London ENG"]
[Date "1851.06.21"]
[Round "?"]
[White "Adolf Anderssen"]
[Black "Lionel Kieseritzky"]
[Result "1-0"]

1. e4 e5 2. f4 exf4 3. Bc4 Qh4+ 4. Kf1 b5 5. Bxb5 Nf6 6. Nf3 Qh6 7. d3 Nh5
8. Nh4 Qg5 9. Nf5 c6 10. g4 Nf6 11. Rg1 cxb5 12. h4 Qg6 13. h5 Qg5 14. Qf3 Ng8
15. Bxf4 Qf6 16. Nc3 Bc5 17. Nd5 Qxb2 18. Bd6 Bxg1 19. e5 Qxa1+ 20. Ke2 Na6
21. Nxg7+ Kd8 22. Qf6+ Nxf6 23. Be7# 1-0
"""

    /** Even (White) / odd (Black) ply indices, well inside each game's move count. */
    val CHESSCOM_OVERRIDES = Overrides(whitePlies = listOf(6, 14), blackPlies = listOf(17))
    val IMMORTAL_OVERRIDES = Overrides(whitePlies = listOf(4, 20, 28), blackPlies = listOf(9, 15))

    fun script(context: android.content.Context, pgnText: String, overrides: Overrides, userColor: Color = Color.WHITE): VideoScript {
        val game = PgnParser.parse(pgnText).single()
        val report = report(context, game, overrides, userColor)
        return VideoScriptGenerator(userColor).generate(report, game, NarrationOptions())
    }

    private fun report(context: android.content.Context, game: PgnGame, overrides: Overrides, userColor: Color?): GameReport {
        val alternates = HashSet<String>()
        val evals = ArrayList<PositionEval>()

        for (i in game.moves.indices) {
            val pgnMove = game.moves[i]
            val posBefore = Position.fromFen(pgnMove.positionFenBefore)
            val baseCp = materialCpForSideToMove(posBefore)

            val forceAlternate = i in overrides.whitePlies || i in overrides.blackPlies
            val alt = if (forceAlternate) posBefore.legalMoves().firstOrNull { it.toUci() != pgnMove.uci } else null
            val line = if (alt != null) {
                alternates += alt.toUci()
                EngineLineInput(1, baseCp + 600, null, depth = 16, pvUci = continuation(posBefore, alt, 3))
            } else {
                EngineLineInput(1, baseCp, null, depth = 16, pvUci = listOf(pgnMove.uci))
            }
            evals.add(PositionEval(pgnMove.positionFenBefore, listOf(line), depth = 16))
        }

        val finalFen = game.moves.last().positionFenAfter
        val finalPos = Position.fromFen(finalFen)
        evals.add(
            PositionEval(
                finalFen,
                listOf(EngineLineInput(1, materialCpForSideToMove(finalPos), null, depth = 16, pvUci = emptyList())),
                depth = 16,
            ),
        )

        val book = context.assets.open("openings.tsv").bufferedReader().use { OpeningBook.load(it) }
        val analyzer = GameAnalyzer(MoveClassifier(NeutralSee()), FixtureTacticsDetector(alternates))
        return analyzer.analyze(game, evals, userColor = userColor, book = book)
    }

    private fun continuation(start: Position, first: Move, plies: Int): List<String> {
        val uci = ArrayList<String>()
        var pos = start
        var move: Move? = first
        while (move != null && uci.size < plies) {
            uci.add(move.toUci())
            pos = pos.makeMove(move)
            move = pos.legalMoves().firstOrNull()
        }
        return uci
    }

    private class NeutralSee : SeeEvaluator {
        override fun see(position: Position, move: Move): Int = 0
        override fun isHanging(position: Position, square: Square): Boolean = false
    }

    /** Same shape as core's own fixture detector: forced alternates always report a juicy fork. */
    private class FixtureTacticsDetector(private val alwaysTrigger: Set<String>) : TacticsDetector {
        override fun detect(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> {
            if (move.toUci() in alwaysTrigger) {
                return listOf(
                    TacticInstance(
                        type = TacticType.FORK,
                        byColor = move.color,
                        moveUci = move.toUci(),
                        targetSquares = listOf(move.to, position.kingSquare(move.color.opposite())),
                        materialSwing = 500,
                        confidence = 0.95,
                    ),
                )
            }
            if (!move.isCapture) return emptyList()
            return listOf(
                TacticInstance(
                    type = TacticType.HANGING_PIECE,
                    byColor = move.color,
                    moveUci = move.toUci(),
                    targetSquares = listOf(move.to),
                    materialSwing = move.capturedPiece?.let { PieceValues.of(it) } ?: 100,
                    confidence = 0.9,
                ),
            )
        }
    }

    private fun materialCpForSideToMove(pos: Position): Int {
        var diff = 0
        for (sq in 0..63) {
            val piece = pos.pieceAt(Square(sq)) ?: continue
            if (piece.type == PieceType.KING) continue
            diff += if (piece.color == Color.WHITE) PieceValues.of(piece.type) else -PieceValues.of(piece.type)
        }
        return if (pos.sideToMove == Color.WHITE) diff else -diff
    }
}
