package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.RealGameFixture
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/LLM_REPHRASE_DESIGN.md §6.2 and §8.1: the narration post-pass and the card-text swap. */
class RephrasedScriptTest {

    private val wpm = 169

    private val script: VideoScript by lazy {
        val g = RealGameFixture.immortal
        VideoScriptGenerator(null).generate(g.report(null), g.pgn, NarrationOptions(speechWpm = wpm, pace = VideoPace.NORMAL))
    }
    private val budget by lazy { VideoScriptGenerator.budgetMs((RealGameFixture.immortal.pgn.moves.size + 1) / 2) }

    /** Every eligible beat with " Indeed." style growth of [extraWords] words. */
    private fun grown(extraWords: Int): Map<String, String> =
        RephrasedScript.beats(script).associateWith { it.dropLast(1) + List(extraWords) { " now" }.joinToString("") + "." }

    @Test
    fun `an empty map is the identity`() {
        assertSame(script, RephrasedScript.apply(script, emptyMap(), wpm, budget))
    }

    @Test
    fun `only narration and its estimate change - boards, lead-ins, holds, captions, best lines and evals are untouched`() {
        val beat = RephrasedScript.beats(script).first { it.split(' ').size > 8 }
        val shorter = beat.replace("Black plays", "Black goes for")
        val out = RephrasedScript.apply(script, mapOf(beat to shorter), wpm, budget)
        assertEquals(script.segments.size, out.segments.size)
        for ((a, b) in script.segments.zip(out.segments)) {
            assertEquals(a.copy(narration = "", estimatedSpeechMs = 0), b.copy(narration = "", estimatedSpeechMs = 0))
            if (a.narration == beat) {
                assertEquals(shorter, b.narration)
                assertEquals(VideoScriptGenerator.estimateSpeechMs(shorter, wpm), b.estimatedSpeechMs)
            } else {
                assertEquals(a, b)
            }
        }
        val growth = out.segments.sumOf { it.estimatedSpeechMs } - script.segments.sumOf { it.estimatedSpeechMs }
        assertEquals(script.totalEstimatedMs + growth, out.totalEstimatedMs)
        assertEquals(script.pacingMs, out.pacingMs)
        assertEquals(script.chapters, out.chapters)
        assertEquals(script.recap, out.recap)
    }

    @Test
    fun `the allowlist covers the prose kinds, and a kind outside it is denied`() {
        // V4's spoken best line (one template sentence per engine move, then "Back to the game now.") is not prose to
        // reword and stays denied; every other kind today is prose. A new kind must be added on purpose.
        assertEquals(SegmentKind.values().toSet() - SegmentKind.BEST_LINE, RephrasedScript.ALLOWED_KINDS)
        assertTrue(SegmentKind.BEST_LINE !in RephrasedScript.ALLOWED_KINDS)
        val seg = script.segments.first { RephrasedScript.eligible(it) }
        assertTrue(RephrasedScript.eligible(seg))
        assertTrue(!RephrasedScript.eligible(seg.copy(narration = seg.caption)))
        assertTrue(!RephrasedScript.eligible(seg.copy(narration = "  ")))
    }

    @Test
    fun `a beat that is not eligible keeps its words even when the map has them`() {
        val seg = script.segments.first { RephrasedScript.eligible(it) }
        val twin = seg.copy(caption = seg.narration) // narration == caption: never touched
        val s2 = script.copy(segments = listOf(twin))
        val out = RephrasedScript.apply(s2, mapOf(seg.narration to "Something else entirely here now."), wpm, budget)
        assertEquals(twin, out.segments.single())
    }

    @Test
    fun `the script-level cap reverts the largest growth first until the story fits`() {
        val out = RephrasedScript.apply(script, grown(12), wpm, budget)
        val before = script.segments.sumOf { it.estimatedSpeechMs }
        val after = out.segments.sumOf { it.estimatedSpeechMs }
        assertTrue("$after <= 1.1 x $before", after <= (before * RephrasedScript.MAX_GROWTH).toLong())
        assertTrue(out.storyMs <= maxOf(budget, script.storyMs))
        // something was kept and something reverted
        val changed = script.segments.zip(out.segments).count { (a, b) -> a.narration != b.narration }
        assertTrue("changed $changed", changed in 1 until RephrasedScript.beats(script).size)
        // the reverted beats are the ones that grew most: every kept beat grew no more than any reverted one
        val deltas = script.segments.zip(out.segments).filter { (a, _) -> RephrasedScript.eligible(a) }.map { (a, b) ->
            val grownText = grown(12).getValue(a.narration)
            Triple(a.narration != b.narration, VideoScriptGenerator.estimateSpeechMs(grownText, wpm) - a.estimatedSpeechMs, a.index)
        }
        val keptMax = deltas.filter { it.first }.maxOf { it.second }
        val revertedMin = deltas.filter { !it.first }.minOf { it.second }
        assertTrue("kept $keptMax <= reverted $revertedMin", keptMax <= revertedMin)
    }

    @Test
    fun `the cap is deterministic`() {
        assertEquals(RephrasedScript.apply(script, grown(12), wpm, budget), RephrasedScript.apply(script, grown(12), wpm, budget))
    }

    @Test
    fun `a small growth everywhere is kept whole`() {
        val out = RephrasedScript.apply(script, grown(1), wpm, budget)
        val changed = script.segments.zip(out.segments).count { (a, b) -> a.narration != b.narration }
        assertEquals(script.segments.count { RephrasedScript.eligible(it) }, changed)
    }

    @Test
    fun `the beats are the eligible narrations in script order, each once`() {
        val beats = RephrasedScript.beats(script)
        assertEquals(beats.distinct(), beats)
        assertEquals(script.segments.filter { RephrasedScript.eligible(it) }.map { it.narration }.distinct(), beats)
        assertTrue(beats.size >= 20)
    }

    @Test
    fun `the card swap replaces the annotation texts and the key moment summaries that repeat them, nothing else`() {
        val report = RealGameFixture.immortal.report(Color.WHITE)
        val target = report.keyMoments.first()
        val map = mapOf(target.summary to "Reworded.")
        val out = RephrasedReport.apply(report, map)
        assertEquals("Reworded.", out.keyMoments.first().summary)
        assertEquals("Reworded.", out.annotations.first { it.ply == target.ply }.text)
        assertEquals(report.annotations.map { it.copy(text = "") }, out.annotations.map { it.copy(text = "") })
        assertEquals(report.copy(annotations = emptyList(), keyMoments = emptyList()), out.copy(annotations = emptyList(), keyMoments = emptyList()))
        assertSame(report, RephrasedReport.apply(report, emptyMap()))
        assertNotEquals(report, out)
    }

    @Test
    fun `a segment built by hand with an unknown-style card board is handled like any other`() {
        val text = "One thing to take away from this game is that every loose piece in it was punished sooner or later by the other side."
        val est = VideoScriptGenerator.estimateSpeechMs(text, wpm)
        val seg = ScriptSegment(0, SegmentKind.OUTRO_LESSONS, null, text, "", BoardDirective.Card("Lessons", emptyList()), est)
        val s = script.copy(segments = listOf(seg), totalEstimatedMs = est, pacingMs = 0)
        val reworded = text.replace("One thing", "One clear thing")
        val out = RephrasedScript.apply(s, mapOf(text to reworded), wpm, 60_000)
        assertEquals(reworded, out.segments.single().narration)
        assertEquals(VideoScriptGenerator.estimateSpeechMs(reworded, wpm), out.totalEstimatedMs)
    }
}
