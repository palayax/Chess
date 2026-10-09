package net.palaya.chessanalyzer.rephrase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.palaya.chessanalyzer.core.analysis.CommentaryGenerator
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.KeyMoment
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.text.FakeRephraser
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.core.text.RephraseSurface
import net.palaya.chessanalyzer.core.text.Rephraser
import net.palaya.chessanalyzer.core.text.RephraseRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * docs/LLM_REPHRASE_DESIGN.md §6, §8.1: the cache, the job, Skip, the foreground gate and the failure paths,
 * against [FakeRephraser] (no model). Every output still goes through the real ClaimChecker.
 */
class RephraseServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6?? 4.Qxf7#: the generator writes a blunder card with a charge and "Better was". */
    private fun report(side: Color? = null): GameReport {
        val sans = listOf("e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#")
        val mate = TacticInstance(
            TacticType.MATE_NET, Color.WHITE, "h5f7", listOf(Square.fromAlgebraic("e8")), listOf(Square.fromAlgebraic("f7")),
            10_000, "Qxf7# is checkmate.", 0.95
        )
        var pos = Position.startPosition()
        val a = sans.mapIndexed { i, san ->
            val move = pos.parseSan(san)
            val before = pos
            pos = pos.makeMove(move)
            val ply = i + 1
            val blunder = ply == 6
            val last = ply == 7
            MoveAnnotation(
                ply = ply, moveNumber = (ply + 1) / 2, color = move.color, san = before.moveToSan(move), uci = move.toUci(),
                fenBefore = before.toFen(), fenAfter = pos.toFen(),
                classification = when { blunder -> MoveClassification.BLUNDER; last -> MoveClassification.GREAT; else -> MoveClassification.GOOD },
                loss = if (blunder) 50.0 else 0.0, winPercentBefore = 55.0, winPercentAfter = if (blunder) 5.0 else 55.0,
                evalBeforeCp = 0, evalAfterCp = 0, mateInBefore = if (last) 1 else null,
                mateInAfter = when { blunder -> 1; last -> 0; else -> null }, bestMoveSan = if (blunder) "g6" else null,
                tacticsPlayed = if (last) listOf(mate) else emptyList(), tacticsFound = if (last) listOf(mate) else emptyList(),
                threatsAllowed = if (blunder) listOf(mate) else emptyList(),
            )
        }
        fun player(c: Color) = PlayerReport(c, null, 80.0, 1500, true, emptyMap(), emptyList(), emptyList())
        val raw = GameReport(player(Color.WHITE), player(Color.BLACK), a, null, null, "1-0", List(8) { 50.0 },
            listOf(KeyMoment(6, "Nf6", MoveClassification.BLUNDER, 50.0, "")), 12)
        return CommentaryGenerator().regenerate(raw, side)
    }

    private fun cache() = RephraseCache(File(tmp.root, "rephrase/cache"))

    /** A "model" that rewrites the blunder card faithfully and returns every other text unchanged. */
    private fun faithful(r: GameReport): FakeRephraser {
        val blunder = r.annotations.first { it.ply == 6 }.text
        val reworded = blunder.replace(Regex("^(This lets White play|Now White can play|This hands White) Qxf7#"), "White can now answer with Qxf7#")
        require(reworded != blunder) { blunder }
        return FakeRephraser.table(mapOf(blunder to reworded), id = "fake-model@p1")
    }

    @Test
    fun polishCachesTheVerdictsAndApplyCachedSwapsInOnlyAcceptedTexts() = runBlocking {
        val r = report()
        val fake = faithful(r)
        val service = RephraseService(cache(), { fake })
        val stats = service.polish(RephraseService.cardOrder(r))
        assertEquals(1, stats.accepted)
        assertEquals(r.annotations.map { it.text }.distinct().size - 1, stats.unchanged)
        val applied = service.applyCached(r)
        val card = applied.annotations.first { it.ply == 6 }.text
        assertTrue(card, card.startsWith("White can now answer with Qxf7#"))
        assertEquals(card, applied.keyMoments.single().summary)
        assertEquals(r.annotations.filter { it.ply != 6 }.map { it.text }, applied.annotations.filter { it.ply != 6 }.map { it.text })
        // a second run asks the model nothing: every verdict is cached
        val calls = fake.calls
        val again = service.polish(RephraseService.cardOrder(r))
        assertEquals(calls, fake.calls)
        assertEquals(again.cached, r.annotations.map { it.text }.distinct().size)
    }

    @Test
    fun aRejectedRewordingIsCachedAsRejectedAndNeverShown() = runBlocking {
        val r = report()
        val blunder = r.annotations.first { it.ply == 6 }.text
        val adversary = FakeRephraser.adversary(id = "fake-model@p1") { it.replace("White", "Black") }
        val service = RephraseService(cache(), { adversary })
        val stats = service.polish(listOf(RephraseSurface.CARD to blunder))
        assertEquals(1, stats.rejected)
        assertEquals(mapOf("CHARGE" to 1), stats.rejectedByReason) // the charge now names Black as its beneficiary
        assertEquals(blunder, service.applyCached(r).annotations.first { it.ply == 6 }.text)
        assertTrue(cache().get("fake-model@p1", RephraseSurface.CARD, blunder) is RephraseCache.Entry.Rejected)
    }

    @Test
    fun aPromptVersionBumpMovesEveryKeySoEverythingIsRetried() = runBlocking {
        val r = report()
        val p1 = faithful(r)
        RephraseService(cache(), { p1 }).polish(RephraseService.cardOrder(r))
        val p2 = FakeRephraser.identity(id = "fake-model@p2")
        val stats = RephraseService(cache(), { p2 }).polish(RephraseService.cardOrder(r))
        assertEquals(0, stats.cached)
        assertTrue(p2.calls > 0)
    }

    @Test
    fun theFeatureOffMeansNoBackendAndTheOriginalsAtOnce() = runBlocking {
        val r = report()
        val fake = faithful(r)
        val on = RephraseService(cache(), { fake })
        on.polish(RephraseService.cardOrder(r))
        val off = RephraseService(cache(), { null })
        assertSame(r, off.applyCached(r))
        assertEquals(RephraseService.cardOrder(r).size, off.polish(RephraseService.cardOrder(r)).unavailable)
        assertEquals(null, off.activeId())
    }

    @Test
    fun aFailingOrSlowBackendLeavesTheOriginalAndCachesNothing() = runBlocking {
        val r = report()
        val text = r.annotations.first { it.ply == 6 }.text
        val throwing = object : Rephraser {
            override val id = "boom@p1"
            override suspend fun rephrase(request: RephraseRequest): RephraseResult = throw IllegalStateException("native")
        }
        val s1 = RephraseService(cache(), { throwing }).polish(listOf(RephraseSurface.CARD to text))
        assertEquals(1, s1.unavailable)
        assertEquals(null, cache().get("boom@p1", RephraseSurface.CARD, text))
        val slow = object : Rephraser {
            override val id = "slow@p1"
            override suspend fun rephrase(request: RephraseRequest): RephraseResult { delay(5_000); return RephraseResult.Unchanged }
        }
        val s2 = RephraseService(cache(), { slow }, requestTimeoutMs = 50).polish(listOf(RephraseSurface.CARD to text))
        assertEquals(1, s2.unavailable)
        assertEquals(null, cache().get("slow@p1", RephraseSurface.CARD, text))
    }

    @Test
    fun skipIsCancellationAndKeepsWhatIsDone() = runBlocking {
        val r = report()
        val items = RephraseService.cardOrder(r)
        val gate = CompletableDeferred<Unit>()
        var served = 0
        val blocking = object : Rephraser {
            override val id = "gate@p1"
            override suspend fun rephrase(request: RephraseRequest): RephraseResult {
                served++
                if (served == 2) gate.await()
                return RephraseResult.Unchanged
            }
        }
        val service = RephraseService(cache(), { blocking })
        val job = async { service.polish(items) }
        while (served < 2) yield()
        job.cancel()
        assertTrue(job.isCancelled || runCatching { job.await() }.isFailure)
        // the first verdict is kept, the interrupted one is not
        assertTrue(cache().get("gate@p1", RephraseSurface.CARD, items[0].second) != null)
        assertEquals(null, cache().get("gate@p1", RephraseSurface.CARD, items[1].second))
    }

    @Test
    fun theJobWaitsWhileNoScreenIsInTheForeground() = runBlocking {
        val r = report()
        val fg = MutableStateFlow(false)
        val fake = FakeRephraser.identity(id = "fg@p1")
        val service = RephraseService(cache(), { fake }, foreground = fg)
        val job = async { service.polish(RephraseService.keyMomentTexts(r)) }
        repeat(20) { yield() }
        assertEquals(0, fake.calls)
        fg.value = true
        val stats = job.await()
        assertEquals(1, stats.unchanged)
        assertEquals(1, fake.calls)
    }

    @Test
    fun progressCountsEveryItemOnceInOrder() = runBlocking {
        val r = report()
        val seen = ArrayList<Pair<Int, Int>>()
        RephraseService(cache(), { FakeRephraser.identity(id = "p@p1") }).polish(RephraseService.cardOrder(r)) { d, t -> seen += d to t }
        val total = RephraseService.cardOrder(r).size
        assertEquals((0..total).map { it to total }, seen)
    }

    @Test
    fun theKeyMomentsComeFirstThenTheCardsByDistance() {
        val r = report()
        val order = RephraseService.cardOrder(r, aroundPly = 3).map { it.second }
        assertEquals(r.keyMoments.single().summary, order.first())
        assertEquals(order.distinct(), order)
        assertEquals(r.annotations.first { it.ply == 3 }.text, order[1])
    }

    @Test
    fun theLogCarriesHashesAndReasonsNeverTheText() = runBlocking {
        val r = report()
        val lines = ArrayList<String>()
        val text = r.annotations.first { it.ply == 6 }.text
        RephraseService(cache(), { FakeRephraser.adversary(id = "l@p1") { "$it It is brilliant." } }, log = { lines += it })
            .polish(listOf(RephraseSurface.CARD to text))
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().contains("rejected"))
        assertFalse(lines.single(), lines.single().contains("Qxf7"))
    }
}
