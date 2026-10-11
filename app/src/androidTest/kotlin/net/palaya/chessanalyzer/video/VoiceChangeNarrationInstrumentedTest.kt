package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.narrationNeedsPreparation
import net.palaya.chessanalyzer.ui.video.NarrationSource
import net.palaya.chessanalyzer.ui.video.VideoPlayerController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RF, the owner's item 3 ("Change voice for analyzed games not works: can choose a different voice but the old voice
 * narration stays"). The cause, confirmed in code: the in-app player ([VideoPlayerController]) never synthesises. It
 * plays the WAVs already in [NarrationStore] for its voice's identity and speaks every other segment with the live
 * device voice, and nothing recorded the newly picked voice, so the narration heard after a voice change was never that
 * voice.
 *
 * This test drives the fixed path with two distinguishable voices (fakes that write WAVs of different lengths, so the
 * player's timeline shows which voice's files it plays):
 *  1. the review recorded voice A ([NarrationPreparer], the processing run's "Recording the narration voice" stage);
 *  2. the user picks voice B: B has every segment missing, [narrationNeedsPreparation] says so, and a player for B
 *     before the recording falls back to the device voice (TTS): the old bug's behaviour;
 *  3. the inline preparation records B; nothing is missing any more, and a player for B plays its narration from the
 *     FILES of voice B (the timeline's length is B's recordings, not A's).
 */
@RunWith(AndroidJUnit4::class)
class VoiceChangeNarrationInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = NarrationStore.forApp(context)
    private val marker = System.nanoTime()
    private val written = ArrayList<File>()

    /** A voice that "speaks" every sentence as [msPerSentence] of silence; its identity is its name. */
    private inner class FakeVoice(name: String, private val msPerSentence: Int) : NarrationVoiceProvider {
        override val displayName: String = "Test voice $name $marker"
        var calls = 0
        override suspend fun prepare(): Boolean = true
        override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
            calls++
            outFile.parentFile?.mkdirs()
            WavUtil.writeSilentWav(outFile, msPerSentence.toLong(), 22050)
            return SynthesisResult.Success(outFile, msPerSentence.toLong())
        }
        override fun release() = Unit
    }

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun script(): VideoScript {
        val segments = (0 until 3).map { i ->
            ScriptSegment(
                index = i,
                kind = SegmentKind.INTRO,
                ply = null,
                narration = "Segment $i of the voice change test, marker $marker.",
                caption = "Test $i",
                board = BoardDirective.Hold(startFen),
                estimatedSpeechMs = 1000,
            )
        }
        return VideoScript(
            title = "Voice change test",
            subtitle = "RF",
            segments = segments,
            chapters = emptyList(),
            totalEstimatedMs = 3000,
            userColor = Color.WHITE,
        )
    }

    @After
    fun tearDown() {
        // The store is the app's own (the player reads only that one): remove every file this test made.
        val s = script()
        for (name in listOf("A", "B")) {
            for (seg in s.segments) {
                val display = "Test voice $name $marker"
                store.fileFor(store.keyFor(seg.narration, display, "default")).delete()
                for (sentence in NarrationSynthesizer.splitIntoSentences(seg.narration)) {
                    store.fileFor(store.keyFor(sentence, display, "default")).delete()
                }
            }
        }
        written.forEach { it.delete() }
    }

    private suspend fun narrationSourceOfFirstSegment(controller: VideoPlayerController): NarrationSource {
        controller.play()
        withTimeout(30_000) {
            while (controller.uiState.value.narrationSource == NarrationSource.NONE) delay(50)
        }
        return controller.uiState.value.narrationSource
    }

    @Test
    fun aNewVoiceIsRecordedAndThenThePlayerPlaysThatVoicesFiles(): Unit = runBlocking {
        val s = script()
        val preparer = NarrationPreparer(store, { DeviceTtsProvider(context) }, File(context.cacheDir, "voice_change_test"))
        val voiceA = FakeVoice("A", msPerSentence = 900)
        val voiceB = FakeVoice("B", msPerSentence = 2_100)

        // 1. The review recorded voice A.
        preparer.prepare(s, voiceA)
        assertEquals(0, preparer.missingSegments(s, voiceA))

        // 2. Voice B picked: everything is missing in B, so the inline preparation is needed.
        val missingB = preparer.missingSegments(s, voiceB)
        assertEquals(3, missingB)
        assertTrue(narrationNeedsPreparation(NarrationProviderChoice.NEURAL, voiceInstalled = true, missingSegments = missingB))
        val scope = CoroutineScope(Dispatchers.Main + Job())
        try {
            val before = withContext(Dispatchers.Main) { VideoPlayerController(context, s, scope, voiceB) }
            // Without B's recordings the player has only the device voice (TTS) for B: what the owner heard. (The
            // device TTS may be slow to start on an emulator, so NONE until then is also "not B's voice".)
            withTimeout(30_000) { while (!before.uiState.value.narrationAvailable) delay(50) }
            val sourceBefore = withContext(Dispatchers.Main) { narrationSourceOfFirstSegment(before) }
            withContext(Dispatchers.Main) { before.release() }
            assertTrue("before B is recorded nothing of B can play from a file: $sourceBefore", sourceBefore != NarrationSource.FILE)

            // 3. The inline preparation records B.
            var lastProgress = 0 to 0
            preparer.prepare(s, voiceB) { done, total -> lastProgress = done to total }
            assertEquals(3 to 3, lastProgress)
            assertEquals(0, preparer.missingSegments(s, voiceB))
            assertTrue("voice B was asked to speak", voiceB.calls >= 3)
            for (seg in s.segments) {
                assertNotNull(store.get(store.keyFor(seg.narration, voiceB.displayName, voiceB.narrationCacheFingerprint())))
            }

            val after = withContext(Dispatchers.Main) { VideoPlayerController(context, s, scope, voiceB) }
            try {
                // The timeline is built from B's recordings (3 x 2.1 s), not A's (3 x 0.9 s) nor the estimates (3 x 1 s).
                assertTrue("timeline ${after.uiState.value.totalDurationMs} ms is B's", after.uiState.value.totalDurationMs >= 3 * 2_100)
                assertEquals(NarrationSource.FILE, withContext(Dispatchers.Main) { narrationSourceOfFirstSegment(after) })
            } finally {
                withContext(Dispatchers.Main) { after.release() }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingTheRecordingKeepsWhatWasDoneAndStopsAtTheNextSegment(): Unit = runBlocking {
        val s = script()
        val preparer = NarrationPreparer(store, { DeviceTtsProvider(context) }, File(context.cacheDir, "voice_change_test"))
        val slow = object : NarrationVoiceProvider {
            override val displayName: String = "Test voice A $marker"
            override suspend fun prepare(): Boolean = true
            override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
                outFile.parentFile?.mkdirs()
                WavUtil.writeSilentWav(outFile, 500, 22050)
                delay(1_500)
                return SynthesisResult.Success(outFile, 500)
            }
            override fun release() = Unit
        }
        val job = launch(Dispatchers.Default) { preparer.prepare(s, slow) }
        withTimeout(10_000) { while (preparer.missingSegments(s, slow) == 3) delay(50) }
        job.cancel()
        job.join()
        val left = preparer.missingSegments(s, slow)
        assertTrue("a cancel keeps the recorded segments and leaves the rest ($left missing)", left in 1..2)
    }
}
