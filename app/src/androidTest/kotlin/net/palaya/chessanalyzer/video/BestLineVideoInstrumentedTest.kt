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
import net.palaya.chessanalyzer.core.narration.ArrowRole
import net.palaya.chessanalyzer.core.narration.ArrowSpec
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentBestLine
import net.palaya.chessanalyzer.core.narration.SegmentEval
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.video.VideoPlayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * V2 (ANALYSIS_SPEC 9.8): a key moment's best line in the exported MP4 and in the in-app player. One key
 * moment ("White plays a3. Pawn to e four was the move.") carries the engine's line 1. e4 e5 2. Nf3 Nc6,
 * played silently after the speech at the Normal pace. The voice is [ToneVoiceProvider], whose clip
 * lengths are known in advance, so the exact instant the line starts is known and checked:
 *
 *  - the container is as long as the timeline, the line included (it is inside the segment's hold);
 *  - the audio is the tone during the speech and silence while the line plays;
 *  - the MP4's frames during the line are the renderer's frames for those instants (the board moves),
 *    not the still position with its arrows;
 *  - the in-app player, on the same cached narration, draws the same frame at the same instant.
 */
@RunWith(AndroidJUnit4::class)
class BestLineVideoInstrumentedTest {

    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val pace = VideoPace.NORMAL

    private fun script(): VideoScript {
        val line = SegmentBestLine(
            fen = start,
            uci = listOf("e2e4", "e7e5", "g1f3", "b8c6"),
            san = listOf("e4", "e5", "Nf3", "Nc6"),
            captions = listOf("Best line — 1. e4", "Best line — 1. e4 e5", "Best line — 1. e4 e5 2. Nf3", "Best line — 1. e4 e5 2. Nf3 Nc6"),
            stepMs = pace.lineMoveMinMs,
            finalHoldMs = pace.lineFinalHoldMs,
        )
        val intro = ScriptSegment(
            index = 0, kind = SegmentKind.INTRO, ply = null, narration = "Here is the moment that mattered.",
            caption = "", board = BoardDirective.Hold(start), estimatedSpeechMs = 2_000L,
        )
        val key = ScriptSegment(
            index = 1, kind = SegmentKind.BLUNDER, ply = 1, narration = "White plays pawn to a three. Pawn to e four was the move.",
            caption = "1. a3 ?", board = BoardDirective.Annotate(start, arrows = listOf(ArrowSpec("a2", "a3", ArrowRole.PLAYED), ArrowSpec("e2", "e4", ArrowRole.BEST))),
            estimatedSpeechMs = 3_000L, holdAfterMs = line.durationMs, speakerColor = Color.WHITE,
            eval = SegmentEval(winPercentWhite = 60.0, evalCp = 40), moveNumber = 1, classification = MoveClassification.MISTAKE,
            bestLine = line,
        )
        val segs = listOf(intro, key)
        return VideoScript("Best line", "test", segs, emptyList(), segs.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, null, pacingMs = line.durationMs)
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

    private fun rendered(script: VideoScript, seg: ScriptSegment, elapsedMs: Long, speechMs: Long, labels: BoardFrameRenderer.PanelLabels): Bitmap {
        val instruction = SegmentFrameBuilder.build(script, seg, elapsedMs, BoardOrientation.WHITE_DOWN, labels, speechMs = speechMs)
        val bmp = Bitmap.createBitmap(VideoExporter.VIDEO_WIDTH, VideoExporter.VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        BoardFrameRenderer.renderBoardFrame(Canvas(bmp), bmp.width, bmp.height, (instruction as RenderInstruction.Board).spec)
        return bmp
    }

    @Test
    fun theExportedVideoPlaysTheLineAfterTheSpeechAndThePlayerDrawsTheSameFrames(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = script()
        val key = script.segments[1]
        val line = key.bestLine!!
        val provider = ToneVoiceProvider()
        val exporter = VideoExporter(context)
        val out = exporter.export(script, "v2_best_line", provider)
        val done = exporter.state.value as VideoExporter.State.Completed
        val labels = BoardFrameRenderer.PanelLabels.from(context)
        val scope = CoroutineScope(Dispatchers.Main + Job())
        try {
            // The speech lengths the timeline uses: the cached clips' own lengths, read the way the player reads
            // them (the export put them in the store), with the timeline's floor.
            val store = NarrationStore.forApp(context)
            fun speechOf(seg: ScriptSegment): Long {
                val file = requireNotNull(store.get(store.keyFor(seg.narration, provider.displayName, provider.narrationCacheFingerprint()))) { "no cached clip for ${seg.index}" }
                return WavUtil.readHeader(file)!!.durationMs.coerceAtLeast(TimelineBuilder.MIN_SEGMENT_MS)
            }
            val speech0 = speechOf(script.segments[0])
            val speech1 = speechOf(key)
            val keyStart = speech0 + TimelineBuilder.INTER_SEGMENT_GAP_MS
            val lineStart = keyStart + speech1
            val total = keyStart + speech1 + key.holdAfterMs + TimelineBuilder.INTER_SEGMENT_GAP_MS
            assertEquals("the line is inside the segment: the timeline is speech + hold + gaps", total, done.durationMs)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(out.absolutePath)
            val container = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("container $container vs timeline $total", abs(container - total) <= 200)

            // Frames: the still position with its arrows just before the line, then each move of the line.
            val instants = listOf(
                lineStart - 400L, // the speech's last moment: the position before the move, arrows on it
                lineStart + line.stepMs - 200L, // 1. e4 has landed
                lineStart + 2 * line.stepMs - 200L, // 1... e5
                lineStart + 4 * line.stepMs + line.finalHoldMs / 2, // the line's final position, held
            )
            val mp4 = instants.map { t -> requireNotNull(retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)) { "no frame at $t ms" } }
            retriever.release()
            val raw = instants.map { t -> rendered(script, key, t - keyStart, speech1, labels) }
            for (i in instants.indices) {
                val same = boardDiff(Bitmap.createScaledBitmap(mp4[i], raw[i].width, raw[i].height, true), raw[i])
                val other = boardDiff(Bitmap.createScaledBitmap(mp4[i], raw[i].width, raw[i].height, true), raw[(i + 1) % raw.size])
                android.util.Log.i("BestLineVideo", "t=${instants[i]} ms: diff to its own render $same, to the next step's $other")
                assertTrue("t=${instants[i]}: the MP4 frame is the renderer's frame ($same) rather than another step's ($other)", same < other)
            }
            // The audio: the tone while the voice speaks, silence while the line plays.
            val pcm = ExportedAudioProbe.decodeAudioTrack(out)
            fun rms(fromMs: Long, toMs: Long): Double {
                val a = (fromMs * pcm.sampleRate / 1000L).toInt().coerceIn(0, pcm.mono.size)
                val b = (toMs * pcm.sampleRate / 1000L).toInt().coerceIn(a, pcm.mono.size)
                if (b <= a) return 0.0
                var s = 0.0
                for (i in a until b) s += pcm.mono[i].toDouble() * pcm.mono[i]
                return kotlin.math.sqrt(s / (b - a))
            }
            val speaking = rms(keyStart + 200, lineStart - 200)
            val linePlaying = rms(lineStart + 200, lineStart + line.durationMs - 200)
            android.util.Log.i("BestLineVideo", "rms speech $speaking, rms during the line $linePlaying")
            assertTrue("speech rms $speaking", speaking > 1_000)
            assertTrue("the line is silent: rms $linePlaying", linePlaying < 50)

            // The in-app player, on the same cached narration (the export filled the store), lays out the
            // same timeline and draws the same frame at the same instant.
            val player = VideoPlayerController(context, script, scope, provider)
            assertEquals(total, player.uiState.value.totalDurationMs)
            val at = lineStart + line.stepMs + 600L // 1... e5 sliding has finished, resting
            player.seekToMs(at)
            val shown = (player.uiState.value.instruction as RenderInstruction.Board).spec
            val expected = (SegmentFrameBuilder.build(script, key, at - keyStart, BoardOrientation.WHITE_DOWN, labels, speechMs = speech1) as RenderInstruction.Board).spec
            // The labels hold lambdas (compared by identity), so the specs are compared with one set of labels.
            val english = BoardFrameRenderer.PanelLabels.ENGLISH
            assertEquals(expected.copy(labels = english), shown.copy(labels = english))
            assertEquals("e5", shown.san)
            assertEquals(context.getString(R.string.panel_best_line), shown.excursionLabel)
            assertTrue(shown.excursionActive)
            assertNull("no verdict on a line move", BoardFrameRenderer.panelChip(shown))
            player.release()
        } finally {
            scope.cancel()
            out.delete()
        }
    }
}
