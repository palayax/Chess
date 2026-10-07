package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.data.models.ManifestEntry
import net.palaya.chessanalyzer.data.models.ModelCompat
import net.palaya.chessanalyzer.data.models.ModelKind
import net.palaya.chessanalyzer.data.models.ModelRuntime
import net.palaya.chessanalyzer.data.models.PauseReason
import net.palaya.chessanalyzer.data.models.UpdateCheckResult
import net.palaya.chessanalyzer.data.models.UpdateFailure
import net.palaya.chessanalyzer.data.models.UpdateInstallOutcome
import net.palaya.chessanalyzer.data.models.UpdateOffer
import net.palaya.chessanalyzer.data.models.UpdatePhase
import net.palaya.chessanalyzer.data.models.UpdateProgress
import net.palaya.chessanalyzer.data.models.UpdateUiState
import net.palaya.chessanalyzer.video.NeuralVoiceTrial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings row and the update sheet's states and copy (D2e; design §1.8). */
class UpdateLogicTest {

    private val voice = UpdateOffer(
        ManifestEntry(
            ModelKind.VOICE, "Narration voice", "v0_19-r2", "k.tar", "https://x/t/k.tar", 158_279_680, "a".repeat(64), 1, null,
            ModelCompat.SherpaKokoro("kokoro-v0_19"), ModelRuntime("sherpa-onnx", "1.13.8", "1.13.8"),
        ),
    )
    private val net = UpdateOffer(
        ManifestEntry(
            ModelKind.NET, "Chess engine data", "nn-abcdef012345", "nn-abcdef012345.nnue", "https://x/t/nn-abcdef012345.nnue",
            98_511_183, "abcdef012345" + "b".repeat(52), 1, null, ModelCompat.StockfishNnue(0x6a448afaL, 0xa85b2205L, null), null,
        ),
    )

    @Test
    fun theRowIsBlockedByAnAnalysisThenAnExportThenSetup() {
        assertEquals(UpdateBlock.ANALYSIS, updateBlock(analysisRunning = true, exportRunning = true, setupRunning = true, setupComplete = false))
        assertEquals(UpdateBlock.EXPORT, updateBlock(false, true, true, false))
        assertEquals(UpdateBlock.SETUP, updateBlock(false, false, true, true))
        assertEquals(UpdateBlock.SETUP, updateBlock(false, false, false, false))
        assertNull(updateBlock(false, false, false, true))
        // The row asks with setupComplete = true: an unfinished setup does not block a check, a running one does.
        assertNull(updateBlock(false, false, setupRunning = false, setupComplete = true))
        assertEquals(UpdateBlock.SETUP, updateBlock(false, false, setupRunning = true, setupComplete = true))
    }

    @Test
    fun theRowSaysCheckingInstallingBlockedOrLastChecked() {
        assertEquals(UpdateRowLine.LAST_CHECKED, updateRowLine(UpdateUiState.Idle, null))
        assertEquals(UpdateRowLine.CHECKING, updateRowLine(UpdateUiState.Checking, UpdateBlock.ANALYSIS))
        assertEquals(UpdateRowLine.BLOCKED, updateRowLine(UpdateUiState.Idle, UpdateBlock.EXPORT))
        val installing = UpdateUiState.Installing(voice, UpdateProgress(ModelKind.VOICE, UpdatePhase.DOWNLOADING, 1, 2), emptyList())
        assertEquals(UpdateRowLine.INSTALLING, updateRowLine(installing, null))
    }

    @Test
    fun everyCheckResultHasItsLine() {
        fun line(r: UpdateCheckResult) = updateSheetView(UpdateUiState.Checked(r, (r as? UpdateCheckResult.Available)?.offers.orEmpty()), null).line
        assertEquals(UpdateLine.CHECKING, updateSheetView(UpdateUiState.Checking, null).line)
        assertEquals(UpdateLine.UP_TO_DATE, line(UpdateCheckResult.UpToDate(emptyList())))
        assertEquals(UpdateLine.AVAILABLE, line(UpdateCheckResult.Available(listOf(voice), emptyList())))
        assertEquals(UpdateLine.NO_INTERNET, line(UpdateCheckResult.NoInternet))
        assertEquals(UpdateLine.SERVER_UNAVAILABLE, line(UpdateCheckResult.ServerUnavailable("x")))
        assertEquals(UpdateLine.NOT_FOUND, line(UpdateCheckResult.NotFound("x")))
        assertEquals(UpdateLine.SIGNATURE_INVALID, line(UpdateCheckResult.SignatureInvalid("x")))
        assertEquals(UpdateLine.MANIFEST_INVALID, line(UpdateCheckResult.ManifestInvalid("x")))
        assertTrue(UpdateLine.SIGNATURE_INVALID.isError && UpdateLine.NO_INTERNET.isError && !UpdateLine.UP_TO_DATE.isError)
    }

    @Test
    fun offersCarryTheirSizeAndAreDisabledWhileBlocked() {
        val checked = UpdateUiState.Checked(UpdateCheckResult.Available(listOf(net, voice), emptyList()), listOf(net, voice))
        val v = updateSheetView(checked, null)
        assertEquals(listOf(ModelKind.NET, ModelKind.VOICE), v.offers.map { it.kind })
        assertEquals(158_279_680L, v.offers[1].sizeBytes)
        assertEquals("v0_19-r2", v.offers[1].version)
        assertTrue(v.offers.all { it.installEnabled })
        assertFalse(v.showCheckAgain)
        assertTrue(updateSheetView(checked, UpdateBlock.ANALYSIS).offers.none { it.installEnabled })
    }

    @Test
    fun theInstallShowsItsPhasesAndCancelOnlyWhileDownloading() {
        fun view(phase: UpdatePhase, retry: Int = 0) =
            updateSheetView(UpdateUiState.Installing(voice, UpdateProgress(ModelKind.VOICE, phase, 50_000_000, 158_279_680, retry), emptyList()), null)
        assertEquals(UpdateLine.CONNECTING, view(UpdatePhase.CONNECTING).line)
        val d = view(UpdatePhase.DOWNLOADING)
        assertEquals(UpdateLine.DOWNLOADING, d.line)
        assertTrue(d.showProgress && d.showCancel)
        assertEquals(50_000_000L, d.bytesDone)
        assertTrue(d.fraction in 0.2f..0.3f)
        assertEquals(2, view(UpdatePhase.RETRYING, retry = 1).retryNumber)
        assertEquals(5, view(UpdatePhase.RETRYING, retry = 1).retryOf)
        for (p in listOf(UpdatePhase.VERIFYING, UpdatePhase.UNPACKING, UpdatePhase.TRYING)) {
            assertFalse("no cancel once the file is in ($p)", view(p).showCancel)
        }
        assertEquals(UpdateLine.TRYING, view(UpdatePhase.TRYING).line)
        assertEquals(UpdateLine.UNPACKING, view(UpdatePhase.UNPACKING).line)
        assertTrue("the offer being installed cannot be tapped again", view(UpdatePhase.DOWNLOADING).offers.none { it.installEnabled })
    }

    @Test
    fun theEndOfAnInstallSaysWhatHappened() {
        fun line(o: UpdateInstallOutcome) = updateSheetView(UpdateUiState.Finished(voice, o, emptyList()), null).line
        assertEquals(UpdateLine.INSTALLED, line(UpdateInstallOutcome.Installed(ModelKind.VOICE)))
        assertEquals(UpdateLine.INSTALLED_NET, updateSheetView(UpdateUiState.Finished(net, UpdateInstallOutcome.Installed(ModelKind.NET), emptyList()), null).line)
        assertEquals(UpdateLine.ROLLED_BACK, line(UpdateInstallOutcome.RolledBack(ModelKind.VOICE, "RMS 3")))
        assertEquals(UpdateLine.CONNECTION_DROPPED, line(UpdateInstallOutcome.Paused(ModelKind.VOICE, PauseReason.CONNECTION_LOST)))
        assertEquals(UpdateLine.SERVER_UNAVAILABLE, line(UpdateInstallOutcome.Paused(ModelKind.VOICE, PauseReason.SERVER_UNAVAILABLE)))
        val failures = mapOf(
            UpdateFailure.NOT_FOUND to UpdateLine.FILE_NOT_FOUND,
            UpdateFailure.SERVER to UpdateLine.SERVER_UNAVAILABLE,
            UpdateFailure.DAMAGED to UpdateLine.DAMAGED,
            UpdateFailure.INSUFFICIENT_STORAGE to UpdateLine.LOW_STORAGE,
            UpdateFailure.INCOMPATIBLE to UpdateLine.INCOMPATIBLE,
            UpdateFailure.BUSY to UpdateLine.BUSY_EXPORT,
            UpdateFailure.INSTALL to UpdateLine.FAILED,
            UpdateFailure.INSECURE to UpdateLine.FAILED,
        )
        for ((f, l) in failures) assertEquals("$f", l, line(UpdateInstallOutcome.Failed(ModelKind.VOICE, f, "x")))
        assertEquals(UpdateLine.BUSY_ANALYSIS, updateSheetView(UpdateUiState.Finished(net, UpdateInstallOutcome.Failed(ModelKind.NET, UpdateFailure.BUSY, ""), listOf(net)), null).line)
        // What is left to install stays on the sheet; with nothing left, "Check again".
        val left = updateSheetView(UpdateUiState.Finished(voice, UpdateInstallOutcome.Installed(ModelKind.VOICE), listOf(net)), null)
        assertEquals(listOf(ModelKind.NET), left.offers.map { it.kind })
        assertFalse(left.showCheckAgain)
        assertTrue(updateSheetView(UpdateUiState.Finished(voice, UpdateInstallOutcome.Installed(ModelKind.VOICE), emptyList()), null).showCheckAgain)
    }

    @Test
    fun theVoiceTrialNeedsSoundNotJustAFile() {
        assertTrue(NeuralVoiceTrial.verdict(durationMs = 2_400, rms = 2_500.0))
        assertFalse("silence", NeuralVoiceTrial.verdict(durationMs = 2_400, rms = 3.0))
        assertFalse("too short", NeuralVoiceTrial.verdict(durationMs = 250, rms = 2_500.0))
        assertEquals(0.0, NeuralVoiceTrial.rms(ShortArray(0)), 0.0)
        assertEquals(1000.0, NeuralVoiceTrial.rms(shortArrayOf(1000, -1000, 1000, -1000)), 1e-9)
    }
}
