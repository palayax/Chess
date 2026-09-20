package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseSan
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentaryGeneratorTest {

    private val generator = CommentaryGenerator()

    private fun tactic(
        type: TacticType,
        color: Color = Color.WHITE,
        targets: List<Square> = emptyList(),
        involved: List<Square> = emptyList(),
        swing: Int = 300,
        confidence: Double = 0.95
    ) = TacticInstance(
        type = type, byColor = color, moveUci = "d5f6", targetSquares = targets,
        involvedSquares = involved, materialSwing = swing, confidence = confidence
    )

    @Test
    fun `BLUNDER with a missed hanging piece names the piece and square`() {
        // White knight sits on f6 after the blunder.
        val pos = Position.fromFen("4k3/8/5N2/8/8/8/8/4K3 w - - 0 1")
        val move = pos.parseSan("Kd2")
        val after = pos.makeMove(move)
        val hanging = tactic(TacticType.HANGING_PIECE, color = Color.WHITE, targets = listOf(Square.fromAlgebraic("f6")))

        val text = generator.generate(
            classification = MoveClassification.BLUNDER,
            moveSan = "Kd2",
            move = move,
            positionBefore = pos,
            positionAfter = after,
            loss = 40.0,
            bestMoveSan = "Qe2",
            mateInBefore = null,
            tacticsFound = emptyList(),
            tacticsMissed = listOf(hanging),
            threatsAllowed = emptyList()
        )

        assertTrue("expected 'Blunder' in: $text", text.contains("Blunder"))
        assertTrue("expected 'knight' in: $text", text.contains("knight", ignoreCase = true))
        assertTrue("expected 'f6' in: $text", text.contains("f6"))
        assertTrue("expected 'Qe2' in: $text", text.contains("Qe2"))
        assertTrue("should read as a full sentence ending in '.'", text.trim().endsWith("."))
    }

    @Test
    fun `BRILLIANT with a decoy names the decoyed piece and defended square`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)
        val decoy = tactic(
            TacticType.DECOY, color = Color.WHITE,
            involved = listOf(Square.fromAlgebraic("d7"), Square.fromAlgebraic("b8")),
            targets = listOf(Square.fromAlgebraic("b8"))
        )

        val text = generator.generate(
            classification = MoveClassification.BRILLIANT,
            moveSan = "Rxd7",
            move = move,
            positionBefore = pos,
            positionAfter = after,
            loss = 0.0,
            bestMoveSan = null,
            mateInBefore = null,
            tacticsFound = listOf(decoy),
            tacticsMissed = emptyList(),
            threatsAllowed = emptyList()
        )

        assertTrue(text.contains("Brilliant"))
        assertTrue("expected 'd7' in: $text", text.contains("d7"))
        assertTrue("expected 'b8' in: $text", text.contains("b8"))
        assertTrue(text.trim().endsWith("."))
    }

    @Test
    fun `MISS with a forced mate names the best move and mate distance`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("a3")
        val after = pos.makeMove(move)

        val text = generator.generate(
            classification = MoveClassification.MISS,
            moveSan = "a3",
            move = move,
            positionBefore = pos,
            positionAfter = after,
            loss = 60.0,
            bestMoveSan = "Qb8+",
            mateInBefore = 3,
            tacticsFound = emptyList(),
            tacticsMissed = emptyList(),
            threatsAllowed = emptyList()
        )

        assertTrue(text.contains("Missed win"))
        assertTrue(text.contains("Qb8+"))
        assertTrue(text.contains("3"))
        assertTrue(text.trim().endsWith("."))
    }

    @Test
    fun `FORCED and BOOK produce sensible text with no tactic present`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)

        val forcedText = generator.generate(
            MoveClassification.FORCED, "e4", move, pos, after, 0.0, null, null, emptyList(), emptyList(), emptyList()
        )
        val bookText = generator.generate(
            MoveClassification.BOOK, "e4", move, pos, after, 0.0, null, null, emptyList(), emptyList(), emptyList()
        )

        assertTrue(forcedText.contains("Forced"))
        assertTrue(forcedText.contains("e4"))
        assertTrue(bookText.contains("Book"))
        assertTrue(bookText.trim().isNotEmpty())
    }

    @Test
    fun `every classification produces non-blank text with no tactics available`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)

        for (classification in MoveClassification.entries) {
            val text = generator.generate(
                classification, "e4", move, pos, after, 5.0, "Nf3", null, emptyList(), emptyList(), emptyList()
            )
            assertTrue("classification $classification produced blank text", text.isNotBlank())
            assertTrue("classification $classification text should end with '.': $text", text.trim().endsWith("."))
        }
    }

    @Test
    fun `threatsAllowed appends a 'you allowed' clause for an otherwise good move`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)
        val threat = tactic(TacticType.FORK, color = Color.BLACK, targets = listOf(Square.fromAlgebraic("e5")))

        val text = generator.generate(
            classification = MoveClassification.GOOD,
            moveSan = "e4",
            move = move,
            positionBefore = pos,
            positionAfter = after,
            loss = 3.0,
            bestMoveSan = null,
            mateInBefore = null,
            tacticsFound = emptyList(),
            tacticsMissed = emptyList(),
            threatsAllowed = listOf(threat)
        )

        assertTrue("expected 'allowed' clause in: $text", text.contains("allowed", ignoreCase = true))
        assertTrue(text.contains("fork", ignoreCase = true))
    }

    // -----------------------------------------------------------------------
    // Second person is reserved for the configured user's own plies.
    // -----------------------------------------------------------------------

    /** Plays [san] from [pos] and returns the "you/White/Black allowed …" commentary. */
    private fun threatText(pos: Position, san: String, userColor: Color?, threatBy: Color): String {
        val move = pos.parseSan(san)
        val after = pos.makeMove(move)
        val threat = tactic(TacticType.FORK, color = threatBy, targets = listOf(Square.fromAlgebraic("e5")))
        return generator.generate(
            classification = MoveClassification.GOOD,
            moveSan = san,
            move = move,
            positionBefore = pos,
            positionAfter = after,
            loss = 3.0,
            bestMoveSan = null,
            mateInBefore = null,
            tacticsFound = emptyList(),
            tacticsMissed = emptyList(),
            threatsAllowed = listOf(threat),
            userColor = userColor
        )
    }

    private fun afterE4(): Position {
        val start = Position.startPosition()
        return start.makeMove(start.parseSan("e4"))
    }

    @Test
    fun `the user's own move is addressed in the second person`() {
        val text = threatText(Position.startPosition(), "e4", userColor = Color.WHITE, threatBy = Color.BLACK)
        assertTrue("expected second person in: $text", text.contains("You allowed a fork on e5."))
        assertTrue(!text.contains("White allowed"))
    }

    @Test
    fun `the opponent's move is addressed in the third person by colour`() {
        val text = threatText(afterE4(), "e5", userColor = Color.WHITE, threatBy = Color.WHITE)
        assertTrue("expected third person in: $text", text.contains("Black allowed a fork on e5."))
        assertTrue("must not say 'you' for the opponent: $text", !text.contains("You"))
    }

    @Test
    fun `with no user colour both sides are named by colour`() {
        val whiteText = threatText(Position.startPosition(), "e4", userColor = null, threatBy = Color.BLACK)
        val blackText = threatText(afterE4(), "e5", userColor = null, threatBy = Color.WHITE)

        assertTrue("expected 'White allowed' in: $whiteText", whiteText.contains("White allowed a fork on e5."))
        assertTrue("expected 'Black allowed' in: $blackText", blackText.contains("Black allowed a fork on e5."))
        assertTrue("must not use second person: $whiteText", !whiteText.contains("You"))
        assertTrue("must not use second person: $blackText", !blackText.contains("You"))
    }

    @Test
    fun `a black user's own move is addressed in the second person`() {
        val text = threatText(afterE4(), "e5", userColor = Color.BLACK, threatBy = Color.WHITE)
        assertTrue("expected second person in: $text", text.contains("You allowed a fork on e5."))
        assertTrue(!text.contains("Black allowed"))
    }
}
