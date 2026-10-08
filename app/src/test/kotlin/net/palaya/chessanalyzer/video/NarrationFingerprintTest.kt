package net.palaya.chessanalyzer.video

import java.io.File
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** D2e: the narration cache key carries the installed voice's id, so a voice update can never reuse old WAVs. */
class NarrationFingerprintTest {

    private fun provider(id: String?) = NeuralTtsProvider(NeuralVoiceTier.KOKORO, File("unused"), voiceVersionId = id)

    @Test
    fun theVoiceIdIsPartOfTheFingerprintAndOfTheCacheKey() {
        val a = provider("7190c4801645")
        val b = provider("0123456789ab")
        assertEquals(
            "KOKORO@7190c4801645/sid${NeuralVoiceTier.KOKORO.speakerId}/ls${"%.2f".format(java.util.Locale.ROOT, NeuralVoiceTier.KOKORO.lengthScale)}/pr${SpokenRespelling.tableId}",
            a.cacheFingerprint,
        )
        assertNotEquals(a.cacheFingerprint, b.cacheFingerprint)
        val store = NarrationStore.forDirectory(File(System.getProperty("java.io.tmpdir"), "narration-fp-test"))
        try {
            assertNotEquals(
                store.keyFor("White to play.", a.displayName, a.narrationCacheFingerprint()),
                store.keyFor("White to play.", b.displayName, b.narrationCacheFingerprint()),
            )
            assertEquals("KOKORO@unknown", provider(null).cacheFingerprint.substringBefore('/'))
        } finally {
            store.dir.deleteRecursively()
        }
    }
}
