package net.palaya.chessanalyzer.desktop.encode

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.desktop.render.FrameRenderer
import net.palaya.chessanalyzer.desktop.render.SpecBuilder
import net.palaya.chessanalyzer.desktop.storyboard.Storyboard
import net.palaya.chessanalyzer.desktop.timeline.Timeline
import net.palaya.chessanalyzer.desktop.timeline.TimelineBuilder
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import net.palaya.chessanalyzer.desktop.work.decideStage
import net.palaya.chessanalyzer.desktop.work.sha256Hex
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class RenderMetrics(
    val finishedAt: String,
    val encoder: String,
    val frames: Int,
    val renderedFrames: Int,
    val reusedFrames: Int,
    val videoSeconds: Double,
    /** Time inside Java2D (spec building + drawing), summed. */
    val renderSeconds: Double,
    /** Time blocked writing frames into ffmpeg's stdin (the encoder's back-pressure). */
    val pipeSeconds: Double,
    /** Time between closing stdin and ffmpeg exiting (encoder flush). */
    val encodeTailSeconds: Double,
    val wallSeconds: Double,
    /** frames / wall: the end-to-end render+encode rate. */
    val fps: Double,
    /** rendered frames / renderSeconds: the Java2D draw rate. */
    val drawFps: Double,
)

/**
 * Stage RENDER: timeline → frames → ffmpeg rawvideo pipe → silent `video.mp4` (design §8.2).
 * A frame whose spec equals the previous one is not drawn again; its bytes are re-sent.
 */
class RenderStage(private val ffmpeg: Path, private val log: (String) -> Unit = ::println) {

    fun run(workDir: WorkDir, storyboard: Storyboard, timeline: Timeline, encoder: String, force: Boolean, from: Stage?): Path {
        val fp = Stage.fingerprint(
            Stage.RENDER,
            "timeline=${sha256Hex(WorkJson.encodeToString(timeline))}",
            "storyboard=${sha256Hex(Files.readAllBytes(workDir.storyboardJson))}",
            "encoder=$encoder",
            "renderer=$RENDERER_VERSION",
        )
        val decision = decideStage(Stage.RENDER, Files.isRegularFile(workDir.videoMp4), workDir.stageRecord(Stage.RENDER), fp, force, from)
        if (!decision.run) {
            log("[render] ${decision.reason}; ${workDir.videoMp4}")
            return workDir.videoMp4
        }
        val frames = TimelineBuilder.frameCount(timeline)
        log(String.format(Locale.ROOT, "[render] running: %s; %d frames (%.1f s at %d fps), %s",
            decision.reason, frames, timeline.totalMs / 1000.0, timeline.fps, encoder))
        Files.createDirectories(workDir.logsDir)
        val logFile = workDir.logsDir.resolve("render.ffmpeg.log")
        val tmpOut = workDir.dir.resolve("video.tmp.mp4")
        Files.deleteIfExists(tmpOut)
        val codec = when (encoder) {
            "nvenc" -> listOf("-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "21", "-b:v", "0")
            else -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20")
        }
        val cmd = listOf(ffmpeg.toString(), "-hide_banner", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24",
            "-s", "${timeline.width}x${timeline.height}", "-r", timeline.fps.toString(), "-i", "pipe:0") +
            codec + listOf("-pix_fmt", "yuv420p", "-g", "60", "-movflags", "+faststart", tmpOut.toString())
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(logFile.toFile()).start()

        val renderer = FrameRenderer(timeline.width, timeline.height)
        val specs = SpecBuilder(storyboard, timeline)
        val t0 = System.nanoTime()
        var renderNs = 0L
        var pipeNs = 0L
        var reused = 0
        var lastImg: java.awt.image.BufferedImage? = null
        var beatIdx = 0
        try {
            BufferedOutputStream(proc.outputStream, 1 shl 22).use { out ->
                for (f in 0 until frames) {
                    val tMs = f * 1000L / timeline.fps
                    while (beatIdx < timeline.beats.size - 1 && tMs >= timeline.beats[beatIdx].endMs) beatIdx++
                    val r0 = System.nanoTime()
                    val img = renderer.render(specs.specAt(tMs, timeline.beats[beatIdx]))
                    renderNs += System.nanoTime() - r0
                    if (img === lastImg) reused++
                    lastImg = img
                    val w0 = System.nanoTime()
                    out.write(renderer.bytes(img))
                    pipeNs += System.nanoTime() - w0
                    if (f % (timeline.fps * 30) == 0 && f > 0) {
                        log(String.format(Locale.ROOT, "[render] %d/%d frames (%.0f%%), %.1f fps", f, frames, 100.0 * f / frames,
                            f / ((System.nanoTime() - t0) / 1e9)))
                    }
                }
            }
        } catch (e: IOException) {
            proc.waitFor(10, TimeUnit.SECONDS)
            throw FfmpegException("ffmpeg pipe failed (${e.message}); last log lines:\n${Tools.tail(Files.readString(logFile))}")
        }
        val c0 = System.nanoTime()
        if (!proc.waitFor(600, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw FfmpegException("ffmpeg did not finish encoding; see $logFile")
        }
        val tailSec = (System.nanoTime() - c0) / 1e9
        if (proc.exitValue() != 0) throw FfmpegException("ffmpeg exited ${proc.exitValue()}:\n${Tools.tail(Files.readString(logFile))}")
        Files.move(tmpOut, workDir.videoMp4, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        workDir.recordStage(Stage.RENDER, fp, mapOf("ffmpeg" to ffmpeg.toString(), "encoder" to encoder))

        val wall = (System.nanoTime() - t0) / 1e9
        val m = RenderMetrics(
            finishedAt = Instant.now().toString(),
            encoder = encoder,
            frames = frames,
            renderedFrames = renderer.framesDrawn,
            reusedFrames = reused,
            videoSeconds = r3(frames.toDouble() / timeline.fps),
            renderSeconds = r3(renderNs / 1e9),
            pipeSeconds = r3(pipeNs / 1e9),
            encodeTailSeconds = r3(tailSec),
            wallSeconds = r3(wall),
            fps = r3(frames / wall),
            drawFps = r3(if (renderNs > 0) renderer.framesDrawn / (renderNs / 1e9) else 0.0),
        )
        workDir.writeMetricsSection("render", WorkJson.encodeToJsonElement(RenderMetrics.serializer(), m))
        log(String.format(Locale.ROOT, "[render] wrote %s: %d frames (%d drawn, %d reused), wall %.1f s = %.1f fps; draw %.1f s, pipe %.1f s, encoder tail %.1f s",
            workDir.videoMp4, frames, m.renderedFrames, reused, wall, m.fps, m.renderSeconds, m.pipeSeconds, tailSec))
        return workDir.videoMp4
    }

    companion object {
        const val RENDERER_VERSION = "p1-java2d-1"
        private fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0
    }
}
