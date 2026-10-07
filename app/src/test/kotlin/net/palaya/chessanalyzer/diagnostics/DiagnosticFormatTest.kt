package net.palaya.chessanalyzer.diagnostics

import java.util.TimeZone
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.data.EngineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the diagnostic log says about the device, the previous process's exit, each position and the game (F1). */
class DiagnosticFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private val device = DeviceInfo(
        appVersion = "1.0",
        versionCode = 1,
        buildType = "release",
        manufacturer = "Google",
        model = "Pixel 6",
        androidRelease = "16",
        sdkInt = 36,
        abis = listOf("arm64-v8a"),
        cpuCores = 8,
        totalRamBytes = 7_800L * 1024 * 1024,
        availRamBytes = 2_100L * 1024 * 1024,
        lowRamDevice = false,
    )

    @Test
    fun theDeviceBlockListsExactlyTheAgreedFacts() {
        assertEquals(
            """
            app 1.0 (1, release)
            device Google Pixel 6
            android 16 (API 36)
            abi arm64-v8a
            cpu cores 8
            ram total 7800 MiB, available 2100 MiB, low-ram device no
            """.trimIndent(),
            formatDeviceInfo(device),
        )
    }

    @Test
    fun theDeviceBlockCarriesNoIdentifier() {
        // DeviceInfo has no field for a serial, an Android ID or an account, so none can be logged.
        val fields = DeviceInfo::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertEquals(
            setOf(
                "appVersion", "versionCode", "buildType", "manufacturer", "model", "androidRelease",
                "sdkInt", "abis", "cpuCores", "totalRamBytes", "availRamBytes", "lowRamDevice",
            ),
            fields,
        )
    }

    @Test
    fun exitReasonsAreNamedAndUnknownOnesStayReadable() {
        assertEquals("LOW_MEMORY", exitReasonName(3))
        assertEquals("CRASH", exitReasonName(4))
        assertEquals("CRASH_NATIVE", exitReasonName(5))
        assertEquals("ANR", exitReasonName(6))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", exitReasonName(9))
        assertEquals("USER_REQUESTED", exitReasonName(10))
        assertEquals("FREEZER", exitReasonName(14))
        assertEquals("REASON_99", exitReasonName(99))
        assertEquals("CACHED", importanceName(400))
        assertEquals("FOREGROUND", importanceName(100))
        assertEquals("IMPORTANCE_7", importanceName(7))
    }

    @Test
    fun anExitRecordSaysWhatKilledTheAppAndWhatItWasDoing() {
        val record = ExitRecord(
            reason = 9, subReason = null, description = "excessive cpu usage", importance = 400,
            timestampMs = 1_791_288_000_000L, status = 0, pssKb = 812_000, rssKb = 905_000,
        )
        assertEquals(
            "previous process exit: EXCESSIVE_RESOURCE_USAGE at 2026-10-06 12:00:00 +0000, importance CACHED, " +
                "status 0, pss 812000 KB, rss 905000 KB, description \"excessive cpu usage\"",
            formatExitRecord(record, utc),
        )
    }

    @Test
    fun onlyExitRecordsNewerThanTheLastLoggedOneAreLoggedOldestFirst() {
        fun r(t: Long) = ExitRecord(3, null, null, 400, t, 0, 0, 0)
        val records = listOf(r(500), r(100), r(300), r(200))
        assertEquals(listOf(300L, 500L), newExitRecords(records, lastLoggedMs = 200).map { it.timestampMs })
        assertTrue(newExitRecords(records, lastLoggedMs = 500).isEmpty())
        assertEquals(listOf(400L, 500L), newExitRecords((1..5).map { r(it * 100L) }, 0, limit = 2).map { it.timestampMs })
    }

    @Test
    fun aPositionLineHasEveryFieldTheOwnerAskedFor() {
        val line = formatPositionLine(
            PositionLogLine(
                index = 45, san = "Rdg1", requestedDepth = 18, reachedDepth = 16,
                nodes = 30_123_456, timeMs = 41_234, capped = true, score = scoreText(-598, null),
            ),
        )
        assertEquals("pos 45 Rdg1 depth 16/18 nodes 30123456 ms 41234 capped yes score cp -598", line)
        assertEquals(
            "pos 0 start depth 18/18 nodes 900 ms 12 capped no score mate 3",
            formatPositionLine(PositionLogLine(0, null, 18, 18, 900, 12, false, scoreText(null, 3))),
        )
        assertEquals("none", scoreText(null, null))
    }

    @Test
    fun theGameIsLoggedWithItsTagsAndItsMovesInSan() {
        val game = PgnParser.parse(
            """
            [White "Fouchon"]
            [Black "Sonarmind"]
            [Result "0-1"]

            1. e4 {[%clk 0:04:59]} d5 2. exd5 Nf6 0-1
            """.trimIndent(),
        ).single()
        val text = formatGameForLog(game)
        assertTrue(text, text.startsWith("tags: "))
        assertTrue(text, text.contains("[White \"Fouchon\"]") && text.contains("[Black \"Sonarmind\"]"))
        assertTrue(text, text.endsWith("\nmoves: 1. e4 d5 2. exd5 Nf6 0-1"))
        assertFalse("clock comments are not moves", text.contains("clk"))
    }

    @Test
    fun theShareSummaryNamesVersionDeviceAndTheLastError() {
        assertEquals(
            "Palaya Chess 1.0 (release)\nGoogle Pixel 6, Android 16 (API 36)\nLast error: none",
            shareSummary("1.0", "release", "Google Pixel 6", "16", 36, null),
        )
        assertTrue(shareSummary("1.0", "debug", "x", "14", 34, "analysis failed: ANALYSIS").endsWith("Last error: analysis failed: ANALYSIS"))
    }

    @Test
    fun onlyTheEnginesOwnDiagnosticsAreLoggedNotItsSearchOutput() {
        assertTrue(EngineController.isEngineDiagnostic("info string NNUE evaluation using nn-1a298aa575a0.nnue"))
        assertTrue(EngineController.isEngineDiagnostic("info string ERROR: Network evaluation parameters incompatible"))
        assertTrue(EngineController.isEngineDiagnostic("Stockfish 19 by the Stockfish developers (see AUTHORS file)"))
        assertFalse(EngineController.isEngineDiagnostic("info depth 12 seldepth 18 multipv 1 score cp 31 nodes 1000 pv e2e4"))
        assertFalse(EngineController.isEngineDiagnostic("bestmove e2e4 ponder e7e5"))
        assertFalse(EngineController.isEngineDiagnostic("readyok"))
        assertFalse(EngineController.isEngineDiagnostic("option name Threads type spin default 1 min 1 max 1024"))
        assertFalse(EngineController.isEngineDiagnostic("   "))
    }
}
