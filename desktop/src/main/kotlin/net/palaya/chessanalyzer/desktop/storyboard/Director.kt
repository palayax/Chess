package net.palaya.chessanalyzer.desktop.storyboard

import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.NotationGuard
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import net.palaya.chessanalyzer.desktop.work.decideStage
import java.nio.file.Files

/**
 * Director v0 (docs/PC_PRODUCER_DESIGN.md §14 P1): a thin adapter that turns the segments of
 * core's [VideoScriptGenerator] (the same generator the Android app speaks) into storyboard beats,
 * one beat per segment, the segment's [BoardDirective] as the beat's single board cue.
 *
 * No tiers, no cold open, no FULL cap, no duration budget: those are Director v1 (P2). What v0
 * guarantees is that every chess claim spoken comes from tested `:core` code.
 */
object Director {

    const val VERSION = "v0-generator-adapter"

    fun direct(
        gameId: String,
        game: PgnGame,
        report: GameReport,
        userColor: Color?,
        options: NarrationOptions,
        lang: String = "en",
    ): Storyboard {
        val script = VideoScriptGenerator(userColor).generate(report, game, options)
        return fromScript(gameId, game, script, lang)
    }

    fun fromScript(gameId: String, game: PgnGame, script: VideoScript, lang: String): Storyboard {
        // FEN (placement + side + castling + ep) → the game move that produced it, for the
        // last-move highlight on static cues.
        val lastMoveByFen = HashMap<String, String>()
        game.moves.forEach { lastMoveByFen[fenKey(it.positionFenAfter)] = it.uci }

        val beats = script.segments.map { seg -> beat(seg, lastMoveByFen) }
        val chapters = script.chapters.mapNotNull { ch ->
            beats.getOrNull(ch.startSegmentIndex)?.let { StoryChapter(ch.title, it.id) }
        }
        val h = script.header
        return Storyboard(
            gameId = gameId,
            lang = lang,
            director = VERSION,
            header = StoryHeader(
                white = h?.whiteName ?: (game.tags["White"] ?: "White"),
                black = h?.blackName ?: (game.tags["Black"] ?: "Black"),
                whiteElo = h?.whiteRating,
                blackElo = h?.blackRating,
                result = h?.result ?: game.result,
                opening = h?.openingName,
                eco = h?.openingEco,
                date = h?.dateText,
            ),
            userColor = script.userColor?.name,
            chapters = chapters,
            beats = beats,
            estimatedSeconds = script.totalEstimatedMs / 1000.0,
            summary = StorySummary(script.whiteAccuracy, script.blackAccuracy, script.whiteEstimatedRating, script.blackEstimatedRating),
        )
    }

    fun beatId(index: Int) = "b" + index.toString().padStart(3, '0')

    private fun beat(seg: ScriptSegment, lastMoveByFen: Map<String, String>): Beat {
        val cue = when (val d = seg.board) {
            is BoardDirective.Hold -> BoardCue(cue = "hold", fen = d.fen, lastMove = lastMoveByFen[fenKey(d.fen)])
            is BoardDirective.PlayMove -> BoardCue(cue = "play_move", fen = d.fen, uci = listOf(d.uci), san = listOf(d.san))
            is BoardDirective.PlayLine -> BoardCue(cue = "play_line", fen = d.fen, uci = d.uciMoves, san = d.sanMoves, label = d.label)
            is BoardDirective.Annotate -> BoardCue(
                cue = "annotate",
                fen = d.fen,
                arrows = d.arrows.map { ArrowCue(it.fromSquare, it.toSquare, it.role.name) },
                highlights = d.highlightSquares,
                lastMove = lastMoveByFen[fenKey(d.fen)],
            )
            is BoardDirective.Card -> BoardCue(cue = "card", heading = d.heading, lines = d.lines)
        }
        val classification = seg.classification
            ?: (seg.board as? BoardDirective.PlayMove)?.classification
        return Beat(
            id = beatId(seg.index),
            kind = seg.kind.name,
            ply = seg.ply,
            moveNumber = seg.moveNumber,
            color = seg.speakerColor?.name,
            classification = classification?.name,
            caption = seg.caption,
            // The generator already scrubs; this is the belt-and-braces pass §6.5 asks for.
            fallbackText = NotationGuard.scrub(seg.narration),
            estimatedMs = seg.estimatedSpeechMs,
            holdAfterMs = seg.holdAfterMs,
            eval = seg.eval?.let { BeatEval(round2(it.winPercentWhite), it.evalCp, it.mateIn) },
            evalSwingCp = seg.evalSwingCp,
            tactic = seg.tactic?.type?.displayName,
            excursion = seg.kind == SegmentKind.MISSED_TACTIC,
            board = listOf(cue),
        )
    }

    /** The first four FEN fields: clocks differ between a PGN replay and a generator rebuild. */
    fun fenKey(fen: String): String = fen.trim().split(Regex("\\s+")).take(4).joinToString(" ")

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}

/** Stage STORYBOARD: report → `storyboard.json`, cached by fingerprint. */
class StoryboardStage(private val log: (String) -> Unit = ::println) {

    fun run(
        workDir: WorkDir,
        game: PgnGame,
        report: GameReport,
        userColor: Color?,
        thresholdCp: Int,
        wpm: Int,
        lang: String,
        force: Boolean,
        from: Stage?,
    ): Storyboard {
        val fp = Stage.fingerprint(
            Stage.STORYBOARD,
            "analysis=${workDir.recordedFingerprint(Stage.ANALYZE)}",
            "director=${Director.VERSION}",
            "user=${userColor?.name}",
            "threshold=$thresholdCp",
            "wpm=$wpm",
            "lang=$lang",
        )
        val existing = read(workDir)
        val decision = decideStage(Stage.STORYBOARD, existing != null, workDir.stageRecord(Stage.STORYBOARD), fp, force, from)
        if (!decision.run) {
            log("[storyboard] ${decision.reason}; ${existing!!.beats.size} beats")
            return existing
        }
        val t0 = System.nanoTime()
        val options = NarrationOptions(speechWpm = wpm, significanceThresholdCp = thresholdCp)
        val sb = Director.direct(workDir.gameId, game, report, userColor, options, lang)
        workDir.writeAtomic(workDir.storyboardJson, WorkJson.encodeToString(sb))
        workDir.recordStage(Stage.STORYBOARD, fp, mapOf("director" to Director.VERSION))
        val secs = (System.nanoTime() - t0) / 1e9
        log(String.format(java.util.Locale.ROOT, "[storyboard] %s: %d beats, %d chapters, estimated %.0f s (%.3f s)",
            decision.reason, sb.beats.size, sb.chapters.size, sb.estimatedSeconds, secs))
        return sb
    }

    companion object {
        fun read(workDir: WorkDir): Storyboard? {
            if (!Files.isRegularFile(workDir.storyboardJson)) return null
            return try {
                WorkJson.decodeFromString(Storyboard.serializer(), Files.readString(workDir.storyboardJson))
                    .takeIf { it.schema == Storyboard.SCHEMA }
            } catch (_: Exception) {
                null
            }
        }
    }
}
