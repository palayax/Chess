package net.palaya.chessanalyzer.desktop.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.palaya.chessanalyzer.desktop.encode.FfmpegException
import net.palaya.chessanalyzer.desktop.encode.Tools
import net.palaya.chessanalyzer.desktop.timeline.Timeline
import net.palaya.chessanalyzer.desktop.tts.Wav
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import net.palaya.chessanalyzer.desktop.work.decideStage
import net.palaya.chessanalyzer.desktop.work.sha256Hex
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

@Serializable
data class LoudnormMeasure(val inputI: String, val inputTp: String, val inputLra: String, val inputThresh: String, val targetOffset: String)

@Serializable
data class MixMetrics(
    val finishedAt: String,
    val narrationSeconds: Double,
    val buildSeconds: Double,
    val pass1Seconds: Double,
    val pass2Seconds: Double,
    val measured: LoudnormMeasure,
    val targetLufs: Double,
)

/**
 * Stage MIX, narration only in P1 (design §9 steps 1 and 4 without SFX/music):
 *  1. `narration.wav`: 48 kHz mono, each line's samples placed at its timeline `audioStartMs`,
 *     silence elsewhere, exactly `timeline.totalMs` long; lines are never truncated;
 *  2. loudnorm pass 1 measures it (`print_format=json`);
 *  3. pass 2 applies `loudnorm=I=-14:TP=-1.5:LRA=11` with the measured values (so the result is
 *     genuinely −14 LUFS integrated, not the one-pass approximation) and muxes with `video.mp4`
 *     into `review.mp4` (video stream copied, AAC 192k 48 kHz stereo).
 */
class MixStage(private val ffmpeg: Path, private val log: (String) -> Unit = ::println) {

    fun run(workDir: WorkDir, timeline: Timeline, force: Boolean, from: Stage?): Path {
        val fp = Stage.fingerprint(
            Stage.MIX,
            "timeline=${sha256Hex(WorkJson.encodeToString(timeline))}",
            "manifest=${sha256Hex(Files.readAllBytes(workDir.audioManifestJson))}",
            "render=${workDir.recordedFingerprint(Stage.RENDER)}",
            "target=$TARGET_LUFS",
        )
        val decision = decideStage(Stage.MIX, Files.isRegularFile(workDir.reviewMp4), workDir.stageRecord(Stage.MIX), fp, force, from)
        if (!decision.run) {
            log("[mix] ${decision.reason}; ${workDir.reviewMp4}")
            return workDir.reviewMp4
        }
        log("[mix] running: ${decision.reason}")
        val tb = System.nanoTime()
        val total = (timeline.totalMs * RATE / 1000).toInt()
        val mix = IntArray(total)
        for (beat in timeline.beats) for (line in beat.lines) {
            val samples = Wav.readMono16(workDir.dir.resolve(line.wav), RATE)
            val offset = (line.audioStartMs * RATE / 1000).toInt()
            if (offset + samples.size > total) {
                throw IllegalStateException("line ${line.id} runs past the timeline end; the timeline must never truncate audio")
            }
            for (i in samples.indices) mix[offset + i] += samples[i].toInt()
        }
        val pcm = ShortArray(total) { mix[it].coerceIn(-32768, 32767).toShort() }
        Wav.write(workDir.narrationWav, pcm, RATE)
        val buildSec = (System.nanoTime() - tb) / 1e9

        val ln = "loudnorm=I=$TARGET_LUFS:TP=$TARGET_TP:LRA=$TARGET_LRA"
        val p1 = Tools.run(listOf(ffmpeg.toString(), "-hide_banner", "-nostats", "-i", workDir.narrationWav.toString(),
            "-af", "$ln:print_format=json", "-f", "null", "-"))
        if (p1.exitCode != 0) throw FfmpegException("loudnorm pass 1 failed:\n${Tools.tail(p1.stderr)}")
        val m = parseLoudnorm(p1.stderr)
        log("[mix] pass 1: input ${m.inputI} LUFS, TP ${m.inputTp} dBTP, LRA ${m.inputLra}, thresh ${m.inputThresh}")

        val tmpOut = workDir.dir.resolve("review.tmp.mp4")
        Files.deleteIfExists(tmpOut)
        val filter = "[1:a]$ln:measured_I=${m.inputI}:measured_TP=${m.inputTp}:measured_LRA=${m.inputLra}:" +
            "measured_thresh=${m.inputThresh}:offset=${m.targetOffset}:linear=true:print_format=summary," +
            "aresample=$RATE,aformat=sample_fmts=fltp:channel_layouts=stereo[out]"
        val p2 = Tools.run(listOf(ffmpeg.toString(), "-hide_banner", "-nostats", "-y", "-i", workDir.videoMp4.toString(),
            "-i", workDir.narrationWav.toString(), "-filter_complex", filter, "-map", "0:v", "-map", "[out]",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ar", RATE.toString(), "-ac", "2", "-movflags", "+faststart", tmpOut.toString()))
        Files.createDirectories(workDir.logsDir)
        Files.writeString(workDir.logsDir.resolve("mix.ffmpeg.log"), p1.stderr + "\n----- pass 2 -----\n" + p2.stderr)
        if (p2.exitCode != 0) throw FfmpegException("mix/mux failed:\n${Tools.tail(p2.stderr)}")
        Files.move(tmpOut, workDir.reviewMp4, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        workDir.recordStage(Stage.MIX, fp, mapOf("ffmpeg" to ffmpeg.toString()))
        val metrics = MixMetrics(Instant.now().toString(), timeline.totalMs / 1000.0, r3(buildSec), r3(p1.seconds), r3(p2.seconds), m, TARGET_LUFS)
        workDir.writeMetricsSection("mix", WorkJson.encodeToJsonElement(MixMetrics.serializer(), metrics))
        log(String.format(Locale.ROOT, "[mix] wrote %s (narration %.1f s, pass 1 %.1f s, pass 2 %.1f s)",
            workDir.reviewMp4, timeline.totalMs / 1000.0, p1.seconds, p2.seconds))
        return workDir.reviewMp4
    }

    companion object {
        const val RATE = 48_000
        const val TARGET_LUFS = -14.0
        const val TARGET_TP = -1.5
        const val TARGET_LRA = 11.0

        /** The last `{...}` block ffmpeg's loudnorm prints to stderr. */
        fun parseLoudnorm(stderr: String): LoudnormMeasure {
            val start = stderr.lastIndexOf('{')
            val end = stderr.lastIndexOf('}')
            if (start < 0 || end < start) throw FfmpegException("no loudnorm JSON in ffmpeg output:\n${Tools.tail(stderr)}")
            val o = Json.parseToJsonElement(stderr.substring(start, end + 1)).jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content ?: throw FfmpegException("loudnorm JSON lacks $k")
            return LoudnormMeasure(f("input_i"), f("input_tp"), f("input_lra"), f("input_thresh"), f("target_offset"))
        }

        private fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0
    }
}
