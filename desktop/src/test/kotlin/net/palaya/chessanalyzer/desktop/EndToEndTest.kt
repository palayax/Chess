package net.palaya.chessanalyzer.desktop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.NotationGuard
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.desktop.analysis.AnalysisStage
import net.palaya.chessanalyzer.desktop.analysis.ReportDto
import net.palaya.chessanalyzer.desktop.analysis.ReportFactory
import net.palaya.chessanalyzer.desktop.cli.ExitCode
import net.palaya.chessanalyzer.desktop.cli.Main
import net.palaya.chessanalyzer.desktop.encode.Tools
import net.palaya.chessanalyzer.desktop.storyboard.Director
import net.palaya.chessanalyzer.desktop.storyboard.StoryboardStage
import net.palaya.chessanalyzer.desktop.timeline.Timeline
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The whole producer, as the CLI runs it: `palaya-review fixtures/immortal.pgn --no-llm --tts fake`
 * (design §13 MixStageTest/EndToEndTest). Real Stockfish, a real Python worker, real ffmpeg; every
 * check reads the produced MP4 back with ffprobe/ffmpeg. The MP4 is **kept** under
 * `pc/work/tests/<timestamp>/` for a human to open.
 *
 * Speed knobs, stated so nobody mistakes them for the product settings: depth 14 / Threads 4, and
 * the fake voice at 20 ms per character (a third of speech pace) so the video is minutes, not the
 * 23 minutes the generator's full HIGHLIGHTS script runs at speech pace.
 */
class EndToEndTest {

    @Test
    fun immortalFakeTtsProducesAVerifiedMp4() {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val outDir = TestEnv.repoRoot.resolve("pc/work/tests/$stamp")
        Files.createDirectories(outDir)
        val mp4 = outDir.resolve("immortal_fake.mp4")
        val work = outDir.resolve("work")
        val pgn = TestEnv.fixture("immortal.pgn")
        TestEnv.ttsPython; TestEnv.ffmpeg; TestEnv.ffprobe // fail loudly if a tool is missing

        val log = StringBuilder()
        val t0 = System.nanoTime()
        val code = Main.run(arrayOf(
            pgn.toString(), "-o", mp4.toString(), "--no-llm", "--tts", "fake", "--fake-ms-per-char", "20",
            "--depth", "14", "--threads", TestEnv.THREADS.toString(), "--hash", TestEnv.HASH_MB.toString(),
            "--work", work.toString(),
        )) { line -> println(line); log.appendLine(line) }
        val wall = (System.nanoTime() - t0) / 1e9
        Files.writeString(outDir.resolve("run.log"), log)
        assertEquals("exit code (log: ${outDir.resolve("run.log")})", ExitCode.OK, code)
        assertTrue(Files.isRegularFile(mp4))

        val game = PgnParser.parse(Files.readString(pgn)).first()
        val wd = WorkDir(work, WorkDir.gameId(game))
        val timeline = WorkJson.decodeFromString(Timeline.serializer(), Files.readString(wd.timelineJson))

        // ---- streams ---------------------------------------------------------------------
        val probe = Tools.run(listOf(TestEnv.ffprobe.toString(), "-v", "error", "-show_streams", "-show_format", "-of", "json", mp4.toString()))
        assertEquals(probe.stderr, 0, probe.exitCode)
        val pj = Json.parseToJsonElement(probe.stdout).jsonObject
        val streams = pj["streams"]!!.jsonArray.map { it.jsonObject }
        val v = streams.single { it["codec_type"]!!.jsonPrimitive.content == "video" }
        val a = streams.single { it["codec_type"]!!.jsonPrimitive.content == "audio" }
        assertEquals("h264", v["codec_name"]!!.jsonPrimitive.content)
        assertEquals(1920, v["width"]!!.jsonPrimitive.int)
        assertEquals(1080, v["height"]!!.jsonPrimitive.int)
        assertEquals("30/1", v["r_frame_rate"]!!.jsonPrimitive.content)
        assertEquals("aac", a["codec_name"]!!.jsonPrimitive.content)
        assertEquals("48000", a["sample_rate"]!!.jsonPrimitive.content)
        assertEquals(2, a["channels"]!!.jsonPrimitive.int)
        val durationMs = pj["format"]!!.jsonObject["duration"]!!.jsonPrimitive.content.toDouble() * 1000
        val frames = v["nb_frames"]!!.jsonPrimitive.content.toInt()
        val expectedFrames = Math.round(timeline.totalMs * 30 / 1000.0).toInt()
        println(String.format("E2E: container %.0f ms vs timeline %d ms; %d frames vs %d expected; wall %.1f s",
            durationMs, timeline.totalMs, frames, expectedFrames, wall))
        assertEquals("container duration = timeline.totalMs ±100 ms", timeline.totalMs.toDouble(), durationMs, 100.0)
        assertEquals("frame count", expectedFrames.toDouble(), frames.toDouble(), 1.0)

        // ---- loudness --------------------------------------------------------------------
        val eb = Tools.run(listOf(TestEnv.ffmpeg.toString(), "-hide_banner", "-nostats", "-i", mp4.toString(),
            "-filter_complex", "ebur128", "-f", "null", "-"))
        val integrated = Regex("I:\\s+(-?\\d+(?:\\.\\d+)?) LUFS").findAll(eb.stderr).last().groupValues[1].toDouble()
        println("E2E: integrated loudness $integrated LUFS")
        assertEquals("ebur128 integrated", -14.0, integrated, 1.0)

        // ---- the audio track itself ------------------------------------------------------
        val wav = outDir.resolve("review_audio.wav")
        val ex = Tools.run(listOf(TestEnv.ffmpeg.toString(), "-v", "error", "-y", "-i", mp4.toString(), "-vn", "-ac", "1", "-ar", "48000",
            "-c:a", "pcm_s16le", wav.toString()))
        assertEquals(ex.stderr, 0, ex.exitCode)
        val ais = AudioSystem.getAudioInputStream(wav.toFile())
        val bytes = ais.readAllBytes(); ais.close()
        val pcm = ShortArray(bytes.size / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
        val win = 960 // 20 ms
        var silent = 0
        var windows = 0
        var sumSq = 0.0
        for (w in 0 until pcm.size / win) {
            var acc = 0.0
            for (i in w * win until (w + 1) * win) acc += pcm[i].toDouble() * pcm[i]
            sumSq += acc
            if (20 * log10(sqrt(acc / win) / 32768.0 + 1e-12) < -40.0) silent++
            windows++
        }
        val nearSilent = silent.toDouble() / windows
        val rmsDb = 20 * log10(sqrt(sumSq / (windows * win)) / 32768.0)
        println(String.format("E2E: extracted audio %.1f s, RMS %.1f dBFS, near-silent %.1f%%", pcm.size / 48000.0, rmsDb, 100 * nearSilent))
        assertTrue("near-silent ratio < 60% (got $nearSilent)", nearSilent < 0.60)

        // The 440 Hz tone is present at every line's audioStartMs (+100 ms pad): Goertzel power
        // at 440 Hz against the window's total power.
        val lines = timeline.beats.flatMap { it.lines }
        var worst = 1.0
        for (line in lines) {
            val from = ((line.audioStartMs + 140) * 48).toInt()
            val n = 48 * 120
            val ratio = toneRatio(pcm, from, n, 440.0, 48000.0)
            worst = minOf(worst, ratio)
            assertTrue("tone at ${line.id} (${line.audioStartMs} ms): ratio $ratio", ratio > 0.8)
        }
        println(String.format("E2E: tone found at all %d line starts, worst 440 Hz energy ratio %.3f", lines.size, worst))

        // ---- storyboard / script invariants ----------------------------------------------
        val storyboard = StoryboardStage.read(wd)!!
        assertTrue(storyboard.beats.isNotEmpty())
        storyboard.beats.forEach { assertFalse("notation in ${it.id}: ${it.fallbackText}", NotationGuard.containsNotation(it.fallbackText)) }
        assertTrue("the mating move 23.Be7# has a beat", storyboard.beats.any { it.ply == game.moves.size })
        val evals = AnalysisStage.readAnalysis(wd)!!.evals.map { it.toPositionEval() }
        val again = Director.direct(wd.gameId, game, ReportFactory.build(game, evals, null), null, NarrationOptions(speechWpm = 165))
        assertEquals("storyboard.json is byte-identical on a repeat", Files.readString(wd.storyboardJson), WorkJson.encodeToString(again))

        // P0 defect fix: every position carries the capped flag, and report.json lists them.
        val report = WorkJson.decodeFromString(ReportDto.serializer(), Files.readString(wd.reportJson))
        val cappedPlies = report.plies.count { it.capped }
        println("E2E: capped positions ${report.cappedPositions}, capped plies $cappedPlies")
        assertTrue(report.plies.all { it.depthAfter > 0 && it.depthBefore > 0 })

        println("E2E: kept $mp4")
    }

    /** Fraction of the window's energy at [freq] (Goertzel), 0..1. */
    private fun toneRatio(pcm: ShortArray, from: Int, n: Int, freq: Double, rate: Double): Double {
        val k = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0
        var s2 = 0.0
        var energy = 0.0
        for (i in from until minOf(pcm.size, from + n)) {
            val x = pcm[i].toDouble()
            energy += x * x
            val s0 = x + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - k * s1 * s2
        // A pure sinusoid of amplitude A over n samples: power = (A n / 2)^2, energy = A^2 n / 2.
        return if (energy == 0.0) 0.0 else abs(power) * 2.0 / (n * energy)
    }
}
