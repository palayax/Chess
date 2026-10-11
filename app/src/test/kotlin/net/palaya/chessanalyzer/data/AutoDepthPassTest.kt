package net.palaya.chessanalyzer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Auto-depth's second pass (ANALYSIS_SPEC §8.5) as `AnalysisService` drives it, with a fake deep search: the picked
 * positions are replaced and nothing else, a killed pass resumes from its checkpoint without searching a finished
 * position again and ends with the same result, and a first-pass result can never stand in for a deep one.
 */
class AutoDepthPassTest {

    private val game: PgnGame = PgnParser.parse("1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. c3 Nf6 *").single()
    private val positions = listOf(game.moves.first().positionFenBefore) + game.moves.map { it.positionFenAfter }

    private fun eval(i: Int, whiteCp: Int, depth: Int, best: String? = null): PositionEval {
        val position = Position.fromFen(positions[i])
        val stm = if (position.sideToMove == Color.WHITE) 1 else -1
        val first = best ?: game.moves.getOrNull(i)?.uci ?: position.legalMoves().first().toUci()
        val other = position.legalMoves().map { it.toUci() }.first { it != first }
        return PositionEval(
            positions[i],
            listOf(EngineLineInput(1, whiteCp * stm, null, depth, listOf(first)), EngineLineInput(2, whiteCp * stm - 15, null, depth, listOf(other))),
            depth,
            depth,
        )
    }

    /** First pass at 14: 2.Nf3 (ply 3) is a mistake (Bc4 was best), so positions 2 and 3 are picked. */
    private val base: List<PositionEval> = positions.indices.map { i ->
        eval(i, if (i >= 3) -150 else 20, 14, best = if (i == 2) "f1c4" else null)
    }

    /** The deep search agrees with the first pass (no instability), at depth 18. */
    private fun deep(i: Int): PositionEval = base[i].copy(
        lines = base[i].lines.map { it.copy(depth = 18) }, depth = 18, requestedDepth = 18,
    )

    // No cap: a 9-position game would leave room for one pair under the default 25% (the cap is tested in :core).
    private fun pass() = AutoDepthPass(game, null, MoveClassifier(StaticExchangeEvaluator()), AdaptiveDepth.Config(maxFraction = 1.0))

    @Test
    fun thePickedPositionsAreReplacedByTheirDeepResultAndNothingElse() = runBlocking {
        val searched = ArrayList<Int>()
        val reported = ArrayList<AutoDepthPass.Deepened>()
        val checkpoints = ArrayList<List<PositionEval>>()
        val result = pass().run(
            base = base,
            known = emptyMap(),
            search = { i, _, _ -> searched += i; deep(i) },
            onDeepened = { reported += it },
            checkpoint = { checkpoints += it },
        )
        assertEquals(listOf(2, 3), searched)
        assertEquals(listOf(2, 3), reported.map { it.index })
        assertEquals(listOf(1, 2), reported.map { it.done })
        assertTrue(reported.all { it.picked == 2 && !it.reused && AdaptiveDepth.Reason.MISTAKE in it.reasons })
        for (i in positions.indices) {
            assertEquals("position $i", if (i in setOf(2, 3)) 18 else 14, result.evals[i].requestedDepth)
        }
        // A checkpoint after every picked position, the last one equal to the result.
        assertEquals(2, checkpoints.size)
        assertEquals(result.evals, checkpoints.last())
        assertEquals(1, result.rounds)
    }

    @Test
    fun aKilledPassResumesFromItsCheckpointAndEndsWithTheSameResult() = runBlocking {
        val uninterrupted = pass().run(base, emptyMap(), search = { i, _, _ -> deep(i) })
        // The process dies during the second search: the checkpoint holds the first deep result.
        var checkpoint: List<PositionEval>? = null
        try {
            pass().run(
                base, emptyMap(),
                search = { i, done, _ -> if (done == 1) throw IllegalStateException("killed") else deep(i) },
                checkpoint = { checkpoint = it },
            )
            fail("the search should have failed")
        } catch (e: IllegalStateException) {
            // expected
        }
        val known = AutoDepthPass.knownDeepEvals(positions, 18, checkpoint)
        assertEquals(setOf(2), known.keys)
        val searched = ArrayList<Int>()
        val resumed = pass().run(base, known, search = { i, _, _ -> searched += i; deep(i) })
        assertEquals("only the unfinished position is searched again", listOf(3), searched)
        assertEquals(listOf(true, false), resumed.deepened.map { it.reused })
        assertEquals(uninterrupted.evals, resumed.evals)
    }

    @Test
    fun aKnownResultMustHaveTheSameFenAndTheDeepTiersDepth() {
        val wrongFen = deep(2).copy(fen = positions[5])
        val firstPass = base[3]
        val known = AutoDepthPass.knownDeepEvals(positions, 18, listOf(base[0], base[1], wrongFen, firstPass, deep(4)))
        assertEquals("a base result, a misplaced one and a first-pass one are all refused", setOf(4), known.keys)
        // The first source wins over a later one (the Auto checkpoint over a Deep cache).
        val a = deep(4)
        val b = deep(4).copy(depth = 17)
        assertEquals(a, AutoDepthPass.knownDeepEvals(positions, 18, List(5) { if (it == 4) a else base[it] }, List(5) { if (it == 4) b else base[it] })[4])
        assertEquals(emptyMap<Int, PositionEval>(), AutoDepthPass.knownDeepEvals(positions, 18, null, null))
    }

    @Test
    fun aDeepResultThatMovesALotStartsASecondRoundOnItsNeighbours() = runBlocking {
        // Position 3 comes back 300 cp worse for White: the moves on both of its sides are judged again.
        val result = pass().run(base, emptyMap(), search = { i, _, _ ->
            if (i == 3) eval(3, -450, 18).copy(requestedDepth = 18) else deep(i)
        })
        assertEquals(2, result.rounds)
        assertEquals(listOf(2, 3, 4), result.deepened.map { it.index }.sorted())
        assertTrue(result.deepened.first { it.index == 4 }.round == 2)
    }

    @Test
    fun cancellationIsNotSwallowed() = runBlocking {
        try {
            pass().run(base, emptyMap(), search = { _, _, _ -> throw CancellationException("cancelled") })
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected: AnalysisService saves its checkpoint and reports Cancelled
        }
    }
}
