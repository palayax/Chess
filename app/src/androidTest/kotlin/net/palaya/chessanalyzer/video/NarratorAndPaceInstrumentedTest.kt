package net.palaya.chessanalyzer.video

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 and V3 on the device, through the real settings stores and the app's own view model:
 *
 *  - the narrator voice picked in Settings reaches Kokoro (the provider the app builds carries the sid,
 *    the audio it makes is not the default speaker's, and the two never share a narration cache entry);
 *  - the pace picked in Settings reaches the MP4 exporter (the same moment exported at Relaxed and at
 *    Brisk differs by exactly the pace time the script carries).
 *
 * Instrumented tests share one DataStore (CLAUDE.md): every value written here is put back.
 */
@RunWith(AndroidJUnit4::class)
class NarratorAndPaceInstrumentedTest {

    private val app get() = TestApp.app

    private fun viewModel(): AnalysisViewModel {
        lateinit var vm: AnalysisViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AnalysisViewModel(app) }
        return vm
    }

    private fun oneSegmentScript(text: String): VideoScript {
        val seg = ScriptSegment(0, SegmentKind.NORMAL_MOVE, null, text, "", BoardDirective.Hold(Position.STANDARD_START_FEN), estimatedSpeechMs = 3_000)
        return VideoScript("t", "s", listOf(seg), emptyList(), 3_000, null)
    }

    private fun rms(file: File): Double {
        val pcm = WavUtil.readAsMono16(file, 24_000)
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return kotlin.math.sqrt(sum / pcm.size)
    }

    @Test
    fun theChosenSpeakerReachesSynthesisAndNeverSharesACacheEntry(): Unit = runBlocking {
        TestApp.ensureSetUp()
        val repo = app.narrationSettingsRepository
        val store = NarrationStore.forDirectory(File(app.cacheDir, "v1_speaker_store").apply { deleteRecursively() })
        val work = File(app.cacheDir, "v1_speaker_work").apply { deleteRecursively(); mkdirs() }
        try {
            repo.clearProviderChoiceForTesting() // the natural voice, the default
            assertTrue(repo.setSpeakerId(9)) // George, British male
            val vm = viewModel()
            withTimeout(10_000) { vm.narrationVoiceSettings.first { it.speakerId == 9 } }

            // The provider the app builds for the player and the exporter carries the speaker.
            val george = vm.buildNarrationProvider() as NeuralTtsProvider
            assertTrue(george.cacheFingerprint, george.cacheFingerprint.contains("/sid9/"))
            // The script is estimated at George's measured rate.
            assertEquals(KokoroVoices.SPEAKERS[9].measuredWpm, vm.narrationOptionsForCurrentVoice().speechWpm)

            val script = oneSegmentScript(VoiceSamplePlayer.SAMPLE_TEXT)
            val g = NarrationCoordinator(george, DeviceTtsProvider(app), store).synthesizeAll(script, File(work, "g"))
            assertEquals("George narrated it, no fallback", 1, g.primaryUsedCount)
            assertEquals(0, g.fallbackCount)
            val afterGeorge = store.dir.listFiles().orEmpty().map { it.name }.toSet()
            assertTrue(afterGeorge.isNotEmpty())

            // The default speaker, the same text, the same store: nothing of George's is reused.
            val bella = NeuralTtsProvider(NeuralVoiceTier.KOKORO, app.voiceStore.modelDir, voiceVersionId = app.voiceStore.installedVersionId())
            val b = NarrationCoordinator(bella, DeviceTtsProvider(app), store).synthesizeAll(script, File(work, "b"))
            assertEquals(1, b.primaryUsedCount)
            assertEquals("no cache hit across speakers", 0, b.cacheHits)
            assertEquals(0, b.sentenceCacheHits)
            val afterBella = store.dir.listFiles().orEmpty().map { it.name }.toSet()
            assertTrue("Bella's clips are new files", (afterBella - afterGeorge).size >= afterGeorge.size)

            val gw = (g.results.single() as NarrationSynthesizer.Result.Synthesized).wavFile
            val bw = (b.results.single() as NarrationSynthesizer.Result.Synthesized).wavFile
            assertTrue("the two speakers made different audio", !gw.readBytes().contentEquals(bw.readBytes()))
            assertTrue("George is audible: ${rms(gw)}", rms(gw) > NeuralVoiceTrial.MIN_RMS)
            assertTrue("Bella is audible: ${rms(bw)}", rms(bw) > NeuralVoiceTrial.MIN_RMS)

            // A second George run is served from the cache: the switch back finds George's own audio.
            val g2 = NarrationCoordinator(vm.buildNarrationProvider()!!, DeviceTtsProvider(app), store).synthesizeAll(script, File(work, "g2"))
            assertEquals(1, g2.cacheHits)
            assertTrue(gw.readBytes().contentEquals((g2.results.single() as NarrationSynthesizer.Result.Synthesized).wavFile.readBytes()))
        } finally {
            repo.clearSpeakerForTesting()
            repo.clearProviderChoiceForTesting()
            store.dir.deleteRecursively()
            work.deleteRecursively()
        }
    }

    @Test
    fun theVoiceSamplePlayerSynthesizesEachSpeakerOnceAndCachesIt(): Unit = runBlocking {
        TestApp.ensureSetUp()
        val dir = File(app.cacheDir, "v1_samples").apply { deleteRecursively() }
        val player = VoiceSamplePlayer(dir, { app.voiceStore.modelDir }, { app.voiceStore.installedVersionId() }, this)
        try {
            for (sid in listOf(1, 7)) {
                player.play(sid)
                withTimeout(120_000) { player.state.first { it is VoiceSamplePlayer.State.Playing || it is VoiceSamplePlayer.State.Failed } }
                assertEquals(VoiceSamplePlayer.State.Playing(sid), player.state.value)
                val file = player.sampleFile(app.voiceStore.installedVersionId()!!, sid)
                assertTrue(file.isFile)
                assertTrue("sample $sid is audible", rms(file) > NeuralVoiceTrial.MIN_RMS)
            }
            // Starting another stopped the first: only one sample plays.
            assertEquals(VoiceSamplePlayer.State.Playing(7), player.state.value)
            val cached = player.sampleFile(app.voiceStore.installedVersionId()!!, 1)
            val modified = cached.lastModified()
            player.play(1)
            withTimeout(10_000) { player.state.first { it is VoiceSamplePlayer.State.Playing } }
            assertEquals("the cached sample is played, not made again", modified, cached.lastModified())
            player.stop()
            assertEquals(VoiceSamplePlayer.State.Idle, player.state.value)
        } finally {
            player.release()
            dir.deleteRecursively()
        }
    }

    /**
     * The scholar's mate with engine numbers made up for it (mate in one allowed by 3...Nf6, played by
     * 4.Qxf7#): enough for the generator to make 4.Qxf7# a key move with a lead-in.
     */
    private fun scholarsMateScript(pace: VideoPace, wpm: Int): VideoScript {
        val game = PgnParser.parse("[White \"W\"]\n[Black \"B\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0\n").single()
        val best = listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g7g6", "h5f7")
        val scores = listOf(30, -30, 30, -30, 30, -20)
        var pos = Position.startPosition()
        val evals = ArrayList<PositionEval>()
        for (i in 0..game.moves.size) {
            val line = when {
                i < 6 -> EngineLineInput(1, scores[i], null, 12, listOf(best[i]))
                i == 6 -> EngineLineInput(1, null, 1, 12, listOf(best[i]))
                else -> EngineLineInput(1, null, 0, 12, emptyList())
            }
            evals.add(PositionEval(pos.toFen(), listOf(line), 12))
            if (i < game.moves.size) pos = pos.makeMove(pos.parseSan(game.moves[i].san))
        }
        val see = StaticExchangeEvaluator()
        val report = GameAnalyzer(MoveClassifier(see), MotifDetector(see)).analyze(game, evals, null, null)
        return VideoScriptGenerator(null).generate(report, game, net.palaya.chessanalyzer.core.narration.NarrationOptions(speechWpm = wpm, pace = pace))
    }

    /** The key move's beat and the one before it, re-indexed: a short export around one key moment. */
    private fun aroundTheMate(script: VideoScript): VideoScript {
        val key = script.segments.indexOfFirst { (it.board as? BoardDirective.PlayMove)?.san == "Qxf7#" }
        assertTrue("the mate has its beat", key > 0)
        val segs = script.segments.subList(key - 1, key + 1).mapIndexed { i, s -> s.copy(index = i) }
        return script.copy(segments = segs, chapters = emptyList(), recap = null, totalEstimatedMs = segs.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs })
    }

    @Test
    fun thePaceSetInSettingsReachesTheExportedVideo(): Unit = runBlocking {
        val repo = app.settingsRepository
        val vm = viewModel()
        val durations = HashMap<VideoPace, Long>()
        val pacing = HashMap<VideoPace, Long>()
        try {
            for (pace in listOf(VideoPace.RELAXED, VideoPace.BRISK)) {
                repo.setVideoPace(pace)
                val options = vm.narrationOptionsForCurrentVoice()
                assertEquals(pace, options.pace)
                val script = aroundTheMate(scholarsMateScript(options.pace, options.speechWpm))
                val mate = script.segments.last()
                val lead = assertNotNull(mate.leadIn).let { mate.leadIn!! }
                assertEquals(pace.keyLeadInMs, lead.pauseMs)
                pacing[pace] = script.segments.sumOf { it.leadInMs } + mate.holdAfterMs

                val exporter = VideoExporter(app)
                val out = exporter.export(script, "v3_pace_${pace.name.lowercase()}", null)
                val done = exporter.state.value as VideoExporter.State.Completed
                durations[pace] = done.durationMs
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(out.absolutePath)
                val container = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                retriever.release()
                assertTrue("container $container vs timeline ${done.durationMs}", kotlin.math.abs(container - done.durationMs) <= 200)
                out.delete()
            }
            // The same words, so the same speech: the two videos differ by the pace time and nothing else.
            val expected = pacing.getValue(VideoPace.RELAXED) - pacing.getValue(VideoPace.BRISK)
            val actual = durations.getValue(VideoPace.RELAXED) - durations.getValue(VideoPace.BRISK)
            assertTrue("Relaxed - Brisk = $actual ms, the script's pace time differs by $expected ms", kotlin.math.abs(actual - expected) <= 100)
            assertTrue(actual > 0)
        } finally {
            repo.setVideoPace(VideoPace.DEFAULT)
        }
    }
}
