package net.palaya.chessanalyzer.desktop.encode

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class FfmpegException(message: String) : RuntimeException(message)

/**
 * Locates ffmpeg/ffprobe: an explicit path (`--ffmpeg`), then `PALAYA_FFMPEG`, then `PATH`, then
 * the winget install location (`winget install Gyan.FFmpeg` puts it under
 * `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_*\ffmpeg-*\bin`, and a process started
 * before the install does not see the PATH change — the Gradle daemon, for one).
 */
object Tools {

    fun ffmpeg(explicit: Path? = null): Path = find("ffmpeg", explicit)

    fun ffprobe(explicit: Path? = null): Path =
        find("ffprobe", explicit?.let { it.resolveSibling(if (isWindows) "ffprobe.exe" else "ffprobe") })

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun find(tool: String, explicit: Path?): Path {
        val exe = if (isWindows) "$tool.exe" else tool
        explicit?.let {
            if (Files.isRegularFile(it)) return it.toAbsolutePath()
            throw FfmpegException("$tool not found at $it")
        }
        System.getenv("PALAYA_FFMPEG")?.let { Paths.get(it).resolveSibling(exe) }?.takeIf { Files.isRegularFile(it) }?.let { return it }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isBlank()) return@forEach
            val p = try { Paths.get(dir.trim('"')).resolve(exe) } catch (_: Exception) { return@forEach }
            if (Files.isRegularFile(p)) return p
        }
        val local = System.getenv("LOCALAPPDATA")
        if (local != null) {
            val pkgs = Paths.get(local, "Microsoft", "WinGet", "Packages")
            if (Files.isDirectory(pkgs)) {
                Files.list(pkgs).use { s ->
                    s.filter { it.fileName.toString().startsWith("Gyan.FFmpeg") }.sorted(Comparator.reverseOrder()).toList()
                }.forEach { pkg ->
                    Files.walk(pkg, 3).use { w -> w.filter { it.fileName.toString() == exe && it.parent.fileName.toString() == "bin" }.findFirst() }
                        .orElse(null)?.let { return it }
                }
            }
        }
        throw FfmpegException("$tool not found (pass --ffmpeg PATH, set PALAYA_FFMPEG, or `winget install Gyan.FFmpeg`)")
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val seconds: Double)

    /** Runs a short tool invocation to completion, capturing both streams. */
    fun run(cmd: List<String>, timeoutSec: Long = 3600): Result {
        val t0 = System.nanoTime()
        val p = ProcessBuilder(cmd).start()
        p.outputStream.close()
        var err = ""
        val errThread = Thread { err = p.errorStream.bufferedReader().readText() }.apply { isDaemon = true; start() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw FfmpegException("timed out: ${cmd.joinToString(" ")}")
        }
        errThread.join(10_000)
        return Result(p.exitValue(), out, err, (System.nanoTime() - t0) / 1e9)
    }

    fun tail(text: String, lines: Int = 40): String = text.lines().takeLast(lines).joinToString("\n")
}
