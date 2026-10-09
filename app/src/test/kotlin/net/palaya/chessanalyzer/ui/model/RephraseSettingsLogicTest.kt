package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.data.models.DownloadState
import net.palaya.chessanalyzer.data.models.FailureReason
import net.palaya.chessanalyzer.data.models.FileProgress
import net.palaya.chessanalyzer.data.models.ModelFile
import net.palaya.chessanalyzer.data.models.SetupProgress
import net.palaya.chessanalyzer.data.models.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** C2 (design §7, owner decisions §12): which Settings row the wording model shows. */
class RephraseSettingsLogicTest {

    private val size = 1_117_320_736L

    private fun view(
        available: Boolean = true, installed: Boolean = false, wanted: Boolean = false, part: Long = 0,
        enabled: Boolean = false, running: Boolean = false, progress: SetupProgress? = null,
    ) = rephraseRowView(available, installed, wanted, part, size, enabled, running, progress)

    private fun progress(status: SetupStatus, done: Long, failure: FailureReason? = null) = SetupProgress(
        overallFraction = done.toFloat() / size, bytesDone = done, bytesTotal = size,
        perFile = listOf(FileProgress(ModelFile.REPHRASE, done, size, DownloadState.Idle)), status = status, failure = failure,
    )

    @Test
    fun aPhoneThatCannotRunItSaysSoWhateverIsOnDisk() {
        assertEquals(RephraseRow.UNAVAILABLE, view(available = false, installed = true, enabled = true).row)
    }

    @Test
    fun notInstalledOffersTheDownloadAndTheSwitchIsOff() {
        val v = view()
        assertEquals(RephraseRow.NOT_INSTALLED, v.row)
        assertEquals(false, v.enabled)
    }

    @Test
    fun aRunningDownloadShowsItsBytes() {
        val v = view(wanted = true, running = true, progress = progress(SetupStatus.DOWNLOADING, 300_000_000))
        assertEquals(RephraseRow.DOWNLOADING, v.row)
        assertEquals(300_000_000L, v.bytesDone)
        assertEquals(26, v.percent)
    }

    @Test
    fun aPartOnDiskWithNoRunIsPausedAndAFailureSaysWhy() {
        assertEquals(RephraseRow.PAUSED, view(wanted = true, part = 500_000_000).row)
        val failed = view(wanted = true, part = 0, progress = progress(SetupStatus.FAILED, 0, FailureReason.DAMAGED))
        assertEquals(RephraseRow.FAILED, failed.row)
        assertEquals(FailureReason.DAMAGED, failed.failure)
        // A part left from a cancelled request is not "paused" (Cancel withdrew the request).
        assertEquals(RephraseRow.NOT_INSTALLED, view(wanted = false, part = 500_000_000).row)
    }

    @Test
    fun installedShowsTheSwitchInTheSettingsState() {
        assertEquals(RephraseRowView(RephraseRow.INSTALLED, true, size, size), view(installed = true, enabled = true))
        assertEquals(false, view(installed = true, enabled = false).enabled)
    }

    @Test
    fun sizesOfAGigabyteAndMoreAreRoundedUpToATenth() {
        assertEquals("‎1.2 GB‎", gigabytesLabel(size))
        assertEquals("‎1.0 GB‎", gigabytesLabel(1_000_000_000L))
        assertEquals("‎680 MB‎", gigabytesLabel(675_710_816L))
    }
}
