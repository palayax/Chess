package net.palaya.chessanalyzer.data.mapper

import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.KeyMoment as CoreKeyMoment
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport as CorePlayerReport
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.ImportedGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stretch of R1b (ANALYSIS_SPEC §7.1): the card text is written before the user says which side
 * they were, so it names colours; when they say, the move cards and the key-moment cards are written
 * again as "you" / "your opponent", exactly as the Summary's buckets and headings already are. With no
 * side, or "Not me", the neutral colour wording is kept.
 */
class SideCommentaryMappingTest {

    /** 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6?? 4.Qxf7#, with the engine's numbers a real analysis would carry. */
    private val sans = listOf("e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#")

    private val mate = TacticInstance(
        TacticType.MATE_NET, Color.WHITE, "h5f7", listOf(Square.fromAlgebraic("e8")), listOf(Square.fromAlgebraic("f7")),
        10_000, "Qxf7# is checkmate.", 0.95
    )

    private fun annotations(): List<MoveAnnotation> {
        var pos = Position.startPosition()
        return sans.mapIndexed { i, san ->
            val move = pos.parseSan(san)
            val before = pos
            pos = pos.makeMove(move)
            val ply = i + 1
            val blunder = ply == 6
            val last = ply == 7
            MoveAnnotation(
                ply = ply, moveNumber = (ply + 1) / 2, color = move.color, san = before.moveToSan(move), uci = move.toUci(),
                fenBefore = before.toFen(), fenAfter = pos.toFen(),
                classification = when {
                    blunder -> CoreClassification.BLUNDER
                    last -> CoreClassification.GREAT
                    else -> CoreClassification.GOOD
                },
                loss = if (blunder) 50.0 else 0.0, winPercentBefore = 55.0, winPercentAfter = if (blunder) 5.0 else 55.0,
                evalBeforeCp = 0, evalAfterCp = 0,
                mateInBefore = if (last) 1 else null,
                mateInAfter = when { blunder -> 1; last -> 0; else -> null },
                bestMoveSan = if (blunder) "g6" else null,
                tacticsPlayed = if (last) listOf(mate) else emptyList(),
                tacticsFound = if (last) listOf(mate) else emptyList(),
                threatsAllowed = if (blunder) listOf(mate) else emptyList(),
            )
        }
    }

    private fun report(): CoreGameReport {
        val a = annotations()
        fun player(c: Color) = CorePlayerReport(c, null, 80.0, 1500, true, emptyMap(), emptyList(), emptyList())
        val text = a[5].text
        return CoreGameReport(
            player(Color.WHITE), player(Color.BLACK), a, null, null, "1-0", List(8) { 50.0 },
            listOf(CoreKeyMoment(6, "Nf6", CoreClassification.BLUNDER, 50.0, text)), 12
        )
    }

    private val game = ImportedGame(
        id = "g", header = GameHeader(white = "w", black = "b"),
        moves = report().annotations.map { it.toMoveRecord() },
    )

    /** What the analysis wrote before any side was known: written by the generator for no side. */
    private fun neutral(): CoreGameReport {
        val generator = net.palaya.chessanalyzer.core.analysis.CommentaryGenerator()
        return generator.regenerate(report(), null)
    }

    /**
     * The blunder card's text for a viewer, as `:core` writes it (the wording rotates per template since
     * C1, so the test asks the generator rather than pinning one phrasing; what is pinned is the shape:
     * the charge names the mate, the beneficiary follows the viewer, "Better was g6" is last).
     */
    private fun blunderText(side: Color?): String =
        net.palaya.chessanalyzer.core.analysis.CommentaryGenerator().regenerate(report(), side).annotations.first { it.ply == 6 }.text

    private val neutralText = blunderText(null)

    private fun blunderCard(game: ImportedGame) = game.moves.first { it.ply == 6 }.annotation

    @Test
    fun theNeutralBlunderTextHasTheExpectedShape() {
        assertTrue(neutralText, Regex("^(This lets White play|Now White can play|This hands White) Qxf7#, which (is checkmate|is mate|delivers checkmate)\\. ").containsMatchIn(neutralText))
        assertTrue(neutralText, neutralText.endsWith(" Better was g6."))
        assertEquals(neutralText.replace("White", "you").replace("Black", "your opponent"), blunderText(Color.WHITE))
        assertEquals(neutralText.replace("White", "your opponent").replace("Black", "you"), blunderText(Color.BLACK))
    }

    @Test
    fun beforeASideIsChosenTheCardNamesColours() {
        val core = neutral()
        val (_, mapped) = applySideToCommentary(game, core, null)
        assertEquals(neutralText, blunderCard(mapped))
        assertEquals(neutralText, core.keyMoments.single().summary)
    }

    @Test
    fun choosingWhiteTurnsTheCardAndTheKeyMomentIntoYouAndYourOpponent() {
        val (core, mapped) = applySideToCommentary(game, neutral(), Color.WHITE)
        // Black's blunder, seen by White: White (you) now has the reply.
        assertEquals(blunderText(Color.WHITE), blunderCard(mapped))
        assertTrue(blunderCard(mapped).orEmpty(), blunderCard(mapped).orEmpty().contains("you"))
        assertEquals(blunderText(Color.WHITE), core.keyMoments.single().summary)
        for (m in mapped.moves) assertFalse("ply ${m.ply}: ${m.annotation}", Regex("\\b(White|Black)\\b").containsMatchIn(m.annotation.orEmpty()))
    }

    @Test
    fun choosingBlackSaysYourOpponentAndNotMeGoesBackToColours() {
        val white = applySideToCommentary(game, neutral(), Color.WHITE)
        val (black, blackGame) = applySideToCommentary(white.second, white.first, Color.BLACK)
        assertEquals(blunderText(Color.BLACK), blunderCard(blackGame))
        assertTrue(blunderCard(blackGame).orEmpty(), blunderCard(blackGame).orEmpty().contains("your opponent"))
        assertEquals(blunderText(Color.BLACK), black.keyMoments.single().summary)

        // "Not me": no side is the user's, so the neutral wording returns - from the already rewritten report.
        val (_, notMeGame) = applySideToCommentary(blackGame, black, null)
        assertEquals(neutralText, blunderCard(notMeGame))
    }

    @Test
    fun theUiReportBuiltFromTheRewrittenCoreReportCarriesTheSameWordingInItsBrilliancyAndKeyMomentCards() {
        val (core, _) = applySideToCommentary(game, neutral(), Color.BLACK)
        val ui = core.toUiReport(GameHeader("w", "b"), net.palaya.chessanalyzer.ui.model.PieceColor.BLACK)
        assertEquals(blunderText(Color.BLACK), ui.keyMoments.single { it.ply == 6 }.description)
    }

    @Test
    fun onlyTheWordingChanges() {
        val core = neutral()
        val (white, mapped) = applySideToCommentary(game, core, Color.WHITE)
        assertEquals(core.annotations.map { it.copy(text = "") }, white.annotations.map { it.copy(text = "") })
        assertEquals(game.moves.map { it.copy(annotation = null, core = null) }, mapped.moves.map { it.copy(annotation = null, core = null) })
        assertEquals(game.id, mapped.id)
        assertEquals(game.sequences, mapped.sequences)
        // The mating move is a GOOD thing for the side that made it, whoever the viewer is.
        val mate = mapped.moves.last().annotation.orEmpty()
        assertTrue(mate, Regex("^Qxf7# (was the only move that kept things on track|is the only move here: the next-best option gives up real ground|is an only move, and nothing else keeps the position on track)\\. (This|It) (is checkmate|is mate|delivers checkmate)\\.$").matches(mate))
        assertTrue(mapped.moves.size == game.moves.size)
    }
}
