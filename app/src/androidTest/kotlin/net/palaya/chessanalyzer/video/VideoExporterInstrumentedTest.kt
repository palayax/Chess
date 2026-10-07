package net.palaya.chessanalyzer.video

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.VideoScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof that [VideoExporter] produces a real, playable MP4 from a [VideoScript] —
 * deliberately a small *synthetic* script (not a full game analysis, which takes over a minute)
 * so this stays a fast, deterministic check of the encoder/muxer pipeline itself: H.264 video
 * track at the right resolution, an AAC audio track (real speech or the documented silent
 * fallback), and a played-back duration that lands near the script's expected length.
 */
@RunWith(AndroidJUnit4::class)
class VideoExporterInstrumentedTest {

    /** See [TestScripts.syntheticScript] — moved there so the service tests can share it. */
    private fun syntheticScript(): VideoScript = TestScripts.syntheticScript()

    @Test
    fun exportsAPlayableMp4WithVideoAndAudioTracks(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = syntheticScript()
        val exporter = VideoExporter(context)

        val startedAt = System.currentTimeMillis()
        val outFile = exporter.export(script, "instrumented_test_export")
        val elapsedMs = System.currentTimeMillis() - startedAt

        val completed = exporter.state.value
        assertTrue("expected State.Completed, got $completed", completed is VideoExporter.State.Completed)
        val completedState = completed as VideoExporter.State.Completed

        android.util.Log.i(
            "VideoExporterTest",
            "elapsedMs=$elapsedMs exportedDurationMs=${completedState.durationMs} " +
                "fileSizeBytes=${completedState.fileSizeBytes} narrationWasSpoken=${completedState.narrationWasSpoken}",
        )

        // ---- File exists and is a real, non-trivial size (not an empty/stub container). ----
        assertTrue("output file should exist: $outFile", outFile.isFile)
        assertTrue("output file should be non-trivial in size, was ${outFile.length()} bytes", outFile.length() > 10_000)

        // ---- MediaMetadataRetriever: real video track, right resolution, plausible duration. ----
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(outFile.absolutePath)
        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val reportedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

        assertEquals("expected a video track", "yes", hasVideo)
        assertEquals(VideoExporter.VIDEO_WIDTH, width)
        assertEquals(VideoExporter.VIDEO_HEIGHT, height)
        assertTrue("retriever should report a duration", reportedDurationMs != null && reportedDurationMs > 0)

        // Tight ±5% tolerance, deliberately: with explicit per-frame presentation timestamps
        // (see VideoExporter's class doc on the surface-mode -> buffer-mode migration), output
        // duration is deterministic and independent of how long rendering actually took, so a
        // loose tolerance here would hide exactly the kind of regression that shipped once
        // already (a 2.3x duration inflation from wall-clock-paced frame timestamps).
        val expected = completedState.durationMs
        val tolerance = (expected * 0.05).toLong().coerceAtLeast(200L)
        assertTrue(
            "reported duration $reportedDurationMs ms should be within ±5% of expected $expected ms",
            reportedDurationMs!! in (expected - tolerance)..(expected + tolerance),
        )

        // ---- Decoupling: output duration must be set by the SCRIPT, not by how long encoding took.
        // The old surface-mode renderer took its presentation timestamps from the wall clock, so the
        // output duration tracked `elapsedMs` — a slow device produced a longer video and the
        // narration drifted out of sync. With explicit PTS the two are independent.
        //
        // Note this is deliberately NOT asserted as "export must finish faster than real time".
        // That is a performance goal, not the correctness property, and it fails on a
        // software-rendered emulator (and on a slow phone) even when the implementation is perfect.
        // The real proof is that the duration assertion above holds *while* elapsed time differs
        // from it: under the old bug those two numbers were the same by construction.
        val ratio = elapsedMs.toDouble() / completedState.durationMs.toDouble()
        android.util.Log.i(
            "VideoExportBench",
            "elapsedMs=$elapsedMs durationMs=${completedState.durationMs} " +
                "ratio=${"%.2f".format(ratio)} (>1 means slower than real time to encode)",
        )
        //
        // When the device happens to encode at about real time (seen on chess36 in D2d: 18.6 s to export
        // a 19.4 s video), the two numbers agree by coincidence and this export proves nothing either
        // way. That is inconclusive, not a failure: a second, much shorter script is exported, whose fixed
        // encoder start-up cost puts its wall-clock time far from its own duration, and the same two
        // checks (container duration = the script's timeline, and != the wall clock) must hold for it.
        val conclusive = kotlin.math.abs(elapsedMs - completedState.durationMs) > completedState.durationMs * 0.1
        if (!conclusive) {
            val shortScript = TestScripts.shortScript()
            val shortExporter = VideoExporter(context)
            val shortStarted = System.currentTimeMillis()
            val shortFile = shortExporter.export(shortScript, "instrumented_test_export_short")
            val shortElapsed = System.currentTimeMillis() - shortStarted
            val shortState = shortExporter.state.value as VideoExporter.State.Completed
            val shortReported = MediaMetadataRetriever().run {
                setDataSource(shortFile.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull().also { release() }
            }
            android.util.Log.i(
                "VideoExportBench",
                "inconclusive first export (ratio ${"%.2f".format(ratio)}); short script: elapsedMs=$shortElapsed " +
                    "durationMs=${shortState.durationMs} reportedMs=$shortReported",
            )
            val shortTolerance = (shortState.durationMs * 0.05).toLong().coerceAtLeast(200L)
            assertTrue(
                "short export: reported duration $shortReported ms should be within ±5% of its timeline ${shortState.durationMs} ms",
                shortReported != null && shortReported in (shortState.durationMs - shortTolerance)..(shortState.durationMs + shortTolerance),
            )
            assertTrue(
                "output duration must not track wall-clock export time (the wall-clock-pacing bug): first export " +
                    "${completedState.durationMs} ms in $elapsedMs ms, short export ${shortState.durationMs} ms in $shortElapsed ms",
                kotlin.math.abs(shortElapsed - shortState.durationMs) > shortState.durationMs * 0.1,
            )
            shortFile.delete()
        }
        // Sanity ceiling so a pathological encoding regression still trips the test.
        assertTrue(
            "export took ${ratio}x the video's own duration — unreasonably slow even for an emulator",
            ratio < 30.0,
        )

        // ---- MediaExtractor: audio track always present (real speech, or the documented ----
        // ---- silent fallback — see NarrationSynthesizer's class doc for why that's expected ----
        // ---- routinely on emulator images with no TTS voice data installed). ----
        val extractor = MediaExtractor()
        extractor.setDataSource(outFile.absolutePath)
        var sawAudioTrack = false
        var sawVideoTrack = false
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) sawAudioTrack = true
            if (mime.startsWith("video/")) sawVideoTrack = true
        }
        extractor.release()
        assertTrue("expected a video track via MediaExtractor", sawVideoTrack)
        assertTrue("expected an audio track via MediaExtractor (speech or silent fallback)", sawAudioTrack)

        if (completedState.narrationWasSpoken) {
            android.util.Log.i("VideoExporterTest", "TTS produced real narration for at least one segment.")
        } else {
            android.util.Log.i(
                "VideoExporterTest",
                "TTS was unavailable/unusable on this device — exercised the silent-caption fallback " +
                    "(audio track is present but silent; captions are burned into every frame).",
            )
        }

        // ---- Pull a handful of frames for a human/agent to eyeball visually. Saved under the ----
        // ---- app's external files dir so `adb pull` can reach them without root. ----
        val framesDir = File(context.getExternalFilesDir(null), "video_test_frames").apply { mkdirs() }
        // 0.02 = intro card (header sub-lines), 0.25 = opening Hold (balanced eval bar),
        // 0.50 = key-moment Annotate, 0.80 = the deliberately Black-favoured PlayLine excursion
        // (the direction-correctness frame), 0.98 = outro card (accuracy sub-lines).
        val fractions = listOf(0.02, 0.25, 0.50, 0.80, 0.98)
        fractions.forEachIndexed { i, fraction ->
            val timeUs = (reportedDurationMs * fraction * 1000L).toLong().coerceAtLeast(0L)
            val bitmap: Bitmap? = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (bitmap != null) {
                FileOutputStream(File(framesDir, "frame_$i.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
        retriever.release()
        android.util.Log.i("VideoExporterTest", "frames saved to ${framesDir.absolutePath}")
    }

    @Test
    fun cancelStopsExportPromptlyWithoutLeavingAPartialOutputFile(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = syntheticScript()
        val exporter = VideoExporter(context)

        val deferred = async {
            runCatching { exporter.export(script, "instrumented_test_cancel") }
        }
        // Let it get into the narration/render pipeline, then cancel.
        delay(300)
        exporter.cancel()
        val result = deferred.await()

        assertTrue("export should fail with VideoExportCancelledException", result.isFailure)
        assertTrue(result.exceptionOrNull() is VideoExportCancelledException)
        assertTrue(
            "state should reflect Cancelled after a cancel()",
            exporter.state.value is VideoExporter.State.Cancelled,
        )
    }
}

/**
 * Regression guard for the defect that reached a shipped APK: `VideoScreen.startExport()` passed
 * `null` as the narration provider, so [VideoExporter.export] took the device-TTS path and every
 * exported MP4 ignored the selected neural voice — while in-app playback (a different path) used
 * it, so the bug was invisible until someone listened to the file. Nothing tested
 * `export()` with a NON-null provider, which is why it survived.
 *
 * The proof has to be in the **exported audio**, not in a mock's call log alone: a
 * [ToneVoiceProvider] narrates every sentence as a 1 kHz sine, the MP4's AAC track is decoded back
 * to PCM, and a Goertzel filter must find windows dominated by that tone. The negative control
 * exports the same script with `null` — the old behaviour — and requires the tone to be absent,
 * so the positive assertion is demonstrably one that the old code fails, not one it passes by
 * accident.
 */
@RunWith(AndroidJUnit4::class)
class VideoExporterProviderRegressionTest {

    @Test
    fun exportUsesTheSuppliedProviderForTheAudioTrackAndKeepsThePerSegmentFallback(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = TestScripts.syntheticScript()
        val failing = script.segments[3].narration
        val provider = ToneVoiceProvider(failFor = { it == failing })
        val exporter = VideoExporter(context)

        val outFile = exporter.export(script, "instrumented_test_provider_export", provider)
        val completed = exporter.state.value as VideoExporter.State.Completed

        // (1) The provider was actually driven, sentence by sentence, through the coordinator.
        val expectedRequests = script.segments.map { it.narration }.filter { it.isNotBlank() }
        assertEquals("every spoken sentence must go to the supplied provider", expectedRequests.sorted(), provider.requested.sorted())
        assertEquals("the coordinator releases the provider once", 1, provider.releaseCount.get())
        assertTrue(completed.narrationWasSpoken)

        // (2) The mandatory per-segment fallback still applies: exactly one segment fell back,
        // the export still completed, and the user is told once, in plain words.
        val notice = completed.narrationNotice
        assertNotNull("a fallback must surface a notice", notice)
        assertTrue(notice!!, notice.contains("1 of ${script.segments.size} segment"))
        assertTrue(notice, notice.contains("deliberate test failure"))

        // (3) The provider's audio is what ended up in the MP4. Decode the AAC track and look for
        // the tone: this is the assertion the null-passing code cannot satisfy.
        val pcm = ExportedAudioProbe.decodeAudioTrack(outFile)
        val stats = ExportedAudioProbe.toneWindows(pcm, provider.toneHz)
        android.util.Log.i("VideoExporterProviderRegression", "decoded ${pcm.mono.size} samples @${pcm.sampleRate}; $stats")
        assertTrue("decoded audio should be non-trivial", pcm.mono.size > pcm.sampleRate)
        assertTrue(
            "expected many 100 ms windows dominated by the ${provider.toneHz} Hz tone in the exported audio, got $stats",
            stats.toneDominatedWindows >= 20,
        )
        assertTrue("the tone should be essentially pure in its best window: $stats", stats.bestRatio > 0.8)
    }

    /**
     * Negative control = the old `null` behaviour. Same script, same probe, same threshold: the
     * provider is never consulted and no tone reaches the file. If this test ever starts failing
     * the probe has lost its discrimination and the positive test above proves nothing.
     */
    @Test
    fun nullProviderNeverReachesTheProviderPath_negativeControlForTheProbe(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = TestScripts.syntheticScript()
        val provider = ToneVoiceProvider()
        val exporter = VideoExporter(context)

        val outFile = exporter.export(script, "instrumented_test_null_export", null)
        assertTrue(exporter.state.value is VideoExporter.State.Completed)

        assertEquals("with null the provider must never be called (that was the bug)", emptyList<String>(), provider.requested)
        val stats = ExportedAudioProbe.toneWindows(ExportedAudioProbe.decodeAudioTrack(outFile), provider.toneHz)
        android.util.Log.i("VideoExporterProviderRegression", "null-provider export: $stats")
        assertEquals("no tone may appear when the provider was not used: $stats", 0, stats.toneDominatedWindows)
    }
}
