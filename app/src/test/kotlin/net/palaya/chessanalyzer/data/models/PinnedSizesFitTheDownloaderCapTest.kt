package net.palaya.chessanalyzer.data.models

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every file the app pins in this module (the net's pin is internal to :engine and is ~100 MB) must fit under [ModelDownloader.MAX_FILE_BYTES], which refuses a larger pin before any
 * request. The 1 GiB cap of D2a refused the 1.1 GB wording model on the owner's Pixel 8 (2026-10-11); the emulators
 * never reached that download (RAM gate), so this host test is the guard.
 */
class PinnedSizesFitTheDownloaderCapTest {

    @Test
    fun everyCompiledPinFitsTheCap() {
        val pins = mapOf(
            "voice download" to GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES,
            "wording model" to GeneratedModelPins.REPHRASE_MODEL_SIZE_BYTES,
        )
        for ((name, size) in pins) {
            assertTrue("$name: $size bytes > cap ${ModelDownloader.MAX_FILE_BYTES}", size in 1..ModelDownloader.MAX_FILE_BYTES)
        }
    }

    @Test
    fun theCapAgreesWithTheUpdateLimitForTheWordingModel() {
        assertTrue(SizeLimits.DEFAULT.rephraseMax <= ModelDownloader.MAX_FILE_BYTES)
    }
}
