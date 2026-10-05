package net.palaya.chessanalyzer.desktop.tts

import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.desktop.TestEnv
import net.palaya.chessanalyzer.desktop.script.ScriptBeat
import net.palaya.chessanalyzer.desktop.script.ScriptFile
import net.palaya.chessanalyzer.desktop.script.ScriptLine
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * AUDIO through the real JSONL protocol and a real Python process (the `fake` backend: a 440 Hz
 * tone proportional to the text, 100 ms of silence either side). Every number the manifest
 * records is re-derived here with an independent reader (`javax.sound.sampled`), not taken from
 * the worker's reply or from [Wav].
 */
class AudioStageTest {

    private fun script(gameId: String, texts: List<String>) = ScriptFile(
        gameId = gameId, lang = "en", source = "template",
        beats = listOf(ScriptBeat("b000", "template", lines = texts.mapIndexed { i, t -> ScriptLine("b000_l$i", t, t, t) })),
    )

    @Test
    fun fakeBackendManifestMatchesTheWavsOnDisk() {
        val tmp = Files.createTempDirectory("palaya-audio")
        val wd = WorkDir(tmp, "g1").ensureExists()
        val texts = listOf("Short line.", "A line that is exactly twice as long as the other one, give or take.", "Why?")
        val s = script("g1", texts)
        wd.writeAtomic(wd.scriptJson, WorkJson.encodeToString(s))
        val spec = TtsBackendSpec("fake", TestEnv.ttsPython, TestEnv.ttsWorker)

        val manifest = AudioStage().run(wd, s, spec, force = false, from = null, workers = 2)
        assertEquals("fake", manifest.backend.name)
        assertEquals(3, manifest.lines.size)
        for ((i, line) in manifest.lines.withIndex()) {
            val file = wd.dir.resolve(line.wav).toFile()
            val ais = AudioSystem.getAudioInputStream(file)
            val fmt = ais.format
            val frames = ais.frameLength
            val bytes = ais.readAllBytes()
            ais.close()
            val headerMs = frames * 1000.0 / fmt.sampleRate
            println(String.format("%s: header %.1f ms, manifest %d ms, rms %.1f, peak %.1f, lead %d, trail %d",
                line.id, headerMs, line.durationMs, line.rmsDbfs, line.peakDbfs, line.leadingSilenceMs, line.trailingSilenceMs))
            assertEquals("duration from the header", headerMs, line.durationMs.toDouble(), 1.0)
            // The fake's layout: 100 ms pad + max(300, 60 ms/char) + 100 ms pad.
            val expected = 200 + maxOf(300, Math.round(60.0 * texts[i].length).toInt())
            assertEquals(expected.toDouble(), headerMs, 1.0)

            // Independent RMS / peak.
            var sum = 0.0
            var peak = 0
            val n = bytes.size / 2
            for (k in 0 until n) {
                val v = ((bytes[2 * k + 1].toInt() shl 8) or (bytes[2 * k].toInt() and 0xFF)).toShort().toInt()
                sum += v.toDouble() * v
                peak = maxOf(peak, abs(v))
            }
            val rmsDb = 20 * log10(sqrt(sum / n) / 32768.0)
            val peakDb = 20 * log10(peak / 32768.0)
            assertEquals(rmsDb, line.rmsDbfs, 0.11)
            assertEquals(peakDb, line.peakDbfs, 0.11)
            assertEquals("peak of a 0.3 FS tone", -10.5, peakDb, 0.2)
            assertEquals("leading silence is the 100 ms pad", 100.0, line.leadingSilenceMs.toDouble(), 20.0)
            assertEquals("trailing silence is the 100 ms pad", 100.0, line.trailingSilenceMs.toDouble(), 20.0)
            assertTrue(line.rtf != null && !line.cached)
        }

        // Second run: same text, same backend → every line comes from the cache.
        val again = AudioStage().run(wd, s, spec, force = false, from = net.palaya.chessanalyzer.desktop.work.Stage.AUDIO)
        assertTrue("all cached on re-run", again.lines.all { it.cached })
        // Edit one line: only that one is re-synthesized.
        val edited = script("g1", listOf(texts[0], texts[1], "Why not?"))
        wd.writeAtomic(wd.scriptJson, WorkJson.encodeToString(edited))
        val third = AudioStage().run(wd, edited, spec, force = false, from = null)
        assertEquals(listOf(true, true, false), third.lines.map { it.cached })
    }

    @Test
    fun aMissingInterpreterIsAClearFailure() {
        val tmp = Files.createTempDirectory("palaya-audio-bad")
        val wd = WorkDir(tmp, "g2").ensureExists()
        val s = script("g2", listOf("x"))
        wd.writeAtomic(wd.scriptJson, WorkJson.encodeToString(s))
        val spec = TtsBackendSpec("fake", File(tmp.toFile(), "no-python.exe").toPath(), TestEnv.ttsWorker)
        try {
            AudioStage().run(wd, s, spec, force = false, from = null)
            throw AssertionError("expected TtsException")
        } catch (e: TtsException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }
}
