package net.palaya.chessanalyzer.video

import java.io.File
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.SegmentLeadIn
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.KOKORO_DEFAULT_SPEAKER_ID
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1 (narrator voice picker) and V3 (pace) on the app side, host-checked: the speaker list, the cache
 * key per speaker, the options a script is built with, and the timeline's lead-in.
 */
class NarratorVoiceTest {

    // ---- V1: the speakers ----

    @Test
    fun elevenSpeakersInSidOrderWithTheUpstreamNames() {
        assertEquals(KokoroVoices.EXPECTED_SPEAKER_COUNT, KokoroVoices.SPEAKERS.size)
        assertEquals((0..10).toList(), KokoroVoices.SPEAKERS.map { it.sid })
        assertEquals(
            listOf("af", "af_bella", "af_nicole", "af_sarah", "af_sky", "am_adam", "am_michael", "bf_emma", "bf_isabella", "bm_george", "bm_lewis"),
            KokoroVoices.NAMES,
        )
        // voices.bin of kokoro-int8-en-v0_19 is 5,755,904 B: 11 style tables of 523,264 B (511 x 256 float32;
        // measured on the file, V1: the brief said 522,240 B = 510 rows, which is 11,264 B short of the file).
        assertEquals(5_755_904L, KokoroVoices.EXPECTED_SPEAKER_COUNT * 511L * 256L * 4L)
    }

    @Test
    fun accentAndGenderFollowUpstreamPrefixes() {
        for (s in KokoroVoices.SPEAKERS) {
            val prefix = s.upstreamName.take(2)
            assertEquals(s.upstreamName, if (prefix[0] == 'a') KokoroVoices.Accent.AMERICAN else KokoroVoices.Accent.BRITISH, s.accent)
            assertEquals(s.upstreamName, if (prefix[1] == 'f') KokoroVoices.Gender.FEMALE else KokoroVoices.Gender.MALE, s.gender)
        }
        // At least two of each accent and gender, mixed: the picker really offers a choice.
        val groups = KokoroVoices.SPEAKERS.groupBy { it.accent to it.gender }.mapValues { it.value.size }
        assertEquals(4, groups.size)
        assertTrue(groups.values.all { it >= 2 })
    }

    @Test
    fun thePickerShowsEverySpeakerOnceAndTheDefaultFirst() {
        assertEquals((0..10).toSet(), KokoroVoices.PICKER_ORDER.toSet())
        assertEquals(11, KokoroVoices.PICKER_ORDER.size)
        assertEquals(KOKORO_DEFAULT_SPEAKER_ID, KokoroVoices.PICKER_ORDER.first())
        assertEquals(1, KOKORO_DEFAULT_SPEAKER_ID)
        assertEquals(KOKORO_DEFAULT_SPEAKER_ID, NeuralVoiceTier.KOKORO.speakerId)
        assertEquals(KOKORO_DEFAULT_SPEAKER_ID, NarrationVoiceSettings().speakerId)
        // The blend has no name of its own; every other voice does.
        assertEquals(listOf(0), KokoroVoices.SPEAKERS.filter { it.displayName == null }.map { it.sid })
    }

    @Test
    fun theMeasuredRatesAreTheOnesInTheVoiceSampleMeasurements() {
        val file = locate("docs/voice_samples/measurements.txt")
        val measured = file.readLines().mapNotNull { line ->
            val m = Regex("""^kokoro_(\d\d)_\S+\s.*effectiveWpm=\s*(\d+)""").find(line) ?: return@mapNotNull null
            m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }.toMap()
        assertEquals(11, measured.size)
        for (s in KokoroVoices.SPEAKERS) assertEquals(s.upstreamName, measured.getValue(s.sid), s.measuredWpm)
        // The default speaker's rate is the tier's.
        assertEquals(NeuralVoiceTier.KOKORO.measuredWpm, KokoroVoices.SPEAKERS[KOKORO_DEFAULT_SPEAKER_ID].measuredWpm)
    }

    @Test
    fun unknownIdsAreRejected() {
        assertFalse(KokoroVoices.isKnown(-1))
        assertFalse(KokoroVoices.isKnown(11))
        assertTrue(KokoroVoices.isKnown(0))
        assertEquals("sid42", KokoroVoices.nameFor(42))
    }

    // ---- V1: the cache key per speaker ----

    @Test
    fun everySpeakerHasItsOwnFingerprintAndCacheKey() {
        val store = NarrationStore.forDirectory(File(System.getProperty("java.io.tmpdir"), "narration-speaker-test"))
        try {
            val providers = KokoroVoices.SPEAKERS.map {
                NeuralTtsProvider(NeuralVoiceTier.KOKORO, File("unused"), speakerId = it.sid, voiceVersionId = "7190c4801645")
            }
            assertEquals(11, providers.map { it.cacheFingerprint }.toSet().size)
            assertEquals(11, providers.map { store.keyFor("White to play.", it.displayName, it.narrationCacheFingerprint()) }.toSet().size)
            assertTrue(providers[9].cacheFingerprint.contains("/sid9/"))
            // The default constructor (the update trial's) is the default speaker.
            assertEquals(providers[KOKORO_DEFAULT_SPEAKER_ID].cacheFingerprint,
                NeuralTtsProvider(NeuralVoiceTier.KOKORO, File("unused"), voiceVersionId = "7190c4801645").cacheFingerprint)
        } finally {
            store.dir.deleteRecursively()
        }
    }

    @Test
    fun theSelectionCarriesTheChosenSpeaker() {
        val s = NarrationVoiceSettings(speakerId = 9)
        assertEquals(NarrationProviderSelection.Neural(NeuralVoiceTier.KOKORO, 9), selectNarrationProvider(s) { true })
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s) { false })
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s.copy(provider = NarrationProviderChoice.DEVICE)) { true })
    }

    // ---- V1 + V3: the options a script is built with ----

    @Test
    fun theScriptIsEstimatedAtTheChosenSpeakersRateAndBuiltAtTheChosenPace() {
        val nicole = NarrationVoiceSettings(speakerId = 2)
        val o = narrationOptionsFor(nicole, voiceInstalled = true, EngineSettings(videoPace = VideoPace.BRISK, narrationThresholdCp = 100))
        assertEquals(127, o.speechWpm)
        assertEquals(VideoPace.BRISK, o.pace)
        assertEquals(100, o.significanceThresholdCp)
        // Not installed, or the phone's voice chosen: the device voice's rate.
        assertEquals(NarrationOptions().speechWpm, narrationOptionsFor(nicole, false, EngineSettings()).speechWpm)
        assertEquals(
            NarrationOptions().speechWpm,
            narrationOptionsFor(nicole.copy(provider = NarrationProviderChoice.DEVICE), true, EngineSettings()).speechWpm,
        )
        // The app's default pace is Relaxed.
        assertEquals(VideoPace.RELAXED, narrationOptionsFor(NarrationVoiceSettings(), true, EngineSettings()).pace)
        assertEquals(VideoPace.RELAXED, EngineSettings().videoPace)
    }

    // ---- V3: the timeline ----

    @Test
    fun theTimelinePutsTheLeadInBeforeTheSpeech() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val lead = SegmentLeadIn(fen = fen, approachUci = listOf("e2e4", "e7e5"), approachSan = listOf("e4", "e5"), stepMs = 1_500, pauseMs = 1_200)
        val segs = listOf(
            ScriptSegment(0, SegmentKind.INTRO, null, "Hello.", "", BoardDirective.Card("t", emptyList()), estimatedSpeechMs = 2_000),
            ScriptSegment(1, SegmentKind.FOUND_TACTIC, 3, "Knight to f three.", "", BoardDirective.PlayMove(fen, "g1f3", "Nf3"),
                estimatedSpeechMs = 3_000, holdAfterMs = 1_000, leadIn = lead),
        )
        val script = VideoScript("t", "s", segs, emptyList(), segs.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, null)
        val tl = TimelineBuilder.build(script, listOf(NarrationSynthesizer.Result.Synthesized(1, File("x.wav"), 2_500)))
        val key = tl.segments[1]
        assertEquals(4_200L, lead.durationMs)
        assertEquals(4_200L, key.leadInMs)
        assertEquals(2_250L, key.startMs)
        assertEquals(key.startMs + 4_200L, key.speechStartMs)
        // lead-in + real speech + hold + gap
        assertEquals(4_200L + 2_500L + 1_000L + TimelineBuilder.INTER_SEGMENT_GAP_MS, key.totalDurationMs)
        assertEquals(0L, tl.segments[0].leadInMs)
        assertEquals(tl.segments[0].startMs, tl.segments[0].speechStartMs)
        assertNotEquals(key.startMs, key.speechStartMs)
    }

    private fun locate(relative: String): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate $relative")
    }
}
