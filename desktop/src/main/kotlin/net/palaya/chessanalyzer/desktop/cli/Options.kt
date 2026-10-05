package net.palaya.chessanalyzer.desktop.cli

import net.palaya.chessanalyzer.desktop.work.Stage
import java.nio.file.Path
import java.nio.file.Paths

/** Bad command line; exits with [ExitCode.BAD_INPUT]. */
class UsageException(message: String) : Exception(message)

enum class Quality { FAST, BEST }

/**
 * Every flag of `palaya-review` (docs/PC_PRODUCER_DESIGN.md §12). All are parsed and validated in
 * P0 so the command line is stable from the start; stages that consume the later ones are not
 * implemented yet and say so when reached.
 */
data class ProducerOptions(
    val pgn: Path,
    val output: Path? = null,
    val lang: String = "en",
    val quality: Quality? = null,
    val game: Int = 1,
    val user: String? = null,
    // Engine (§4). depth == null means "from --quality, else 22".
    val depthFlag: Int? = null,
    val multiPv: Int = 3,
    val threads: Int = 8,
    val hashMb: Int = 512,
    val movetimeCapMs: Long = DEFAULT_MOVETIME_CAP_MS,
    val stockfish: Path? = null,
    // Storyboard.
    val thresholdCp: Int = 50,
    val fullMoments: Int = 3,
    val targetSeconds: IntRange = 480..840,
    // Script.
    val noLlm: Boolean = false,
    val llmUrl: String = "http://127.0.0.1:8080",
    val llmModel: Path? = null,
    val llmStrict: Boolean = false,
    val llmSeed: Long? = null,
    // Voice.
    /** P1 default: the Kokoro CPU baseline; the Chatterbox backends arrive with P4. */
    val tts: String = "kokoro",
    /** Interpreter for the TTS worker; default per backend (§11): kokoro/fake → pc/tts/.venv-kokoro. */
    val ttsPython: Path? = null,
    /** CPU threads for the Kokoro backend (measured: 2 is as fast as 8 on this machine). */
    val ttsThreads: Int = 2,
    /** Parallel worker processes (CPU backends only; a GPU backend must stay at 1, §11). null = per backend. */
    val ttsWorkers: Int? = null,
    /** `--tts fake` only: tone length per character of text (default 60 ms, about speech pace). */
    val fakeMsPerChar: Int? = null,
    val ffmpeg: Path? = null,
    val voice: String? = null,
    val wpm: Int = 165,
    // Mix.
    val music: Path? = null,
    val musicUpbeat: Path? = null,
    // Render.
    val encoder: String = "x264",
    val fps: Int = 30,
    val width: Int = 1920,
    val height: Int = 1080,
    // Work dir and stage control.
    val work: Path? = null,
    val from: Stage? = null,
    val only: Stage? = null,
    val force: Boolean = false,
    val keepVideoOnly: Boolean = false,
    val dryRun: Boolean = false,
) {
    /** §12: explicit `--depth` wins; else `--quality fast` = 18, `best` = 26; else [DEFAULT_DEPTH]. */
    val depth: Int get() = depthFlag ?: when (quality) {
        Quality.FAST -> 18
        Quality.BEST -> 26
        null -> DEFAULT_DEPTH
    }

    companion object {
        /**
         * 20, not the design's 22: P0 measured that depth 22 is not needed for the classifier and
         * that the real risk is a truncated search (RUN_LOG "P0"), which the cap below addresses.
         */
        const val DEFAULT_DEPTH = 20

        /**
         * A safety net only. P0's 8 s cap truncated 8 of 34 plies to depth 13-15 while another
         * process loaded the CPU, and misclassified 15...Nxd7; 30 s lets depth 20 finish even at a
         * third of the idle node rate. A ply that still hits it is flagged `capped` (analysis.json,
         * report.json) and counted in the end-of-stage warning.
         */
        const val DEFAULT_MOVETIME_CAP_MS = 30_000L

        val USAGE = """
            |Usage: palaya-review <game.pgn> -o <review.mp4> [options]
            |  --lang en|he  --quality fast|best
            |  --game N                 which game in a multi-game PGN (default 1)
            |  --user NAME              viewer's name (second-person narration, board orientation)
            |  --depth 20 --multipv 3 --threads 8 --hash 512 --movetime-cap 30000
            |  --stockfish PATH         engine binary (default pc/bin/stockfish/stockfish-windows-x86-64-universal.exe)
            |  --threshold 50           significance threshold, cp
            |  --full-moments 3         cap on FULL-treatment moments (max 4)
            |  --target 8:00-14:00      duration window
            |  --no-llm  --llm-url URL  --llm-model PATH  --llm-strict  --llm-seed N
            |  --tts kokoro|fake|chatterbox_turbo|chatterbox_v3|voxcpm2  --voice NAME  --wpm 165
            |  --tts-python PATH        worker interpreter (default pc/tts/.venv-kokoro/Scripts/python.exe)
            |  --tts-threads 2          Kokoro CPU threads per worker
            |  --tts-workers N          parallel worker processes (default 3 for kokoro, 1 otherwise)
            |  --fake-ms-per-char 60    --tts fake: tone length per character (tests use it to shorten runs)
            |  --ffmpeg PATH            ffmpeg.exe (default: PALAYA_FFMPEG, PATH, then the winget install)
            |  --music PATH --music-upbeat PATH
            |  --encoder x264|nvenc  --fps 30  --size 1920x1080
            |  --work DIR               default pc/work
            |  --from STAGE | --only STAGE | --force     stages: ${Stage.entries.joinToString(", ") { it.cliName }}
            |  --keep-video-only
            |  --dry-run                analyze + storyboard, print the per-ply table, beat table and timings
            """.trimMargin()

        fun parse(args: Array<String>): ProducerOptions {
            var o = ProducerOptions(pgn = Paths.get(""))
            var pgn: Path? = null
            var i = 0
            fun value(flag: String): String {
                if (i + 1 >= args.size) throw UsageException("$flag needs a value")
                i++
                return args[i]
            }
            fun int(flag: String, min: Int, max: Int = Int.MAX_VALUE): Int {
                val raw = value(flag)
                val v = raw.toIntOrNull() ?: throw UsageException("$flag expects an integer, got '$raw'")
                if (v !in min..max) throw UsageException("$flag must be in $min..$max, got $v")
                return v
            }
            fun choice(flag: String, allowed: Set<String>): String {
                val v = value(flag)
                if (v !in allowed) throw UsageException("$flag must be one of ${allowed.joinToString("|")}, got '$v'")
                return v
            }
            fun stage(flag: String): Stage {
                val v = value(flag)
                return Stage.parse(v)
                    ?: throw UsageException("$flag: unknown stage '$v' (${Stage.entries.joinToString("|") { it.cliName }})")
            }
            while (i < args.size) {
                val a = args[i]
                o = when (a) {
                    "-o", "--output" -> o.copy(output = Paths.get(value(a)))
                    "--lang" -> o.copy(lang = choice(a, setOf("en", "he")))
                    "--quality" -> o.copy(quality = Quality.valueOf(choice(a, setOf("fast", "best")).uppercase()))
                    "--game" -> o.copy(game = int(a, 1))
                    "--user" -> o.copy(user = value(a))
                    "--depth" -> o.copy(depthFlag = int(a, 1, 99))
                    "--multipv" -> o.copy(multiPv = int(a, 1, 16))
                    "--threads" -> o.copy(threads = int(a, 1, 1024))
                    "--hash" -> o.copy(hashMb = int(a, 1, 65536))
                    "--movetime-cap" -> o.copy(movetimeCapMs = int(a, 1).toLong())
                    "--stockfish" -> o.copy(stockfish = Paths.get(value(a)))
                    "--threshold" -> o.copy(thresholdCp = int(a, 0))
                    "--full-moments" -> o.copy(fullMoments = int(a, 0, 4))
                    "--target" -> o.copy(targetSeconds = parseTarget(value(a)))
                    "--no-llm" -> o.copy(noLlm = true)
                    "--llm-url" -> o.copy(llmUrl = value(a))
                    "--llm-model" -> o.copy(llmModel = Paths.get(value(a)))
                    "--llm-strict" -> o.copy(llmStrict = true)
                    "--llm-seed" -> o.copy(llmSeed = value(a).toLongOrNull() ?: throw UsageException("--llm-seed expects an integer"))
                    "--tts" -> o.copy(tts = choice(a, setOf("kokoro", "fake", "chatterbox_turbo", "chatterbox_v3", "voxcpm2")))
                    "--tts-python" -> o.copy(ttsPython = Paths.get(value(a)))
                    "--tts-threads" -> o.copy(ttsThreads = int(a, 1, 64))
                    "--tts-workers" -> o.copy(ttsWorkers = int(a, 1, 16))
                    "--fake-ms-per-char" -> o.copy(fakeMsPerChar = int(a, 1, 1000))
                    "--ffmpeg" -> o.copy(ffmpeg = Paths.get(value(a)))
                    "--voice" -> o.copy(voice = value(a))
                    "--wpm" -> o.copy(wpm = int(a, 60, 400))
                    "--music" -> o.copy(music = Paths.get(value(a)))
                    "--music-upbeat" -> o.copy(musicUpbeat = Paths.get(value(a)))
                    "--encoder" -> o.copy(encoder = choice(a, setOf("x264", "nvenc")))
                    "--fps" -> o.copy(fps = int(a, 1, 120))
                    "--size" -> parseSize(value(a)).let { (w, h) -> o.copy(width = w, height = h) }
                    "--work" -> o.copy(work = Paths.get(value(a)))
                    "--from" -> o.copy(from = stage(a))
                    "--only" -> o.copy(only = stage(a))
                    "--force" -> o.copy(force = true)
                    "--keep-video-only" -> o.copy(keepVideoOnly = true)
                    "--dry-run" -> o.copy(dryRun = true)
                    else -> {
                        if (a.startsWith("-")) throw UsageException("unknown option '$a'")
                        if (pgn != null) throw UsageException("only one PGN file may be given ('$pgn' and '$a')")
                        pgn = Paths.get(a)
                        o
                    }
                }
                i++
            }
            val file = pgn ?: throw UsageException("no PGN file given")
            if (o.from != null && o.only != null) throw UsageException("--from and --only are mutually exclusive")
            if (o.output == null && !o.dryRun && o.only == null) {
                throw UsageException("-o <review.mp4> is required (or use --dry-run / --only STAGE)")
            }
            return o.copy(pgn = file)
        }

        /** "8:00-14:00" or "480-840" → seconds. */
        internal fun parseTarget(s: String): IntRange {
            val parts = s.split("-")
            if (parts.size != 2) throw UsageException("--target expects MM:SS-MM:SS, got '$s'")
            fun secs(p: String): Int {
                val mmss = p.trim().split(":")
                return when (mmss.size) {
                    1 -> mmss[0].toIntOrNull()
                    2 -> mmss[0].toIntOrNull()?.let { m -> mmss[1].toIntOrNull()?.let { m * 60 + it } }
                    else -> null
                } ?: throw UsageException("--target: bad time '$p'")
            }
            val lo = secs(parts[0])
            val hi = secs(parts[1])
            if (lo <= 0 || hi < lo) throw UsageException("--target: empty window '$s'")
            return lo..hi
        }

        internal fun parseSize(s: String): Pair<Int, Int> {
            val m = Regex("^(\\d+)x(\\d+)$").matchEntire(s) ?: throw UsageException("--size expects WxH, got '$s'")
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
    }
}
