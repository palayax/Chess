package net.palaya.chessanalyzer.desktop

import net.palaya.chessanalyzer.desktop.cli.Main
import net.palaya.chessanalyzer.desktop.encode.FfmpegException
import net.palaya.chessanalyzer.desktop.encode.Tools
import org.junit.Assert.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the tests find the repo and its tools. A missing tool is a **failure**, never a skip
 * (docs/PC_PRODUCER_DESIGN.md §13; CLAUDE.md "assumeTrue can pass vacuously").
 */
object TestEnv {
    val repoRoot: Path by lazy {
        val prop = System.getProperty("palaya.repoRoot")
            ?: throw AssertionError("system property palaya.repoRoot not set (desktop/build.gradle.kts sets it)")
        Paths.get(prop).toAbsolutePath()
    }

    val stockfish: Path by lazy {
        val p = repoRoot.resolve(Main.STOCKFISH_RELATIVE)
        if (!Files.isRegularFile(p)) fail("Stockfish binary missing at $p — required by the default suite, not optional")
        p
    }

    /** The interpreter the `fake`/`kokoro` worker runs under (pc/tts/.venv-kokoro). */
    val ttsPython: Path by lazy {
        val p = repoRoot.resolve("pc/tts/.venv-kokoro/Scripts/python.exe")
        if (!Files.isRegularFile(p)) fail("TTS Python missing at $p — create it with `uv venv -p 3.13 pc/tts/.venv-kokoro`")
        p
    }

    val ttsWorker: Path by lazy {
        val p = repoRoot.resolve("pc/tts/tts_worker.py")
        if (!Files.isRegularFile(p)) fail("TTS worker missing at $p")
        p
    }

    val ffmpeg: Path by lazy {
        try { Tools.ffmpeg() } catch (e: FfmpegException) { fail("ffmpeg required by the default suite: ${e.message}"); throw e }
    }

    val ffprobe: Path by lazy {
        try { Tools.ffprobe() } catch (e: FfmpegException) { fail("ffprobe required by the default suite: ${e.message}"); throw e }
    }

    fun fixture(name: String): Path {
        val p = repoRoot.resolve("fixtures").resolve(name)
        if (!Files.isRegularFile(p)) fail("fixture missing: $p")
        return p
    }

    /** Threads for every engine test: 4, so a concurrently running benchmark keeps its CPU. */
    const val THREADS = 4
    const val HASH_MB = 128

    val record: Boolean get() = System.getProperty("palaya.record") == "true"
}
