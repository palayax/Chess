package net.palaya.chessanalyzer.desktop.cli

import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParseException
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.desktop.analysis.AnalysisOutcome
import net.palaya.chessanalyzer.desktop.analysis.AnalysisStage
import net.palaya.chessanalyzer.desktop.engine.EngineException
import net.palaya.chessanalyzer.desktop.engine.EngineSettings
import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.desktop.analysis.ReportFactory
import net.palaya.chessanalyzer.desktop.audio.MixStage
import net.palaya.chessanalyzer.desktop.encode.FfmpegException
import net.palaya.chessanalyzer.desktop.encode.RenderStage
import net.palaya.chessanalyzer.desktop.encode.Tools
import net.palaya.chessanalyzer.desktop.script.ScriptStage
import net.palaya.chessanalyzer.desktop.storyboard.Storyboard
import net.palaya.chessanalyzer.desktop.storyboard.StoryboardStage
import net.palaya.chessanalyzer.desktop.timeline.TimelineBuilder
import net.palaya.chessanalyzer.desktop.tts.AudioStage
import net.palaya.chessanalyzer.desktop.tts.TtsBackendSpec
import net.palaya.chessanalyzer.desktop.tts.TtsException
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.system.exitProcess

/** Process exit codes (docs/PC_PRODUCER_DESIGN.md §12), plus 1 for a stage that does not exist yet. */
enum class ExitCode(val code: Int) {
    OK(0), NOT_IMPLEMENTED(1), BAD_INPUT(2), ENGINE(3), LLM(4), TTS(5), FFMPEG(6)
}

/** `palaya-review` entry point: argument parsing, stage dispatch, exit codes. */
object Main {

    const val STOCKFISH_RELATIVE = "pc/bin/stockfish/stockfish-windows-x86-64-universal.exe"

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args).code)
    }

    fun run(args: Array<String>, out: (String) -> Unit = ::println): ExitCode {
        if (args.isEmpty() || args.any { it == "-h" || it == "--help" }) {
            out(ProducerOptions.USAGE)
            return if (args.isEmpty()) ExitCode.BAD_INPUT else ExitCode.OK
        }
        val opts = try {
            ProducerOptions.parse(args)
        } catch (e: UsageException) {
            out("error: ${e.message}")
            out(ProducerOptions.USAGE)
            return ExitCode.BAD_INPUT
        }
        val root = findRepoRoot()
        val stockfish = (opts.stockfish ?: root.resolve(STOCKFISH_RELATIVE)).toAbsolutePath().normalize()
        val workRoot = (opts.work ?: root.resolve("pc/work")).toAbsolutePath().normalize()

        // ---- input ---------------------------------------------------------------------------
        val pgnText = try {
            Files.readString(opts.pgn)
        } catch (e: Exception) {
            out("error: cannot read PGN ${opts.pgn}: ${e.message}")
            return ExitCode.BAD_INPUT
        }
        val t0 = System.nanoTime()
        val games = try {
            PgnParser.parse(pgnText)
        } catch (e: PgnParseException) {
            out("error: ${opts.pgn}: ${e.message}")
            return ExitCode.BAD_INPUT
        }
        val parseSeconds = (System.nanoTime() - t0) / 1e9
        if (games.isEmpty()) {
            out("error: no games in ${opts.pgn}")
            return ExitCode.BAD_INPUT
        }
        if (opts.game > games.size) {
            out("error: --game ${opts.game}, but ${opts.pgn} holds ${games.size} game(s)")
            return ExitCode.BAD_INPUT
        }
        val game = games[opts.game - 1]
        val workDir = WorkDir(workRoot, WorkDir.gameId(game))
        out("palaya-review: ${describe(game)}  gameId ${workDir.gameId}")
        out("  work dir ${workDir.dir}")

        // ---- stages --------------------------------------------------------------------------
        // Every stage up to the last one asked for is walked in order; each decides for itself
        // (fingerprint, §3) whether to run or reuse its cached artifact.
        val last: Stage = when {
            opts.only != null -> opts.only
            opts.dryRun -> Stage.STORYBOARD
            else -> Stage.MIX
        }
        fun done(stage: Stage) = stage.ordinal >= last.ordinal

        val settings = EngineSettings(
            depth = opts.depth,
            multiPv = opts.multiPv,
            movetimeCapMs = opts.movetimeCapMs,
            threads = opts.threads,
            hashMb = opts.hashMb,
        )
        val analysis = try {
            AnalysisStage(stockfish, settings, out).run(
                workDir = workDir,
                game = game,
                gameIndex = opts.game,
                pgnText = pgnText,
                userName = opts.user,
                thresholdCp = opts.thresholdCp,
                force = opts.force,
                from = opts.from,
            )
        } catch (e: EngineException) {
            out("error: stage ANALYZE failed: ${e.message}")
            out("  engine log: ${workDir.dir.resolve("logs/analyze.uci.log")}")
            return ExitCode.ENGINE
        }
        out("[analyze] ${if (analysis.ranEngine) "wrote" else "cached"} ${workDir.analysisJson}")
        out("[analyze] wrote ${workDir.reportJson}")
        if (done(Stage.ANALYZE)) {
            if (opts.dryRun) printDryRun(analysis, parseSeconds, out)
            return ExitCode.OK
        }

        val userColor = ReportFactory.detectUserColor(game, opts.user)
        val storyboard = StoryboardStage(out).run(workDir, game, analysis.report, userColor, opts.thresholdCp, opts.wpm, opts.lang, opts.force, opts.from)
        if (done(Stage.STORYBOARD)) {
            if (opts.dryRun) {
                printDryRun(analysis, parseSeconds, out)
                printBeats(storyboard, out)
            }
            return ExitCode.OK
        }

        if (!opts.noLlm) {
            out("error: the LLM script writer is phase P3 and not implemented yet; pass --no-llm for template narration")
            return ExitCode.NOT_IMPLEMENTED
        }
        val script = ScriptStage(out).run(workDir, storyboard, opts.noLlm, opts.force, opts.from)
        if (done(Stage.SCRIPT)) return ExitCode.OK

        val spec = try {
            ttsSpec(opts, root)
        } catch (e: TtsException) {
            out("error: ${e.message}")
            return ExitCode.TTS
        }
        val manifest = try {
            val workers = opts.ttsWorkers ?: if (opts.tts == "kokoro") 3 else 1
            AudioStage(out).run(workDir, script, spec, opts.force, opts.from, workers)
        } catch (e: TtsException) {
            out("error: stage AUDIO failed: ${e.message}")
            return ExitCode.TTS
        }
        if (done(Stage.AUDIO)) return ExitCode.OK

        val timeline = TimelineBuilder.build(storyboard, script, manifest, opts.fps, opts.width, opts.height)
        workDir.writeAtomic(workDir.timelineJson, WorkJson.encodeToString(timeline))
        out(String.format(Locale.ROOT, "[timeline] %d beats, %.1f s (storyboard estimate %.1f s)",
            timeline.beats.size, timeline.totalMs / 1000.0, storyboard.estimatedSeconds))
        try {
            val ffmpeg = Tools.ffmpeg(opts.ffmpeg)
            RenderStage(ffmpeg, out).run(workDir, storyboard, timeline, opts.encoder, opts.force, opts.from)
            if (done(Stage.RENDER)) return ExitCode.OK
            val review = MixStage(ffmpeg, out).run(workDir, timeline, opts.force, opts.from)
            opts.output?.let { target ->
                val abs = target.toAbsolutePath()
                abs.parent?.let { Files.createDirectories(it) }
                Files.copy(review, abs, StandardCopyOption.REPLACE_EXISTING)
                out("[done] " + abs + "  (" + String.format(Locale.ROOT, "%.1f", timeline.totalMs / 1000.0) + " s)")
                if (opts.keepVideoOnly) {
                    val silent = abs.resolveSibling(abs.fileName.toString().substringBeforeLast('.') + ".video-only.mp4")
                    Files.copy(workDir.videoMp4, silent, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (e: FfmpegException) {
            out("error: ffmpeg: ${e.message}")
            return ExitCode.FFMPEG
        }
        return ExitCode.OK
    }

    /** The worker for `--tts`: interpreter, script and backend flags (§11). */
    fun ttsSpec(opts: ProducerOptions, root: Path): TtsBackendSpec {
        val worker = root.resolve("pc/tts/tts_worker.py")
        return when (opts.tts) {
            "kokoro", "fake" -> {
                val python = opts.ttsPython ?: root.resolve("pc/tts/.venv-kokoro/Scripts/python.exe")
                val args = if (opts.tts == "kokoro") {
                    listOf("--model-dir", root.resolve(KOKORO_MODEL_RELATIVE).toString(), "--sid", "1",
                        "--length-scale", "1.2", "--threads", opts.ttsThreads.toString())
                } else {
                    opts.fakeMsPerChar?.let { listOf("--fake-ms-per-char", it.toString()) } ?: emptyList()
                }
                TtsBackendSpec(opts.tts, python.toAbsolutePath().normalize(), worker, args)
            }
            else -> throw TtsException("TTS backend ${opts.tts} is not implemented yet (phase P4); use --tts kokoro or --tts fake")
        }
    }

    const val KOKORO_MODEL_RELATIVE = "pc/models/kokoro/kokoro-int8-en-v0_19"

    fun printBeats(sb: Storyboard, out: (String) -> Unit) {
        out("")
        out(String.format(Locale.ROOT, "%-5s %-15s %-5s %-11s %-10s %7s  %s", "beat", "kind", "ply", "class", "cue", "est ms", "narration"))
        for (b in sb.beats) {
            out(String.format(Locale.ROOT, "%-5s %-15s %-5s %-11s %-10s %7d  %s", b.id, b.kind, b.ply?.toString() ?: "-",
                b.classification ?: "", b.board.firstOrNull()?.cue ?: "", b.estimatedMs + b.holdAfterMs, b.fallbackText.take(70)))
        }
        out(String.format(Locale.ROOT, "storyboard: %d beats, estimated %.0f s", sb.beats.size, sb.estimatedSeconds))
    }

    private fun describe(game: PgnGame): String {
        val w = game.tags["White"] ?: "?"
        val b = game.tags["Black"] ?: "?"
        return "$w vs $b, ${game.result}, ${game.moves.size} plies"
    }

    /** The `--dry-run` per-ply table plus stage timings. */
    fun printDryRun(a: AnalysisOutcome, parseSeconds: Double, out: (String) -> Unit) {
        val dto = a.reportDto
        out("")
        out(String.format(Locale.ROOT, "%-4s %-14s %-7s %-7s %-11s %-10s %3s %7s", "ply", "move", "before", "after", "class", "best", "d", "sec"))
        for (p in dto.plies) {
            val best = if (p.playedBest) "=" else (p.bestMoveSan ?: "-")
            val sec = a.perPositionSeconds.getOrNull(p.ply - 1)?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
            val depth = a.evals[p.ply - 1].depth
            val tags = buildList {
                if (p.checkmate) add("checkmate")
                p.tacticsFound.forEach { add("+${it.name}") }
                p.tacticsMissed.forEach { add("missed ${it.name}") }
            }.joinToString(", ")
            out(String.format(Locale.ROOT, "%-4d %-14s %-7s %-7s %-11s %-10s %3d %7s  %s",
                p.ply, p.label, p.evalBefore.text, p.evalAfter.text, p.classification, best, depth, sec, tags))
        }
        val finalSec = a.perPositionSeconds.lastOrNull()?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
        out(String.format(Locale.ROOT, "%-4s %-14s %-7s %-7s %-11s %-10s %3d %7s", "end", "(final pos)", "", "", dto.termination ?: "", "", a.evals.last().depth, finalSec))
        out("")
        out("opening: ${dto.opening.name ?: "-"} (${dto.opening.eco ?: "-"}; PGN tag ${dto.opening.pgnEcoTag ?: "-"})")
        for (p in listOf(dto.white, dto.black)) {
            out(String.format(Locale.ROOT, "%-5s %-20s accuracy %5.1f  est. rating %4d  %s", p.color, p.name ?: "?", p.accuracy, p.estimatedRating,
                p.classificationCounts.entries.joinToString(" ") { "${it.key}=${it.value}" }))
        }
        out("key moments: " + dto.keyMoments.joinToString("; ") { "${it.label} ${it.classification}" }.ifEmpty { "-" })
        out("")
        out("stage timings (${if (a.ranEngine) "engine ran" else "analysis ${a.decisionReason}"}):")
        out(String.format(Locale.ROOT, "  %-14s %8.3f s", "parsePgn", parseSeconds))
        a.stageSeconds.forEach { (k, v) -> out(String.format(Locale.ROOT, "  %-14s %8.3f s", k, v)) }
        val searched = a.perPositionSeconds.filterNotNull()
        if (searched.isNotEmpty()) {
            out(String.format(Locale.ROOT, "  per ply: mean %.2f s, max %.2f s over %d positions", searched.average(), searched.max(), searched.size))
        }
    }

    /**
     * The repo root: the first ancestor of the working directory that holds both
     * `settings.gradle.kts` and `pc/`; failing that, the one above the installDist output this
     * class was loaded from; failing that, the working directory.
     */
    fun findRepoRoot(): Path {
        fun isRoot(p: Path) = Files.isRegularFile(p.resolve("settings.gradle.kts")) && Files.isDirectory(p.resolve("pc"))
        var p: Path? = Paths.get("").toAbsolutePath()
        while (p != null) {
            if (isRoot(p)) return p
            p = p.parent
        }
        val codeSource = try {
            Paths.get(Main::class.java.protectionDomain.codeSource.location.toURI())
        } catch (_: Exception) {
            null
        }
        var q = codeSource
        while (q != null) {
            if (isRoot(q)) return q
            q = q.parent
        }
        return Paths.get("").toAbsolutePath()
    }

}
