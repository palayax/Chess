package net.palaya.chessanalyzer.ui.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.video.DeviceTtsProvider
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.WavUtil
import net.palaya.chessanalyzer.video.narrationCacheFingerprint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves GAP 1 of the "pre-calculate narration" work is real: live in-app playback
 * ([VideoPlayerController]) prefers a pre-generated audio file over the device TTS voice when one
 * is cached, and only falls back to TTS when nothing is cached — never the other way around, and
 * never silence either way. [PlayerUiState.narrationSource] is the direct, unambiguous signal:
 * since [VideoPlayerController.playOrSpeakCurrentIfNeeded] sets it to exactly one of
 * [NarrationSource.FILE] or [NarrationSource.TTS] per segment and never both, observing `FILE`
 * is proof the `TextToSpeech.speak()` branch was never reached for that segment.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlayerControllerInstrumentedTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun oneSegmentScript(narration: String): VideoScript {
        val segment = ScriptSegment(
            index = 0,
            kind = SegmentKind.INTRO,
            ply = null,
            narration = narration,
            caption = "Test",
            board = BoardDirective.Hold(startFen),
            estimatedSpeechMs = 1200,
        )
        return VideoScript(
            title = "Playback test",
            subtitle = "unit",
            segments = listOf(segment),
            chapters = emptyList(),
            totalEstimatedMs = 1200,
            userColor = Color.WHITE,
        )
    }

    @Test
    fun playbackUsesPreGeneratedFileAndNeverInvokesTts(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val narrationText = "This sentence has a pre-generated audio file ready to play."
        val script = oneSegmentScript(narrationText)

        // VideoPlayerController(narrationProvider = null) uses a DeviceTtsProvider identity for
        // cache lookups — mirror that exactly so the key this test writes is the key playback reads.
        val store = NarrationStore.forApp(context)
        val identity = DeviceTtsProvider(context)
        val key = store.keyFor(narrationText, identity.displayName, identity.narrationCacheFingerprint())
        val cachedFile = store.fileFor(key)
        WavUtil.writeSilentWav(cachedFile, 1500, 22050)

        val testScope = CoroutineScope(Dispatchers.Main + Job())
        val controller = VideoPlayerController(context, script, testScope)
        try {
            // Pre-generated audio is available from construction (it's a synchronous filesystem
            // lookup) so this does not need to wait for TTS's async init the way the fallback test
            // below does.
            controller.play()
            withTimeout(8_000) {
                while (controller.uiState.value.narrationSource == NarrationSource.NONE) delay(50)
            }
            assertEquals(
                "a segment with a pre-generated cache file must play from the FILE, never from TTS",
                NarrationSource.FILE,
                controller.uiState.value.narrationSource,
            )
        } finally {
            controller.release()
            testScope.cancel()
            cachedFile.delete()
        }
    }

    @Test
    fun playbackFallsBackToTtsWhenNoFileIsCached(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Unique text (never written to the store by any test) so this can never accidentally hit
        // a leftover cache entry from another run.
        val narrationText = "No pre-generated audio exists anywhere for this exact sentence, marker ${System.nanoTime()}."
        val script = oneSegmentScript(narrationText)

        val testScope = CoroutineScope(Dispatchers.Main + Job())
        val controller = VideoPlayerController(context, script, testScope)
        try {
            // Nothing is cached here, so narrationAvailable only becomes true once the device TTS
            // engine finishes its async init — wait for it rather than racing play() against it.
            // 30 s, not 8: the Google TTS service is a separate process that the system may have
            // reclaimed (the bundled-model tests before this one hold ~260 MB of freshly written
            // files), and its cold start on the emulator was measured at 5-6 s, which with the
            // test's own setup overran 8 s once in the full suite (and never in isolation).
            withTimeout(30_000) {
                while (!controller.uiState.value.narrationAvailable) delay(50)
            }
            controller.play()
            withTimeout(8_000) {
                while (controller.uiState.value.narrationSource == NarrationSource.NONE) delay(50)
            }
            assertEquals(
                "with nothing cached, playback must fall back to live TTS rather than staying silent",
                NarrationSource.TTS,
                controller.uiState.value.narrationSource,
            )
        } finally {
            controller.release()
            testScope.cancel()
        }
    }
}
