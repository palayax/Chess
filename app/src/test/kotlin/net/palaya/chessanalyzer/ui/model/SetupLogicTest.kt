package net.palaya.chessanalyzer.ui.model

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.palaya.chessanalyzer.data.models.DownloadState
import net.palaya.chessanalyzer.data.models.FailureReason
import net.palaya.chessanalyzer.data.models.FileProgress
import net.palaya.chessanalyzer.data.models.ModelFile
import net.palaya.chessanalyzer.data.models.ModelSetup
import net.palaya.chessanalyzer.data.models.NetworkCost
import net.palaya.chessanalyzer.data.models.PauseReason
import net.palaya.chessanalyzer.data.models.SetupProgress
import net.palaya.chessanalyzer.data.models.SetupState
import net.palaya.chessanalyzer.data.models.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The Setup screen's pure rules (D2c, docs/MODEL_DOWNLOAD_DESIGN.md §1.2-§1.4, §6.1 "SetupLogicTest"). */
class SetupLogicTest {

    // The real pins (MODELS.lock): net 98,511,183 B, voice download (.tar.gz, D2f) 102,543,452 B, unpacked
    // voice (the tar) 158,269,440 B.
    private val sizes = SetupSizes(netBytes = 98_511_183L, voiceBytes = 102_543_452L, voiceInstalledBytes = 158_269_440L)
    private val fresh = SetupState(netInstalled = false, voiceInstalled = false, netPartBytes = 0, voicePartBytes = 0)
    private val netOnly = SetupState(netInstalled = true, voiceInstalled = false, netPartBytes = 0, voicePartBytes = 0)
    private val complete = SetupState(netInstalled = true, voiceInstalled = true, netPartBytes = 0, voicePartBytes = 0)

    /** ModelSetup.storageNeeded() for a fresh install: net + .tar.gz + unpacked voice + the 32 MiB margin. */
    private val freshPeak = sizes.netBytes + sizes.voiceBytes + sizes.voiceInstalledBytes + ModelSetup.SAFETY_MARGIN_BYTES

    // ---- sizes ----

    @Test
    fun theAboutFiguresMatchTheDesignsCopy() {
        assertEquals(100L, aboutMegabytes(sizes.netBytes))
        // D2f: the voice downloads as a .tar.gz (about 110 MB) and unpacks to about 160 MB.
        assertEquals(110L, aboutMegabytes(sizes.voiceBytes))
        assertEquals(210L, aboutMegabytes(sizes.netBytes + sizes.voiceBytes))
        // 400 MB at the peak on a fresh install (450 with the plain tar, design §1.3), 260 once done;
        // 300 / 160 for the voice alone.
        assertEquals(400L, aboutMegabytes(freshPeak))
        assertEquals(260L, aboutMegabytes(installedFootprintBytes(fresh, sizes)))
        assertEquals(300L, aboutMegabytes(sizes.voiceBytes + sizes.voiceInstalledBytes + ModelSetup.SAFETY_MARGIN_BYTES))
        assertEquals(160L, aboutMegabytes(installedFootprintBytes(netOnly, sizes)))
        // What is downloaded is smaller than what it becomes.
        assertEquals(201_054_635L, downloadTotalBytes(fresh, sizes))
        assertEquals(256_780_623L, installedFootprintBytes(fresh, sizes))
        assertEquals(sizes.voiceBytes, downloadTotalBytes(netOnly, sizes))
        assertEquals(0L, downloadTotalBytes(complete, sizes))
    }

    @Test
    fun aboutNeverUnderstatesAndHandlesSmallAndEmpty() {
        assertEquals(0L, aboutMegabytes(0))
        assertEquals(0L, aboutMegabytes(-5))
        assertEquals(1L, aboutMegabytes(1))
        assertEquals(4L, aboutMegabytes(3_200_000))
        assertEquals(10L, aboutMegabytes(10_000_000))
        assertEquals(20L, aboutMegabytes(10_000_001))
        for (b in listOf(1L, 999_999L, 9_999_999L, 98_511_183L, 256_780_623L, 448_600_000L)) {
            assertTrue("about($b) understates", aboutMegabytes(b) * BYTES_PER_MB >= b)
        }
    }

    @Test
    fun megabyteLabelsAreIsolatedForRightToLeftLines() {
        assertEquals("$LRM" + "260 MB" + "$LRM", megabytesLabel(260))
    }

    @Test
    fun progressCountsRoundTheTotalAndNeverShowDoneEarly() {
        val total = sizes.netBytes + sizes.voiceBytes
        assertEquals(0L to 201L, progressMegabytes(0, total))
        assertEquals(120L to 201L, progressMegabytes(120_400_000, total))
        // One byte short of the end still reads below the total.
        assertEquals(200L to 201L, progressMegabytes(total - 1, total))
        assertEquals(201L to 201L, progressMegabytes(total + 10, total))
        assertEquals(0L to 0L, progressMegabytes(-1, 0))
    }

    @Test
    fun percentIsClampedAndRoundedDown() {
        assertEquals(0, percentOf(-1f))
        assertEquals(43, percentOf(0.4399f))
        assertEquals(100, percentOf(1.5f))
    }

    @Test
    fun theSettingsStorageFormatIsUnchangedAfterTheMove() {
        assertEquals("$LRM" + "12.4 MB" + "$LRM", formatStorageMegabytes((12.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun bytesLeftCountsMissingFilesMinusTheirParts() {
        assertEquals(sizes.netBytes + sizes.voiceBytes, bytesLeftToDownload(fresh, sizes))
        assertEquals(sizes.voiceBytes, bytesLeftToDownload(netOnly, sizes))
        assertEquals(0L, bytesLeftToDownload(complete, sizes))
        assertEquals(
            sizes.netBytes - 40_000_000 + sizes.voiceBytes,
            bytesLeftToDownload(fresh.copy(netPartBytes = 40_000_000), sizes),
        )
        // A part of an installed file does not count.
        assertEquals(sizes.voiceBytes, bytesLeftToDownload(netOnly.copy(netPartBytes = 5), sizes))
    }

    // ---- the tap ----

    @Test
    fun precheckOrderIsStorageThenNoNetworkThenMetered() {
        assertEquals(SetupPrecheck.START, setupPrecheck(NetworkCost.UNMETERED, freshPeak, freshPeak))
        assertEquals(SetupPrecheck.ASK_METERED, setupPrecheck(NetworkCost.METERED, freshPeak, freshPeak))
        assertEquals(SetupPrecheck.NO_NETWORK, setupPrecheck(NetworkCost.UNAVAILABLE, freshPeak, freshPeak))
        assertEquals(SetupPrecheck.LOW_STORAGE, setupPrecheck(NetworkCost.UNAVAILABLE, freshPeak - 1, freshPeak))
        assertEquals(SetupPrecheck.LOW_STORAGE, setupPrecheck(NetworkCost.METERED, freshPeak - 1, freshPeak))
        // Nothing missing: no space needed.
        assertEquals(SetupPrecheck.START, setupPrecheck(NetworkCost.UNMETERED, 0, 0))
    }

    // ---- what the screen shows ----

    @Test
    fun aFreshInstallShowsTheIntroWithDownloadAndBothSizes() {
        val v = setupView(fresh, sizes, running = false, progress = null)
        assertEquals(SetupPhase.INTRO, v.phase)
        assertNull(v.line)
        assertEquals(SetupAction.DOWNLOAD, v.primary)
        assertEquals(sizes.netBytes + sizes.voiceBytes, v.bytesToDownload)
        assertFalse(v.canPause)
        assertFalse(v.canCancel)
        assertEquals(listOf(ModelFile.NET, ModelFile.VOICE), v.rows.map { it.file })
        assertEquals(listOf(sizes.netBytes, sizes.voiceBytes), v.rows.map { it.bytesTotal })
        assertTrue(v.rows.none { it.installed || it.active })
    }

    @Test
    fun netInstalledVoiceMissingOffersTheVoiceOnly() {
        val v = setupView(netOnly, sizes, running = false, progress = null)
        assertEquals(SetupPhase.INTRO, v.phase)
        assertEquals(SetupAction.DOWNLOAD_VOICE, v.primary)
        assertEquals(sizes.voiceBytes, v.bytesToDownload)
        assertTrue(v.rows.first { it.file == ModelFile.NET }.installed)
        assertFalse(v.rows.first { it.file == ModelFile.VOICE }.installed)
    }

    @Test
    fun partFilesFoundAtLaunchShowPausedAtWithResumeAndCancel() {
        // A kill (or a pause in an earlier session) left 54% of the download on disk (net 98.5 + 12 of 201.1 MB);
        // nothing resumes by itself.
        val disk = fresh.copy(netPartBytes = sizes.netBytes, voicePartBytes = 12_000_000)
        val v = setupView(disk, sizes, running = false, progress = null)
        assertEquals(SetupPhase.PAUSED, v.phase)
        assertEquals(SetupLine.PAUSED_AT, v.line)
        assertEquals(SetupAction.RESUME, v.primary)
        assertTrue(v.canCancel)
        assertFalse(v.canPause)
        assertEquals(54, percentOf(v.fraction))
        assertEquals("the total is the download, not the unpacked size", downloadTotalBytes(fresh, sizes), v.bytesTotal)
    }

    @Test
    fun bothInstalledIsDoneWithContinue() {
        val v = setupView(complete, sizes, running = false, progress = null)
        assertEquals(SetupPhase.DONE, v.phase)
        assertEquals(SetupLine.DONE, v.line)
        assertEquals(SetupAction.CONTINUE, v.primary)
    }

    private fun progress(
        status: SetupStatus,
        net: DownloadState = DownloadState.Streaming(0, 0),
        voice: DownloadState = DownloadState.Idle,
        netDone: Long = 50_000_000,
        retry: Int = 0,
        pause: PauseReason? = null,
        failure: FailureReason? = null,
    ) = SetupProgress(
        overallFraction = 0.2f,
        bytesDone = netDone,
        bytesTotal = sizes.netBytes + sizes.voiceBytes,
        perFile = listOf(
            FileProgress(ModelFile.NET, netDone, sizes.netBytes, net),
            FileProgress(ModelFile.VOICE, 0, sizes.voiceBytes, voice),
        ),
        status = status,
        retryAttempt = retry,
        pauseReason = pause,
        failure = failure,
    )

    @Test
    fun runningStatesMapToTheirLinesWithPauseAndCancel() {
        val cases = mapOf(
            progress(SetupStatus.CONNECTING, net = DownloadState.Connecting(0, 0), netDone = 0) to SetupLine.CONNECTING,
            progress(SetupStatus.DOWNLOADING) to SetupLine.DOWNLOADING,
            progress(SetupStatus.RETRYING, net = DownloadState.Backoff(1, 0, 2000), retry = 1) to SetupLine.RETRYING,
            progress(SetupStatus.DOWNLOADING, net = DownloadState.Verified, netDone = sizes.netBytes) to SetupLine.CHECKING,
            progress(SetupStatus.UNPACKING, net = DownloadState.Installed, voice = DownloadState.Installing, netDone = sizes.netBytes) to SetupLine.UNPACKING,
        )
        for ((p, line) in cases) {
            val v = setupView(fresh, sizes, running = true, progress = p)
            assertEquals(p.status.name, SetupPhase.RUNNING, v.phase)
            assertEquals(p.status.name, line, v.line)
            assertTrue(v.canPause)
            assertTrue(v.canCancel)
            assertNull(v.primary)
        }
    }

    @Test
    fun retryingSaysWhichAttemptOfFive() {
        val v = setupView(fresh, sizes, running = true, progress = progress(SetupStatus.RETRYING, net = DownloadState.Backoff(1, 0, 2000), retry = 1))
        assertEquals(2, v.retryNumber)
        assertEquals(5, v.retryOf)
        val last = setupView(fresh, sizes, running = true, progress = progress(SetupStatus.RETRYING, net = DownloadState.Backoff(4, 0, 16000), retry = 4))
        assertEquals(5, last.retryNumber)
    }

    @Test
    fun theActiveFileRowIsTheFirstNotInstalledOne() {
        val p = progress(SetupStatus.DOWNLOADING, net = DownloadState.Installed, voice = DownloadState.Streaming(0, 0), netDone = sizes.netBytes)
            .let { it.copy(perFile = listOf(it.perFile[0], it.perFile[1].copy(bytesDone = 21_000_000))) }
        val v = setupView(fresh, sizes, running = true, progress = p)
        val net = v.rows.first { it.file == ModelFile.NET }
        val voice = v.rows.first { it.file == ModelFile.VOICE }
        assertTrue(net.installed)
        assertFalse(net.active)
        assertTrue(voice.active)
        assertEquals(21_000_000L, voice.bytesDone)
    }

    @Test
    fun aVoiceOnlyRunStillShowsTheInstalledNetRow() {
        val p = SetupProgress(0.1f, 16_000_000, sizes.voiceBytes, listOf(FileProgress(ModelFile.VOICE, 16_000_000, sizes.voiceBytes, DownloadState.Streaming(0, 0))), SetupStatus.DOWNLOADING)
        val v = setupView(netOnly, sizes, running = true, progress = p)
        assertTrue(v.rows.first { it.file == ModelFile.NET }.installed)
        assertTrue(v.rows.first { it.file == ModelFile.VOICE }.active)
    }

    @Test
    fun pausesSayWhyAndOfferResumeOrTryAgain() {
        val user = setupView(fresh, sizes, false, progress(SetupStatus.PAUSED, pause = PauseReason.USER))
        assertEquals(SetupLine.PAUSED, user.line)
        assertEquals(SetupAction.RESUME, user.primary)
        assertTrue(user.canCancel)
        assertFalse(user.line!!.isError)

        val dropped = setupView(fresh, sizes, false, progress(SetupStatus.PAUSED, pause = PauseReason.CONNECTION_LOST))
        assertEquals(SetupLine.CONNECTION_DROPPED, dropped.line)
        assertEquals(SetupAction.RESUME, dropped.primary)
        assertTrue(dropped.line!!.isError)

        val server = setupView(fresh, sizes, false, progress(SetupStatus.PAUSED, pause = PauseReason.SERVER_UNAVAILABLE))
        assertEquals(SetupLine.SERVER_UNAVAILABLE, server.line)
        assertEquals(SetupAction.TRY_AGAIN, server.primary)
    }

    @Test
    fun failuresSayWhyAndOfferTryAgain() {
        val expected = mapOf(
            FailureReason.NOT_FOUND to SetupLine.NOT_FOUND,
            FailureReason.DAMAGED to SetupLine.DAMAGED,
            FailureReason.INSUFFICIENT_STORAGE to SetupLine.LOW_STORAGE,
            FailureReason.SERVER to SetupLine.FAILED,
            FailureReason.SIZE_MISMATCH to SetupLine.FAILED,
            FailureReason.INSECURE to SetupLine.FAILED,
            FailureReason.INSTALL to SetupLine.FAILED,
        )
        for ((reason, line) in expected) {
            val v = setupView(fresh, sizes, false, progress(SetupStatus.FAILED, failure = reason))
            assertEquals(reason.name, SetupPhase.FAILED, v.phase)
            assertEquals(reason.name, line, v.line)
            assertEquals(SetupAction.TRY_AGAIN, v.primary)
            assertTrue(v.line!!.isError)
            assertFalse(v.canPause)
        }
        // Cancel is offered only when there is something on disk to delete.
        assertFalse(setupView(fresh, sizes, false, progress(SetupStatus.FAILED, failure = FailureReason.NOT_FOUND)).canCancel)
        assertTrue(setupView(fresh.copy(netPartBytes = 9), sizes, false, progress(SetupStatus.FAILED, failure = FailureReason.NOT_FOUND)).canCancel)
    }

    @Test
    fun anEndedRunIsDrawnFromTheDiskNotFromItsLastSnapshot() {
        // A second wrong hash deletes the part: the row must not keep saying "99 MB of 99 MB" (chess34, D2c).
        val failed = progress(SetupStatus.FAILED, failure = FailureReason.DAMAGED, netDone = sizes.netBytes)
        val v = setupView(fresh, sizes, running = false, progress = failed)
        assertEquals(SetupLine.DAMAGED, v.line)
        assertEquals(0L, v.rows.first { it.file == ModelFile.NET }.bytesDone)
        assertEquals(0, percentOf(v.fraction))
        // A pause keeps the part: the disk says how much.
        val paused = setupView(fresh.copy(netPartBytes = 40_000_000), sizes, false, progress(SetupStatus.PAUSED, pause = PauseReason.USER))
        assertEquals(40_000_000L, paused.rows.first { it.file == ModelFile.NET }.bytesDone)
        assertEquals(40_000_000L, paused.bytesDone)
    }

    @Test
    fun doneFromTheRunNeedsTheDiskToAgree() {
        val p = progress(SetupStatus.DONE, net = DownloadState.Installed, voice = DownloadState.Installed)
        assertEquals(SetupPhase.DONE, setupView(complete, sizes, false, p).phase)
        // A stale DONE from earlier in the process while the voice has since gone: the disk wins.
        assertEquals(SetupPhase.INTRO, setupView(netOnly, sizes, false, p).phase)
    }

    @Test
    fun aStaleRunningSnapshotWithNoRunningServiceFallsBackToTheDisk() {
        val v = setupView(fresh.copy(netPartBytes = 50_000_000), sizes, running = false, progress = progress(SetupStatus.DOWNLOADING))
        assertEquals(SetupPhase.PAUSED, v.phase)
        assertEquals(SetupLine.PAUSED_AT, v.line)
    }

    // ---- Home ----

    @Test
    fun theHomeCardShowsUntilCompleteAndCarriesTheRunAndTheWaitingGame() {
        assertNull(homeSetupCard(complete, sizes, running = false, progress = null, gameWaiting = false))
        val idle = homeSetupCard(fresh, sizes, false, null, gameWaiting = true)
        assertNotNull(idle)
        assertEquals(210L, aboutMegabytes(idle!!.bytesLeft))
        assertFalse(idle.running)
        assertTrue(idle.gameWaiting)
        assertEquals(110L, aboutMegabytes(homeSetupCard(netOnly, sizes, false, null, false)!!.bytesLeft))
        val running = homeSetupCard(netOnly, sizes, true, progress(SetupStatus.DOWNLOADING), false)!!
        assertTrue(running.running)
        assertEquals(50_000_000L, running.bytesDone)
    }

    // ---- throttle ----

    @Test
    fun theThrottlePassesStructuralChangesAtOnceAndBytesAtItsRate() {
        val t = ProgressThrottle(100)
        val a = progress(SetupStatus.DOWNLOADING, netDone = 1_000)
        assertTrue(t.shouldEmit(a, 1_000))
        assertFalse(t.shouldEmit(a.copy(bytesDone = 2_000), 1_050))
        assertFalse(t.shouldEmit(a.copy(bytesDone = 3_000), 1_099))
        assertTrue(t.shouldEmit(a.copy(bytesDone = 4_000), 1_100))
        // A status change goes through immediately.
        assertTrue(t.shouldEmit(a.copy(status = SetupStatus.RETRYING, retryAttempt = 1), 1_101))
        // So does a file's state change (net verified), and a pause reason.
        val verified = a.copy(perFile = listOf(a.perFile[0].copy(state = DownloadState.Verified), a.perFile[1]))
        assertTrue(t.shouldEmit(verified, 1_102))
        assertTrue(t.shouldEmit(verified.copy(status = SetupStatus.PAUSED, pauseReason = PauseReason.USER), 1_103))
        t.reset()
        assertTrue(t.shouldEmit(a, 0))
    }

    @Test
    fun structuralChangeIgnoresByteCountsOnly() {
        val a = progress(SetupStatus.DOWNLOADING)
        assertFalse(isStructuralChange(a, a.copy(bytesDone = a.bytesDone + 1, overallFraction = 0.3f)))
        assertTrue(isStructuralChange(a, a.copy(failure = FailureReason.SERVER)))
    }

    // ---- the service declaration ----

    @Test
    fun theDownloadServiceIsDeclaredDataSyncAndNotExported() {
        val f = File("src/main/AndroidManifest.xml").let { if (it.exists()) it else File("app/src/main/AndroidManifest.xml") }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
        val services = root.getElementsByTagName("service")
        val svc = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttribute("android:name").endsWith("ModelDownloadService") }
        assertEquals("dataSync", svc.getAttribute("android:foregroundServiceType"))
        assertEquals("false", svc.getAttribute("android:exported"))
    }
}
