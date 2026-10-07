package net.palaya.chessanalyzer.data.mapper

import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.KeyMoment as CoreKeyMoment
import net.palaya.chessanalyzer.core.analysis.CommentaryGenerator
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClassification
import net.palaya.chessanalyzer.core.analysis.MoveSequence as CoreMoveSequence
import net.palaya.chessanalyzer.core.analysis.MoveSequenceDetector
import net.palaya.chessanalyzer.core.analysis.PlayerReport as CorePlayerReport
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticSignificance
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.narration.GameSummarySentence
import net.palaya.chessanalyzer.core.narration.NarrationLocales
import net.palaya.chessanalyzer.core.narration.NarrationStrings
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.ui.model.ClassificationCount
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.GameReport
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.KeyMoment
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.MoveSequenceView
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.PlayerReport
import net.palaya.chessanalyzer.ui.model.TacticGroup
import net.palaya.chessanalyzer.ui.model.TacticOccurrence
import net.palaya.chessanalyzer.ui.theme.MoveClassification

/**
 * Maps `:core`'s analysis result types (Contract.kt) onto the UI's presentation-only models
 * (`ui.model.GameModels`). This is the one place that translates between the two worlds so
 * screens/components never import `:core` types directly (except where a component is happy
 * to carry the real [net.palaya.chessanalyzer.core.analysis.TacticSimulation] straight
 * through — see [MoveRecord.simulation]).
 *
 * [MoveClassification] (ui.theme) and [CoreClassification] (core.analysis) are kept as two
 * separate enums on purpose (per the integration brief): the app enum is a *presentation*
 * concern (color + glyph + string resource), the core enum is the source of truth for the
 * classification list itself. Their entry names are identical by construction, so mapping
 * between them is a plain [Enum.valueOf] rather than a hand-maintained `when`.
 */
fun CoreClassification.toUi(): MoveClassification = MoveClassification.valueOf(name)

fun CoreColor.toUiColor(): PieceColor = if (this == CoreColor.WHITE) PieceColor.WHITE else PieceColor.BLACK

fun PieceColor.toCoreColor(): CoreColor = if (this == PieceColor.WHITE) CoreColor.WHITE else CoreColor.BLACK

/** Builds a [GameHeader] from a parsed PGN's tag pairs, falling back to generic names. */
fun PgnGame.toHeader(): GameHeader {
    val white = tags["White"]?.takeIf { it.isNotBlank() } ?: "White"
    val black = tags["Black"]?.takeIf { it.isNotBlank() } ?: "Black"
    return GameHeader(
        white = white,
        black = black,
        whiteElo = tags["WhiteElo"]?.toIntOrNull(),
        blackElo = tags["BlackElo"]?.toIntOrNull(),
        event = tags["Event"]?.takeIf { it.isNotBlank() && it != "?" },
        date = tags["Date"]?.takeIf { it.isNotBlank() && it != "????.??.??" },
        result = result,
    )
}

/** Converts one `:core` [MoveAnnotation] into a UI [MoveRecord], with the board pre-rendered. */
fun MoveAnnotation.toMoveRecord(): MoveRecord = MoveRecord(
    ply = ply,
    san = san,
    classification = classification.toUi(),
    evalCp = evalAfterCp,
    isMateScore = mateInAfter != null,
    mateInMoves = mateInAfter,
    evalBeforeCp = evalBeforeCp,
    mateInBefore = mateInBefore,
    bestMoveSan = bestMoveSan,
    annotation = text.takeIf { it.isNotBlank() },
    boardAfter = fenToBoardState(fenAfter),
    moverColor = color.toUiColor(),
    uci = uci,
    bestMoveUci = bestMoveUci,
    fenBefore = fenBefore,
    fenAfter = fenAfter,
    core = this,
)

/**
 * The commentary of a game written for the side the user was (ANALYSIS_SPEC §7.1).
 *
 * The analysis writes every card text before the user has said which side they played, so the
 * text names the sides by colour ("This lets White play ..."). Once the side is known - detected
 * from the username, chosen on the Summary, or "Not me" - the text is written again from the
 * annotations alone (no engine, no detector), so the cards and the key moments say "you" and
 * "your opponent" exactly as the Summary's buckets and headings do. With no side, or "Not me"
 * ([sideColor] null), the neutral colour wording is kept.
 *
 * @return the `:core` report with its [MoveAnnotation.text]s and key-moment summaries rewritten,
 *   and [game] with its move cards rebuilt from it. Nothing but wording differs.
 */
fun applySideToCommentary(
    game: ImportedGame,
    report: CoreGameReport,
    sideColor: CoreColor?,
    generator: CommentaryGenerator = CommentaryGenerator(),
): Pair<CoreGameReport, ImportedGame> {
    val rewritten = generator.regenerate(report, sideColor)
    return rewritten to game.copy(moves = rewritten.annotations.map { it.toMoveRecord() })
}

/**
 * Groups the [TacticInstance]s selected by [selector] out of each of this player's annotations
 * into [TacticGroup]s by motif type, so the same motif recurring many times collapses into one
 * row with a count instead of a wall of duplicates. Each occurrence keeps its own ply so it
 * stays individually tappable/navigable.
 */
private fun List<MoveAnnotation>.toTacticGroups(
    selector: (MoveAnnotation) -> List<TacticInstance>,
): List<TacticGroup> = flatMap { annotation ->
    selector(annotation).map { tactic ->
        tactic.type to TacticOccurrence(
            ply = annotation.ply,
            moveNumber = annotation.moveNumber,
            san = annotation.san,
            description = tactic.description.takeIf { it.isNotBlank() } ?: tactic.type.displayName,
        )
    }
}
    .groupBy({ it.first }, { it.second })
    .map { (type, occurrences) -> TacticGroup(type, occurrences, hasReference = TacticReferenceLibrary.hasReference(type)) }
    .sortedByDescending { it.count }

/**
 * @param significant the annotations after the §9.6 gate — what the buckets show.
 * @param minor the annotations holding only what the gate removed — what the disclosure shows.
 */
private fun CorePlayerReport.toUi(significant: List<MoveAnnotation>, minor: List<MoveAnnotation>): PlayerReport {
    val counts = MoveClassification.entries.map { uiClass ->
        val coreClass = CoreClassification.valueOf(uiClass.name)
        ClassificationCount(uiClass, classificationCounts[coreClass] ?: 0)
    }
    val own = significant.filter { it.color == color }
    val ownMinor = minor.filter { it.color == color }
    return PlayerReport(
        name = name ?: if (color == CoreColor.WHITE) "White" else "Black",
        accuracyPercent = accuracy,
        estimatedRating = estimatedRating,
        counts = counts,
        lowConfidence = lowConfidence,
        tacticsFound = own.toTacticGroups { it.tacticsFound },
        tacticsMissed = own.toTacticGroups { it.tacticsMissed },
        minorTacticsFound = ownMinor.toTacticGroups { it.tacticsFound },
        minorTacticsMissed = ownMinor.toTacticGroups { it.tacticsMissed },
    )
}

private fun CoreKeyMoment.toUi(): KeyMoment = KeyMoment(
    ply = ply,
    moveNumber = (ply + 1) / 2,
    san = san,
    classification = classification.toUi(),
    description = summary,
    loss = swing,
)

/** How many brilliancies the Summary lists beside the mistakes; they are rare, so this rarely bites. */
internal const val MAX_BRILLIANT_MOMENTS = 2

/**
 * `:core`'s key moments are the five costliest *mistakes*. The Summary also wants the game's
 * brilliancies ("Your key moments" is what to fix and what to be proud of), so they are added here
 * from the annotations rather than by changing `:core`. Sorted by ply, one entry per ply.
 */
internal fun List<MoveAnnotation>.withBrilliancies(mistakes: List<KeyMoment>): List<KeyMoment> {
    val brilliant = filter { it.classification == CoreClassification.BRILLIANT }
        .take(MAX_BRILLIANT_MOMENTS)
        .map {
            KeyMoment(
                ply = it.ply,
                moveNumber = it.moveNumber,
                san = it.san,
                classification = it.classification.toUi(),
                description = it.text,
            )
        }
    return (mistakes + brilliant).distinctBy { it.ply }.sortedBy { it.ply }
}

/** The presentation view of one `:core` run of plies (ANALYSIS_SPEC §9.3). */
fun CoreMoveSequence.toUi(): MoveSequenceView = MoveSequenceView(
    startPly = startPly,
    endPly = endPly,
    classification = classification.toUi(),
    label = label,
    totalSwingCp = totalSwingCp,
)

/**
 * The runs of plies that should read as one coloured unit (ANALYSIS_SPEC §9.3). Detected in
 * `:core` so the review move list, the eval graph and the narration all group the same way
 * instead of each inventing its own rule.
 *
 * @param tacticThresholdCp the §9.6 gate applied first, so a run of minor motifs does not get
 *   a "Relative pin" band across the move list. 0 = ungated.
 */
fun List<MoveAnnotation>.toSequenceViews(tacticThresholdCp: Int = 0): List<MoveSequenceView> =
    MoveSequenceDetector.detect(TacticSignificance.prune(this, tacticThresholdCp)).map { it.toUi() }

/**
 * Converts a full `:core` [CoreGameReport] into the UI-facing [GameReport] the report screen
 * draws. [userColor] is which side the app user played, when known (see
 * `AnalysisService.detectUserColor`) — the report screen uses it to frame the tactics sections
 * from the user's perspective ("you"/"your opponent") instead of plain White/Black.
 *
 * @param notMe the user answered "Not me" to "Which side were you?": no side is the user's, so
 *   [userColor] is dropped and the report keeps neutral White/Black framing.
 * @param tacticThresholdCp the significance threshold (ANALYSIS_SPEC §9.6) the four tactic
 *   buckets are gated at — the same value the narration uses. What the gate removes is not lost:
 *   it lands in the `minor*` lists for the report's disclosure row.
 * @param strings the language of the one-sentence summary ([GameReport.summarySentence]); the
 *   narration language, so the sentence and the narrated review never disagree.
 */
fun CoreGameReport.toUiReport(
    header: GameHeader,
    userColor: PieceColor? = null,
    tacticThresholdCp: Int = 0,
    notMe: Boolean = false,
    strings: NarrationStrings = NarrationLocales.default,
): GameReport {
    // evalGraph in :core is white win-percent (0..100), which isn't what EvalGraph's
    // centipawn-based rendering expects. Rebuild a white-relative-centipawn series from the
    // annotations themselves (one entry per ply plus the starting position) instead.
    val startCp = annotations.firstOrNull()?.evalBeforeCp ?: 0
    val evalHistory = listOf(startCp) + annotations.map { it.evalAfterCp }
    val significant = TacticSignificance.prune(annotations, tacticThresholdCp)
    val minor = TacticSignificance.minor(annotations, tacticThresholdCp)

    return GameReport(
        header = header,
        white = white.toUi(significant, minor),
        black = black.toUi(significant, minor),
        evalHistory = evalHistory,
        plyClassifications = annotations.map { it.classification.toUi() },
        sequences = annotations.toSequenceViews(tacticThresholdCp),
        keyMoments = annotations.withBrilliancies(keyMoments.map { it.toUi() }),
        // "Not me" and a colour are mutually exclusive: an explicit "not me" wins.
        userColor = userColor.takeUnless { notMe },
        tacticThresholdCp = tacticThresholdCp,
        notMe = notMe,
        openingName = openingName,
        plysWithSimulation = annotations.filter { it.simulation != null }.map { it.ply }.toSet(),
        plysWithBestLine = annotations.filter { net.palaya.chessanalyzer.core.analysis.BestLines.bestFor(it) != null }.map { it.ply }.toSet(),
        // Written from the same side facts the report carries, so the side chooser re-maps it too.
        summarySentence = GameSummarySentence.text(this, userColor?.toCoreColor(), notMe, strings),
    )
}
