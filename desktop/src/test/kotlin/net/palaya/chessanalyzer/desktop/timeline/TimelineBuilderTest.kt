package net.palaya.chessanalyzer.desktop.timeline

import kotlinx.serialization.json.JsonObject
import net.palaya.chessanalyzer.desktop.script.ScriptBeat
import net.palaya.chessanalyzer.desktop.script.ScriptFile
import net.palaya.chessanalyzer.desktop.script.ScriptLine
import net.palaya.chessanalyzer.desktop.storyboard.Beat
import net.palaya.chessanalyzer.desktop.storyboard.BoardCue
import net.palaya.chessanalyzer.desktop.storyboard.StoryHeader
import net.palaya.chessanalyzer.desktop.storyboard.StorySummary
import net.palaya.chessanalyzer.desktop.storyboard.Storyboard
import net.palaya.chessanalyzer.desktop.tts.AudioManifest
import net.palaya.chessanalyzer.desktop.tts.ManifestBackend
import net.palaya.chessanalyzer.desktop.tts.ManifestLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** §7 rules on a hand-built storyboard + manifest: audio drives the picture, nothing is truncated. */
class TimelineBuilderTest {

    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun beat(id: String, cue: BoardCue, hold: Long = 0) = Beat(
        id = id, kind = "NORMAL_MOVE", ply = 1, moveNumber = 1, color = "WHITE", classification = null,
        caption = "", fallbackText = "", estimatedMs = 1000, holdAfterMs = hold, eval = null, evalSwingCp = null,
        tactic = null, excursion = false, board = listOf(cue),
    )

    private fun line(id: String, beat: String, ms: Int) = ManifestLine(
        id = id, beatId = beat, wav = "audio/$id.wav", durationMs = ms, sampleRate = 24000, leadingSilenceMs = 100,
        trailingSilenceMs = 80, rmsDbfs = -20.0, peakDbfs = -3.0, nearSilentRatio = 0.1, clippedSamples = 0,
        workerDurationMs = ms, rtf = 0.1, synthSeconds = 0.1, cached = false, textSha256 = "x", words = 3,
    )

    @Test
    fun audioDrivesTheBeatAndGapsFollowTheRules() {
        val beats = listOf(
            beat("b000", BoardCue("play_move", fen = start, uci = listOf("e2e4"), san = listOf("e4"))),   // long speech
            beat("b001", BoardCue("play_line", fen = start, uci = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5"))), // short speech, 5 steps
            beat("b002", BoardCue("hold", fen = start), hold = 3000),                                  // puzzle pause
            beat("b003", BoardCue("hold", fen = start)),                                               // silent
        )
        val sb = Storyboard(gameId = "g", lang = "en", director = "test", header = StoryHeader("W", "B", null, null, "*", null, null, null),
            userColor = null, chapters = emptyList(), beats = beats, estimatedSeconds = 0.0, summary = StorySummary(null, null, null, null))
        val script = ScriptFile(gameId = "g", lang = "en", source = "template", beats = listOf(
            ScriptBeat("b000", "template", lines = listOf(ScriptLine("b000_l0", "Is it?", "Is it?", "Is it?"), ScriptLine("b000_l1", "Yes.", "Yes.", "Yes."))),
            ScriptBeat("b001", "template", lines = listOf(ScriptLine("b001_l0", "Look.", "Look.", "Look."))),
            ScriptBeat("b002", "template", lines = listOf(ScriptLine("b002_l0", "Find it.", "Find it.", "Find it."))),
            ScriptBeat("b003", "template", lines = emptyList()),
        ))
        val manifest = AudioManifest(backend = ManifestBackend("fake", "1", "cpu", 24000, "t", "fp", JsonObject(emptyMap())), lines = listOf(
            line("b000_l0", "b000", 2000), line("b000_l1", "b000", 1500), line("b001_l0", "b001", 1000), line("b002_l0", "b002", 1200),
        ))
        val t = TimelineBuilder.build(sb, script, manifest, 30, 1920, 1080)
        val (b0, b1, b2, b3) = t.beats

        assertEquals(TimelineBuilder.LEAD_IN_MS, b0.startMs)
        // Line 1 starts after line 0's full audio plus the 320 ms question gap.
        assertEquals(b0.startMs + 2000 + 320, b0.lines[1].audioStartMs)
        assertEquals("never truncated", 1500L, b0.lines[1].durationMs)
        assertEquals(b0.lines[1].audioStartMs + 1500, b0.speechEndMs)
        assertEquals("speech + tail", b0.speechEndMs + TimelineBuilder.TAIL_MS, b0.endMs)
        assertEquals(b0.lines[0].audioStartMs + 100, b0.lines[0].voicedStartMs)

        // Five variation steps (4 s) outlast one second of speech: the beat waits for the picture.
        assertEquals(b0.endMs, b1.startMs)
        assertEquals(b1.startMs + 5 * TimelineBuilder.LINE_STEP_MS + TimelineBuilder.SETTLE_MS + TimelineBuilder.TAIL_MS, b1.endMs)

        // The find-the-move hold is exactly 3000 ms of silence after the speech.
        assertEquals(b2.speechEndMs + 3000 + TimelineBuilder.TAIL_MS, b2.endMs)

        // A silent beat still gets the 900 ms minimum.
        assertEquals(TimelineBuilder.MIN_BEAT_MS, b3.endMs - b3.startMs)
        assertEquals(b3.endMs + TimelineBuilder.TAIL_OUT_MS, t.totalMs)
        assertEquals(Math.round(t.totalMs * 30 / 1000.0).toInt(), TimelineBuilder.frameCount(t))
    }
}
