package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.pgn.PgnGame
import kotlin.math.max

/**
 * Orchestrates the whole analysis pipeline into one [GameReport] — ANALYSIS_SPEC.md section 5
 * (found/missed buckets), 7 (commentary) and the report shape from Contract.kt.
 *
 * Pure and synchronous: all engine calls happen outside, in the app layer, which supplies the
 * pre-computed [PositionEval]s. [classifier] and [tacticsDetector] are injected so this class
 * depends only on the shared interfaces from Contract.kt, never on a concrete `core.tactics`
 * implementation.
 */
class GameAnalyzer(
    private val classifier: MoveClassifier,
    private val tacticsDetector: TacticsDetector,
    private val commentary: CommentaryGenerator = CommentaryGenerator(),
    private val simulationBuilder: SimulationBuilder = SimulationBuilder()
) {

    /**
     * @param evals one [PositionEval] per ply position PLUS the final position, i.e.
     *   `evals.size == game.moves.size + 1`. `evals[i]` is the position before `game.moves[i]`
     *   is played (and thus also the position after `game.moves[i - 1]`); `evals.last()` is the
     *   final position of the game.
     * @param userColor the color the report's UI is oriented around; currently informational
     *   only (both [PlayerReport]s are always produced — the app groups by [userColor] itself).
     * @param book the opening book, or null to skip opening detection entirely.
     */
    fun analyze(game: PgnGame, evals: List<PositionEval>, userColor: Color?, book: OpeningBook?): GameReport {
        require(evals.size == game.moves.size + 1) {
            "evals must have one entry per ply plus the final position " +
                "(expected ${game.moves.size + 1}, got ${evals.size})"
        }

        val startPos = game.startFen?.let { Position.fromFen(it) } ?: Position.startPosition()
        val evalGraphWhite = evals.map { WinProbability.winPercentForColor(it, Color.WHITE) }

        val annotations = ArrayList<MoveAnnotation>()
        var pos = startPos

        for (i in game.moves.indices) {
            val pgnMove = game.moves[i]
            val evalBefore = evals[i]
            val evalAfter = evals[i + 1]
            val mover = pgnMove.color
            val move = pos.parseUci(pgnMove.uci)
            val posAfter = pos.makeMove(move)

            val isBookPos = book?.isBookPosition(pos.toFen()) ?: false
            val isBookMove = book?.isBookPosition(posAfter.toFen()) ?: false
            val classification = classifier.classify(
                pos, move, evalBefore, evalAfter, isBookPos, isBookMove, ply = i + 1
            )

            val winBefore = WinProbability.winPercentForColor(evalBefore, mover)
            val winAfter = WinProbability.winPercentForColor(evalAfter, mover)
            val loss = max(0.0, winBefore - winAfter)
            val moveAccuracy = AccuracyCalculator.moveAccuracy(loss)

            val bestLine = evalBefore.best
            // Line 2, White-relative, for the found-tactic significance gate (spec §9.6). The
            // perspective flip is derived from the FEN exactly as cpWhiteRelative does for line 1.
            val secondBestCp = evalBefore.secondBest?.let { line ->
                val cp = WinProbability.cpOfLine(line)
                if (WinProbability.sideToMoveOf(evalBefore.fen) == Color.WHITE) cp else -cp
            }
            val bestMoveUci = bestLine?.pvUci?.firstOrNull()
            val bestMoveSan = bestMoveUci?.let { safeSan(pos, it) }
            val bestLineSan = buildLineSan(pos, bestLine?.pvUci ?: emptyList())

            val playedPv = pvStartingWith(evalBefore, move.toUci())
            // Raw detector output for the played move. Kept whole for the commentary templates,
            // which describe what a move actually did regardless of how well it scored.
            val tacticsPlayed = tacticsDetector.detect(pos, move, playedPv)
            // Spec 5.4 RECOGNISED BY MOVER: only a move classified BEST/GREAT/BRILLIANT counts as
            // the player having spotted the motif. BOOK/EXCELLENT/GOOD moves that happen to trip a
            // detector are NOT recognised tactics, so `isGood` is deliberately not used here.
            val tacticsFound = if (classification in RECOGNISED_CLASSIFICATIONS) tacticsPlayed else emptyList()

            val tacticsMissed = if (bestMoveUci != null && bestMoveUci != move.toUci() && loss >= 5.0) {
                val bestMove = safeParseUci(pos, bestMoveUci)
                if (bestMove != null) {
                    tacticsDetector.detect(pos, bestMove, bestLine?.pvUci ?: emptyList())
                } else emptyList()
            } else emptyList()

            val threatsAllowed = evalAfter.best?.pvUci?.firstOrNull()?.let { oppUci ->
                val oppMove = safeParseUci(posAfter, oppUci)
                if (oppMove != null) tacticsDetector.detect(posAfter, oppMove, evalAfter.best!!.pvUci) else emptyList()
            } ?: emptyList()

            val simulation = tacticsMissed.maxByOrNull { it.confidence }?.let { tactic ->
                simulationBuilder.build(pos, bestLine?.pvUci ?: emptyList(), tactic)
            }

            val text = commentary.generate(
                classification = classification,
                moveSan = pgnMove.san,
                move = move,
                positionBefore = pos,
                positionAfter = posAfter,
                loss = loss,
                bestMoveSan = bestMoveSan,
                mateInBefore = bestLine?.mateIn,
                tacticsFound = tacticsPlayed,
                tacticsMissed = tacticsMissed,
                threatsAllowed = threatsAllowed,
                userColor = userColor
            )

            annotations.add(
                MoveAnnotation(
                    ply = i + 1,
                    moveNumber = pgnMove.moveNumber,
                    color = mover,
                    san = pgnMove.san,
                    uci = pgnMove.uci,
                    fenBefore = pgnMove.positionFenBefore,
                    fenAfter = pgnMove.positionFenAfter,
                    classification = classification,
                    loss = loss,
                    winPercentBefore = winBefore,
                    winPercentAfter = winAfter,
                    evalBeforeCp = WinProbability.cpWhiteRelative(evalBefore),
                    evalAfterCp = WinProbability.cpWhiteRelative(evalAfter),
                    mateInBefore = WinProbability.mateWhiteRelative(evalBefore),
                    mateInAfter = WinProbability.mateWhiteRelative(evalAfter),
                    evalSecondBestCp = secondBestCp,
                    bestMoveSan = bestMoveSan,
                    bestMoveUci = bestMoveUci,
                    bestLineSan = bestLineSan,
                    moveAccuracy = moveAccuracy,
                    openingName = book?.lookup(pgnMove.positionFenAfter)?.name,
                    tacticsFound = tacticsFound,
                    tacticsMissed = tacticsMissed,
                    threatsAllowed = threatsAllowed,
                    text = text,
                    simulation = simulation
                )
            )

            pos = posAfter
        }

        val openingEntry = book?.longestMatchingOpening(game)
        val lowConfidence = annotations.size < RatingEstimator.LOW_CONFIDENCE_PLY_THRESHOLD

        val white = buildPlayerReport(Color.WHITE, game, annotations, evalGraphWhite, lowConfidence)
        val black = buildPlayerReport(Color.BLACK, game, annotations, evalGraphWhite, lowConfidence)

        val keyMoments = annotations
            .filter { it.classification.isMistake }
            .sortedByDescending { it.loss }
            .take(5)
            .sortedBy { it.ply }
            .map { KeyMoment(it.ply, it.san, it.classification, it.loss, it.text) }

        return GameReport(
            white = white,
            black = black,
            annotations = annotations,
            openingName = openingEntry?.name,
            openingEco = openingEntry?.eco,
            result = game.result,
            evalGraph = evalGraphWhite,
            keyMoments = keyMoments,
            analysisDepth = evals.firstOrNull()?.depth ?: 0
        )
    }

    private fun buildPlayerReport(
        color: Color,
        game: PgnGame,
        annotations: List<MoveAnnotation>,
        evalGraphWhite: List<Double>,
        lowConfidence: Boolean
    ): PlayerReport {
        val playerMoves = annotations.filter { it.color == color }
        val nonBook = playerMoves.filter { it.classification != MoveClassification.BOOK }
        val losses = nonBook.map { it.loss }
        val seriesIndices = nonBook.map { it.ply }

        val rawAccuracy = AccuracyCalculator.gameAccuracy(losses, evalGraphWhite, seriesIndices)
        val accuracy = AccuracyCalculator.round1dp(rawAccuracy)
        val rating = RatingEstimator.estimate(accuracy)

        val classificationCounts = playerMoves.groupingBy { it.classification }.eachCount()
        val tacticsFound = playerMoves.flatMap { it.tacticsFound }.filter { it.byColor == color }
        val tacticsMissed = playerMoves.flatMap { it.tacticsMissed }.filter { it.byColor == color }

        val tagName = game.tags[if (color == Color.WHITE) "White" else "Black"]

        return PlayerReport(
            color = color,
            name = tagName,
            accuracy = accuracy,
            estimatedRating = rating,
            lowConfidence = lowConfidence,
            classificationCounts = classificationCounts,
            tacticsFound = tacticsFound,
            tacticsMissed = tacticsMissed
        )
    }

    private fun pvStartingWith(eval: PositionEval, moveUci: String): List<String> =
        eval.lines.firstOrNull { it.pvUci.firstOrNull() == moveUci }?.pvUci ?: emptyList()

    private fun buildLineSan(start: Position, pvUci: List<String>): List<String> {
        val sanList = ArrayList<String>()
        var pos = start
        for (uci in pvUci) {
            val move = safeParseUci(pos, uci) ?: break
            sanList.add(pos.moveToSan(move))
            pos = pos.makeMove(move)
        }
        return sanList
    }

    private fun safeSan(pos: Position, uci: String): String? =
        safeParseUci(pos, uci)?.let { pos.moveToSan(it) }

    private fun safeParseUci(pos: Position, uci: String) =
        try {
            pos.parseUci(uci)
        } catch (e: Exception) {
            null
        }

    companion object {
        /**
         * ANALYSIS_SPEC.md 5.4 — the classification gate on the "recognised by mover" bucket.
         * Intentionally narrower than [MoveClassification.isGood].
         */
        internal val RECOGNISED_CLASSIFICATIONS = setOf(
            MoveClassification.BEST,
            MoveClassification.GREAT,
            MoveClassification.BRILLIANT
        )
    }
}
