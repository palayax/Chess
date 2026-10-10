package net.palaya.chessanalyzer.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.narration.ArrowRole
import net.palaya.chessanalyzer.core.narration.ArrowSpec
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.ScriptTiming
import net.palaya.chessanalyzer.core.narration.SegmentBestLine
import net.palaya.chessanalyzer.core.narration.SegmentEval
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.video.VideoPlayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * V2 + V4 (ANALYSIS_SPEC 9.8): a key moment's best line in the exported MP4 and in the in-app player, narrated.
 * One key moment ("White plays pawn to a three. Pawn to e four was the move.") is followed by the engine's line
 * 1. e4 e5 2. Nf3 Nc6, one spoken segment per move ("Pawn to e four." ...), then "Back to the game now." over the
 * game's position, at the Normal pace, as the generator lays it out. The voice is [ToneVoiceProvider], whose clips
 * are read back from the store, so every instant is known and checked:
 *
 *  - the container is as long as the timeline (each line move is a segment: speech, hold, gap);
 *  - the audio is the tone while each line move is said and while "Back to the game now." is said: the line is
 *    no longer silent;
 *  - the MP4's frames during the line are the renderer's frames for those instants (the board moves, in the
 *    excursion's colours), and the return frame is the game's position again;
 *  - the in-app player, on the same cached narration, lays out the same timeline and draws the same frame.
 */
@RunWith(AndroidJUnit4::class)
class BestLineVideoInstrumentedTest {

    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val pace = VideoPace.NORMAL
    private val uci = listOf("e2e4", "e7e5", "g1f3", "b8c6")
    private val san = listOf("e4", "e5", "Nf3", "Nc6")
    private val spoken = listOf("Pawn to e four.", "Pawn to e five.", "Knight to f three.", "Knight to c six.")

    private fun script(): VideoScript {
        val captions = listOf("Best line — 1. e4", "Best line — 1. e4 e5", "Best line — 1. e4 e5 2. Nf3", "Best line — 1. e4 e5 2. Nf3 Nc6")
        val line = SegmentBestLine(
            fen = start, uci = uci, san = san, captions = captions,
            stepMs = pace.lineMoveMinMs, finalHoldMs = pace.lineFinalHoldMs,
            spoken = spoken, backToGame = "Back to the game now.",
        )
        val annotate = BoardDirective.Annotate(start, arrows = listOf(ArrowSpec("a2", "a3", ArrowRole.PLAYED), ArrowSpec("e2", "e4", ArrowRole.BEST)))
        val eval = SegmentEval(winPercentWhite = 60.0, evalCp = 40)
        val intro = ScriptSegment(
            index = 0, kind = SegmentKind.INTRO, ply = null, narration = "Here is the moment that mattered.",
            caption = "", board = BoardDirective.Hold(start), estimatedSpeechMs = 2_000L,
        )
        val key = ScriptSegment(
            index = 1, kind = SegmentKind.BLUNDER, ply = 1, narration = "White plays pawn to a three. Pawn to e four was the move.",
            caption = "1. a3 ?", board = annotate, estimatedSpeechMs = 3_000L, speakerColor = Color.WHITE,
            eval = eval, moveNumber = 1, classification = MoveClassification.MISTAKE, bestLine = line,
        )
        var pos = Position.fromFen(start)
        val moves = uci.indices.map { k ->
            val fen = pos.toFen()
            pos = pos.makeMove(pos.parseUci(uci[k]))
            ScriptSegment(
                index = 2 + k, kind = SegmentKind.BEST_LINE, ply = 1, narration = spoken[k], caption = captions[k],
                board = BoardDirective.PlayMove(fen, uci[k], san[k]), estimatedSpeechMs = 1_660L,
                holdAfterMs = if (k == uci.lastIndex) pace.lineFinalHoldMs else 0L,
                speakerColor = if (k % 2 == 0) Color.WHITE else Color.BLACK, eval = eval, moveNumber = 1,
            )
        }
        val back = ScriptSegment(
            index = 6, kind = SegmentKind.KEY_MOMENT, ply = 1, narration = "Back to the game now.",
            caption = "Back to the game — 1. a3", board = annotate, estimatedSpeechMs = 2_015L, speakerColor = Color.WHITE,
            eval = eval, moveNumber = 1, classification = MoveClassification.MISTAKE,
        )
        val segs = listOf(intro, key) + moves + back
        val pacing = (moves + back).sumOf { it.estimatedSpeechMs + it.holdAfterMs }
        return VideoScript("Best line", "test", segs, emptyList(), segs.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, null, pacingMs = pacing)
    }

    /**
     * The largest mean absolute difference (0..255, per channel) over a 32 x 18 grid of cells: one changed
     * square or caption stands out, while the encoder's noise spread over every cell does not.
     */
    private fun boardDiff(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val cols = 32
        val rows = 18
        var worst = 0.0
        for (r in 0 until rows) for (c in 0 until cols) {
            var sum = 0L
            var n = 0
            for (y in (r * h / rows) until ((r + 1) * h / rows) step 2) for (x in (c * w / cols) until ((c + 1) * w / cols) step 2) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                sum += abs(((p shr 16) and 0xff) - ((q shr 16) and 0xff)) + abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff)) + abs((p and 0xff) - (q and 0xff))
                n += 3
            }
            if (n > 0) worst = maxOf(worst, sum.toDouble() / n)
        }
        return worst
    }

    private fun rendered(script: VideoScript, seg: ScriptSegment, elapsedMs: Long, labels: BoardFrameRenderer.PanelLabels): Bitmap {
        val instruction = SegmentFrameBuilder.build(script, seg, elapsedMs, BoardOrientation.WHITE_DOWN, labels)
        val bmp = Bitmap.createBitmap(VideoExporter.VIDEO_WIDTH, VideoExporter.VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        BoardFrameRenderer.renderBoardFrame(Canvas(bmp), bmp.width, bmp.height, (instruction as RenderInstruction.Board).spec)
        return bmp
    }

    @Test
    fun theExportedVideoSaysEachLineMoveAndTheWayBackAndThePlayerDrawsTheSameFrames(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = script()
        val provider = ToneVoiceProvider()
        val exporter = VideoExporter(context)
        val out = exporter.export(script, "v4_best_line", provider)
        val done = exporter.state.value as VideoExporter.State.Completed
        val labels = BoardFrameRenderer.PanelLabels.from(context)
        val scope = CoroutineScope(Dispatchers.Main + Job())
        try {
            // Every segment was spoken: the line's moves and the return are narration now, not silence.
            // (The voice is asked one sentence at a time; each of these is one sentence.)
            for (seg in script.segments.drop(2)) assertTrue("'${seg.narration}' was synthesized", seg.narration in provider.requested)
            // The speech lengths the timeline uses: the cached clips' own lengths, with the timeline's floor.
            val store = NarrationStore.forApp(context)
            fun speechOf(seg: ScriptSegment): Long {
                val file = requireNotNull(store.get(store.keyFor(seg.narration, provider.displayName, provider.narrationCacheFingerprint()))) { "no cached clip for ${seg.index}" }
                return WavUtil.readHeader(file)!!.durationMs.coerceAtLeast(TimelineBuilder.MIN_SEGMENT_MS)
            }
            val starts = ArrayList<Long>()
            val speech = ArrayList<Long>()
            var cursor = 0L
            for (seg in script.segments) {
                starts.add(cursor)
                speech.add(speechOf(seg))
                cursor += speech.last() + seg.holdAfterMs + TimelineBuilder.INTER_SEGMENT_GAP_MS
            }
            val total = cursor
            assertEquals("every line move is a segment of its own: speech + hold + gap", total, done.durationMs)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(out.absolutePath)
            val container = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("container $container vs timeline $total", abs(container - total) <= 200)
            // Each line move stays on screen for its own speech, its hold and the gap (the generator sets the hold
            // from the speech estimate so that this is at least the pace's line rate, spec 9.8).
            for (k in 2..5) {
                val onScreen = speech[k] + script.segments[k].holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
                assertTrue("move ${k - 1} on screen $onScreen ms", onScreen > ScriptTiming.MOVE_ANIMATION_MS + 500)
            }

            // Frames: the key moment's still picture, each move of the line once it has landed, and the return.
            val probes = listOf(1 to starts[2] - 400L) + (2..5).map { it to starts[it] + 700L } + listOf(6 to starts[6] + 400L)
            val mp4 = probes.map { (_, t) -> requireNotNull(retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)) { "no frame at $t ms" } }
            retriever.release()
            val raw = probes.map { (i, t) -> rendered(script, script.segments[i], t - starts[i], labels) }
            for (i in probes.indices) {
                val scaled = Bitmap.createScaledBitmap(mp4[i], raw[i].width, raw[i].height, true)
                val same = boardDiff(scaled, raw[i])
                val other = boardDiff(scaled, raw[(i + 1) % raw.size])
                android.util.Log.i("BestLineVideo", "t=${probes[i].second} ms: diff to its own render $same, to the next probe's $other")
                assertTrue("t=${probes[i].second}: the MP4 frame is the renderer's frame ($same) rather than another's ($other)", same < other)
            }

            // The audio: the tone while each line move and the return are said.
            val pcm = ExportedAudioProbe.decodeAudioTrack(out)
            fun rms(fromMs: Long, toMs: Long): Double {
                val a = (fromMs * pcm.sampleRate / 1000L).toInt().coerceIn(0, pcm.mono.size)
                val b = (toMs * pcm.sampleRate / 1000L).toInt().coerceIn(a, pcm.mono.size)
                if (b <= a) return 0.0
                var s = 0.0
                for (i in a until b) s += pcm.mono[i].toDouble() * pcm.mono[i]
                return kotlin.math.sqrt(s / (b - a))
            }
            for (k in 2..6) {
                val level = rms(starts[k] + 150, starts[k] + speech[k] - 150)
                android.util.Log.i("BestLineVideo", "rms while '${script.segments[k].narration}' is said: $level")
                assertTrue("'${script.segments[k].narration}' is heard: rms $level", level > 1_000)
            }

            // The in-app player, on the same cached narration, lays out the same timeline and draws the same frame.
            val player = VideoPlayerController(context, script, scope, provider)
            assertEquals(total, player.uiState.value.totalDurationMs)
            val at = starts[3] + 700L // 1... e5 has landed
            player.seekToMs(at)
            val shown = (player.uiState.value.instruction as RenderInstruction.Board).spec
            val expected = (SegmentFrameBuilder.build(script, script.segments[3], at - starts[3], BoardOrientation.WHITE_DOWN, labels) as RenderInstruction.Board).spec
            // The labels hold lambdas (compared by identity), so the specs are compared with one set of labels.
            val english = BoardFrameRenderer.PanelLabels.ENGLISH
            assertEquals(expected.copy(labels = english), shown.copy(labels = english))
            assertEquals("e5", shown.san)
            assertEquals(context.getString(R.string.panel_best_line), shown.excursionLabel)
            assertTrue(shown.excursionActive)
            assertNull("no verdict on a line move", BoardFrameRenderer.panelChip(shown))
            assertEquals("Pawn to e five.", script.segments[player.uiState.value.segmentIndex].narration)
            // Back to the game: the game's own position again, no excursion.
            player.seekToMs(starts[6] + 400L)
            val backSpec = (player.uiState.value.instruction as RenderInstruction.Board).spec
            assertFalse(backSpec.excursionActive)
            assertEquals(MoveClassification.MISTAKE, backSpec.classification)
            player.release()
        } finally {
            scope.cancel()
            out.delete()
        }
    }
}
