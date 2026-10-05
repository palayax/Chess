package net.palaya.chessanalyzer.desktop.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.core.narration.NotationGuard
import net.palaya.chessanalyzer.desktop.storyboard.Storyboard
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import net.palaya.chessanalyzer.desktop.work.decideStage
import net.palaya.chessanalyzer.desktop.work.sha256Hex
import java.nio.file.Files

// script.json ("palaya.script/1", design §3.3).

@Serializable
data class ScriptFile(
    val schema: String = SCHEMA,
    val gameId: String,
    val lang: String,
    /** "template" for --no-llm; the LLM writer (P3) records its model here. */
    val source: String,
    val beats: List<ScriptBeat>,
) {
    companion object {
        const val SCHEMA = "palaya.script/1"
    }
}

@Serializable
data class ScriptBeat(
    val id: String,
    val source: String,
    val attempts: Int = 0,
    val validatorErrors: List<String> = emptyList(),
    val lines: List<ScriptLine>,
)

/** [text] as written, [spoken] fed to TTS (notation scrubbed), [caption] burned in. */
@Serializable
data class ScriptLine(
    val id: String,
    val text: String,
    val spoken: String,
    val caption: String,
    val emotion: String = "neutral",
)

/**
 * Stage SCRIPT. P1 has only the §5.6 fallback: each beat's templated `fallbackText`, split into
 * TTS-sized lines (one sentence each; very short sentences merged; hard limit
 * [MAX_LINE_CHARS], the Chatterbox-safe bound from §6.2).
 */
class ScriptStage(private val log: (String) -> Unit = ::println) {

    fun run(workDir: WorkDir, storyboard: Storyboard, noLlm: Boolean, force: Boolean, from: Stage?): ScriptFile {
        require(noLlm) { "the LLM script writer is phase P3; P1 needs --no-llm" }
        val fp = Stage.fingerprint(
            Stage.SCRIPT,
            "storyboard=${sha256Hex(Files.readAllBytes(workDir.storyboardJson))}",
            "source=template",
        )
        val existing = read(workDir)
        val decision = decideStage(Stage.SCRIPT, existing != null, workDir.stageRecord(Stage.SCRIPT), fp, force, from)
        if (!decision.run) {
            log("[script] ${decision.reason}; ${existing!!.beats.sumOf { it.lines.size }} lines")
            return existing
        }
        val script = fallback(storyboard)
        workDir.writeAtomic(workDir.scriptJson, WorkJson.encodeToString(script))
        workDir.recordStage(Stage.SCRIPT, fp, mapOf("source" to "template"))
        val words = script.beats.sumOf { b -> b.lines.sumOf { l -> l.spoken.split(Regex("\\s+")).count { it.isNotBlank() } } }
        log("[script] ${decision.reason}: template narration, ${script.beats.size} beats, ${script.beats.sumOf { it.lines.size }} lines, $words words")
        return script
    }

    companion object {
        const val MAX_LINE_CHARS = 320
        private const val MERGE_BELOW_CHARS = 40
        private const val MERGED_MAX_CHARS = 180

        fun read(workDir: WorkDir): ScriptFile? {
            if (!Files.isRegularFile(workDir.scriptJson)) return null
            return try {
                WorkJson.decodeFromString(ScriptFile.serializer(), Files.readString(workDir.scriptJson))
                    .takeIf { it.schema == ScriptFile.SCHEMA }
            } catch (_: Exception) {
                null
            }
        }

        fun fallback(storyboard: Storyboard): ScriptFile = ScriptFile(
            gameId = storyboard.gameId,
            lang = storyboard.lang,
            source = "template",
            beats = storyboard.beats.map { beat ->
                val lines = splitLines(beat.fallbackText).mapIndexed { i, text ->
                    ScriptLine(id = "${beat.id}_l$i", text = text, spoken = NotationGuard.scrub(text), caption = text)
                }
                ScriptBeat(id = beat.id, source = "template", lines = lines)
            },
        )

        /**
         * Sentences, split after `.`/`!`/`?` followed by whitespace — but not inside a decimal
         * ("91.4") and not after a lone initial — then short ones merged with their neighbour, and
         * any sentence over [MAX_LINE_CHARS] broken at the last comma/space before the limit.
         */
        fun splitLines(text: String): List<String> {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) return emptyList()
            val sentences = ArrayList<String>()
            var start = 0
            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                if ((c == '.' || c == '!' || c == '?') && (i + 1 == clean.length || clean[i + 1] == ' ')) {
                    val candidate = clean.substring(start, i + 1).trim()
                    val lastWord = candidate.substringAfterLast(' ')
                    val isInitial = c == '.' && lastWord.length == 2 && lastWord[0].isUpperCase()
                    if (!isInitial) {
                        sentences.add(candidate)
                        start = i + 1
                    }
                }
                i++
            }
            if (start < clean.length) clean.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)

            val merged = ArrayList<String>()
            for (s in sentences) {
                val last = merged.lastOrNull()
                if (last != null && (last.length < MERGE_BELOW_CHARS || s.length < MERGE_BELOW_CHARS) &&
                    last.length + 1 + s.length <= MERGED_MAX_CHARS
                ) {
                    merged[merged.size - 1] = "$last $s"
                } else {
                    merged.add(s)
                }
            }
            return merged.flatMap { hardWrap(it) }
        }

        private fun hardWrap(s: String): List<String> {
            if (s.length <= MAX_LINE_CHARS) return listOf(s)
            val out = ArrayList<String>()
            var rest = s
            while (rest.length > MAX_LINE_CHARS) {
                val window = rest.substring(0, MAX_LINE_CHARS)
                val cut = window.lastIndexOf(", ").takeIf { it > MAX_LINE_CHARS / 2 }?.plus(1)
                    ?: window.lastIndexOf(' ').takeIf { it > 0 }
                    ?: MAX_LINE_CHARS
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }
    }
}
