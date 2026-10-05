package net.palaya.chessanalyzer.desktop.work

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.palaya.chessanalyzer.core.pgn.PgnGame
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/** One `stages.json` entry (design §3). */
@Serializable
data class StageRecord(
    val inputFingerprint: String,
    val configVersion: Int,
    val finishedAt: String,
    val tools: Map<String, String> = emptyMap(),
)

/**
 * `pc/work/<gameId>/` — the per-game work directory (docs/PC_PRODUCER_DESIGN.md §3).
 *
 * Every artifact is written through [writeAtomic]: to `<name>.tmp` in the same directory, then
 * renamed over the target, so a kill mid-write can never leave a truncated JSON that a later run
 * would trust.
 */
class WorkDir(val root: Path, val gameId: String) {

    val dir: Path = root.resolve(gameId)

    val gamePgn: Path get() = dir.resolve("game.pgn")
    val analysisJson: Path get() = dir.resolve("analysis.json")
    val reportJson: Path get() = dir.resolve("report.json")
    val stagesJson: Path get() = dir.resolve("stages.json")
    val metricsJson: Path get() = dir.resolve("metrics.json")
    val storyboardJson: Path get() = dir.resolve("storyboard.json")
    val scriptJson: Path get() = dir.resolve("script.json")
    val audioDir: Path get() = dir.resolve("audio")
    val audioManifestJson: Path get() = audioDir.resolve("manifest.json")
    val timelineJson: Path get() = dir.resolve("timeline.json")
    val videoMp4: Path get() = dir.resolve("video.mp4")
    val narrationWav: Path get() = dir.resolve("narration.wav")
    val reviewMp4: Path get() = dir.resolve("review.mp4")
    val logsDir: Path get() = dir.resolve("logs")

    /** The recorded input fingerprint of [stage], or "" when it never finished. */
    fun recordedFingerprint(stage: Stage): String = stageRecord(stage)?.inputFingerprint ?: ""

    fun ensureExists(): WorkDir = apply { Files.createDirectories(dir) }

    fun writeAtomic(target: Path, text: String) = writeAtomic(target, text.toByteArray(Charsets.UTF_8))

    fun writeAtomic(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun readStages(): Map<String, StageRecord> =
        if (Files.isRegularFile(stagesJson)) {
            WorkJson.decodeFromString<Map<String, StageRecord>>(Files.readString(stagesJson))
        } else emptyMap()

    fun stageRecord(stage: Stage): StageRecord? = readStages()[stage.name]

    fun recordStage(stage: Stage, fingerprint: String, tools: Map<String, String>) {
        val all = readStages().toMutableMap()
        all[stage.name] = StageRecord(fingerprint, stage.configVersion, Instant.now().toString(), tools)
        val ordered = all.entries.sortedBy { Stage.parse(it.key)?.ordinal ?: Int.MAX_VALUE }
            .associate { it.key to it.value }
        writeAtomic(stagesJson, WorkJson.encodeToString(ordered))
    }

    fun readMetrics(): JsonObject =
        if (Files.isRegularFile(metricsJson)) {
            WorkJson.parseToJsonElement(Files.readString(metricsJson)).jsonObject
        } else JsonObject(emptyMap())

    /** Replaces one top-level section of `metrics.json` (e.g. "analyze"), keeping the others. */
    fun writeMetricsSection(section: String, value: JsonElement) {
        val merged = readMetrics().toMutableMap()
        merged[section] = value
        writeAtomic(metricsJson, WorkJson.encodeToString(JsonObject.serializer(), JsonObject(merged)))
    }

    companion object {
        /** Length of the hex gameId (design §3: `sha256(normalizedPgn)[:12]`). */
        const val GAME_ID_LENGTH = 12

        /**
         * The game's identity for caching. Design §3 says the normalization "strips comments/clocks
         * and whitespace" so the same game from chess.com and from a hand-typed PGN cache to the same
         * dir; a hand-typed copy also differs in tags, move numbering and annotation glyphs, so the
         * normalization goes one step further: the parsed game's start position plus its main-line
         * moves in UCI. Everything the engine stage depends on is in it, and nothing else is.
         */
        fun normalizedPgn(game: PgnGame): String = buildString {
            append(game.startFen ?: "startpos")
            append('\n')
            game.moves.joinTo(this, " ") { it.uci }
        }

        fun gameId(game: PgnGame): String = sha256Hex(normalizedPgn(game)).take(GAME_ID_LENGTH)

        /** sha256 of a (possibly ~100 MB) file, streamed. */
        fun sha256OfFile(path: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }
    }
}
