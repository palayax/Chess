package net.palaya.chessanalyzer.video

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RF: the coordinator says which segments the primary voice could not record (they were read by the fallback voice), so
 * the app can remember them and not load the primary voice again for the same failure after a restart; the preparer turns
 * that into store keys that no longer count as missing.
 */
class NarrationFallbackIndexesTest {

    private val dir: File = Files.createTempDirectory("fallback-indexes-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeWav(file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(WavUtil.buildWavHeader(3_200, 16_000, channels = 1, bitsPerSample = 16))
            out.write(ByteArray(3_200))
        }
    }

    /** Records every sentence except the ones that contain "BAD". */
    private class FakePrimary(private val write: (File) -> Unit) : NarrationVoiceProvider {
        override val displayName = "Primary"
        override suspend fun prepare() = true
        override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
            if ("BAD" in text) return SynthesisResult.Failure("cannot say it")
            write(outFile)
            return SynthesisResult.Success(outFile, 100L)
        }
        override fun release() = Unit
    }

    private class FakeDevice(private val write: (File) -> Unit) : NarrationVoiceProvider {
        override val displayName = "Device"
        override suspend fun prepare() = true
        override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
            write(outFile)
            return SynthesisResult.Success(outFile, 100L)
        }
        override fun release() = Unit
    }

    private fun script(vararg texts: String): VideoScript {
        val segments = texts.mapIndexed { i, text ->
            ScriptSegment(i, SegmentKind.INTRO, null, text, "", BoardDirective.Card("t", emptyList()), estimatedSpeechMs = 1_000)
        }
        return VideoScript("t", "s", segments, emptyList(), segments.sumOf { it.estimatedSpeechMs }, null)
    }

    @Test
    fun theSegmentsTheFallbackReadAreReportedAndBecomeKnownFallbacks() = runBlocking {
        val store = NarrationStore.forDirectory(File(dir, "store"))
        val primary = FakePrimary(::writeWav)
        val script = script("Good one.", "BAD one.", "", "Another good one.")
        val coordinator = NarrationCoordinator(primary, FakeDevice(::writeWav), store)
        val outcome = coordinator.synthesizeAll(script, File(dir, "work"))
        assertEquals(listOf(1), outcome.fallbackSegmentIndexes)
        assertEquals(1, outcome.fallbackCount)
        assertEquals(2, outcome.primaryUsedCount)

        val preparer = NarrationPreparer(store, { FakeDevice(::writeWav) }, File(dir, "prep"))
        // Two spoken segments are recorded; the failed one is missing until it is a known fallback.
        assertEquals(1, preparer.missingSegments(script, primary))
        val fallback = preparer.fallbackKeys(script, primary, outcome)
        assertEquals(1, fallback.size)
        assertTrue(fallback.single() in preparer.segmentKeys(script, primary))
        assertEquals(0, preparer.missingSegments(script, primary, fallback))
    }

    @Test
    fun aCleanRecordingReportsNoFallbacks() = runBlocking {
        val store = NarrationStore.forDirectory(File(dir, "store"))
        val outcome = NarrationCoordinator(FakePrimary(::writeWav), FakeDevice(::writeWav), store)
            .synthesizeAll(script("One.", "Two."), File(dir, "work"))
        assertTrue(outcome.fallbackSegmentIndexes.isEmpty())
        assertEquals(0, outcome.fallbackCount)
    }
}
