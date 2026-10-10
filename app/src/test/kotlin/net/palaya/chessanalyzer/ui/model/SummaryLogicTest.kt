package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.ui.theme.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure logic behind the Summary hub (UX step U5): side chooser, key moments, the move-quality table. */
class SummaryLogicTest {

    // ---- Side chooser state ----

    @Test
    fun sideChoiceRoundTripsThroughItsStoredName() {
        for (choice in SideChoice.entries) {
            assertEquals(choice, SideChoice.fromStored(choice.storedName))
        }
    }

    @Test
    fun storedNamesKeepTheOldColourValuesAndAddNotMe() {
        // GameRepository has always stored WHITE/BLACK (Color.name) or null; those must still read back.
        assertEquals(SideChoice.WHITE, SideChoice.fromStored("WHITE"))
        assertEquals(SideChoice.BLACK, SideChoice.fromStored("BLACK"))
        assertEquals("NOT_ME", SideChoice.NOT_ME.storedName)
        assertNull(SideChoice.UNKNOWN.storedName)
    }

    @Test
    fun anUnreadableStoredNameMeansUnknownNotAnError() {
        assertEquals(SideChoice.UNKNOWN, SideChoice.fromStored(null))
        assertEquals(SideChoice.UNKNOWN, SideChoice.fromStored(""))
        assertEquals(SideChoice.UNKNOWN, SideChoice.fromStored("UNKNOWN"))
        assertEquals(SideChoice.UNKNOWN, SideChoice.fromStored("purple"))
    }

    @Test
    fun notMeAndUnknownBothHaveNoColourButStayDistinct() {
        assertNull(SideChoice.NOT_ME.color)
        assertNull(SideChoice.UNKNOWN.color)
        assertEquals(PieceColor.WHITE, SideChoice.WHITE.color)
        assertEquals(PieceColor.BLACK, SideChoice.BLACK.color)
        assertEquals(SideChoice.UNKNOWN, SideChoice.of(null))
        // The point of the explicit state: Practise must tell these two apart.
        assertTrue(SideChoice.NOT_ME != SideChoice.UNKNOWN)
    }

    @Test
    fun theReportExposesNotMeSeparatelyFromAnUnknownSide() {
        val base = PlaceholderData.sampleReport
        assertEquals(SideChoice.WHITE, base.copy(userColor = PieceColor.WHITE, notMe = false).sideChoice)
        assertEquals(SideChoice.UNKNOWN, base.copy(userColor = null, notMe = false).sideChoice)
        assertEquals(SideChoice.NOT_ME, base.copy(userColor = null, notMe = true).sideChoice)
    }

    @Test
    fun anAnswerGivenEarlierWinsOverAutoDetectionOnReopen() {
        assertEquals(SideChoice.NOT_ME, resolveSide(SideChoice.NOT_ME, PieceColor.WHITE))
        assertEquals(SideChoice.BLACK, resolveSide(SideChoice.BLACK, PieceColor.WHITE))
        assertEquals(SideChoice.WHITE, resolveSide(SideChoice.UNKNOWN, PieceColor.WHITE))
        assertEquals(SideChoice.UNKNOWN, resolveSide(SideChoice.UNKNOWN, null))
    }

    @Test
    fun aFamousGameOpensAsNotMeUnlessTheUserAnsweredOtherwise() {
        // G1-device: the library hands the analysis an initial side of "Not me".
        assertEquals(SideChoice.NOT_ME, resolveSide(SideChoice.UNKNOWN, null, initial = SideChoice.NOT_ME))
        // Even when the Settings name happens to match one of the players: nobody in a famous game is the user.
        assertEquals(SideChoice.NOT_ME, resolveSide(SideChoice.UNKNOWN, PieceColor.WHITE, initial = SideChoice.NOT_ME))
        // The user can still change it on the Summary, and that answer is stored per game and wins on reopen.
        assertEquals(SideChoice.WHITE, resolveSide(SideChoice.WHITE, null, initial = SideChoice.NOT_ME))
        assertEquals(SideChoice.BLACK, resolveSide(SideChoice.BLACK, PieceColor.WHITE, initial = SideChoice.NOT_ME))
        // A shared or pasted game has no initial side: today's behaviour, the detection decides.
        assertEquals(SideChoice.WHITE, resolveSide(SideChoice.UNKNOWN, PieceColor.WHITE, initial = SideChoice.UNKNOWN))
        assertEquals(SideChoice.UNKNOWN, resolveSide(SideChoice.UNKNOWN, null, initial = SideChoice.UNKNOWN))
    }

    // ---- Username written by the chooser ----

    private val header = GameHeader(white = "MorphyFan1857", black = "DukeAndCount")

    @Test
    fun choosingASideRemembersThatPlayersNameWhenNoneIsSet() {
        assertEquals("MorphyFan1857", usernameToRemember(header, SideChoice.WHITE, ""))
        assertEquals("DukeAndCount", usernameToRemember(header, SideChoice.BLACK, "   "))
    }

    @Test
    fun anExistingUsernameIsNeverOverwritten() {
        assertNull(usernameToRemember(header, SideChoice.WHITE, "someone_else"))
    }

    @Test
    fun notMeAndUnknownRememberNothing() {
        assertNull(usernameToRemember(header, SideChoice.NOT_ME, ""))
        assertNull(usernameToRemember(header, SideChoice.UNKNOWN, ""))
    }

    @Test
    fun aMissingOrPlaceholderPgnNameIsNotWorthRemembering() {
        assertNull(usernameToRemember(GameHeader(white = "White", black = "Black"), SideChoice.WHITE, ""))
        assertNull(usernameToRemember(GameHeader(white = "White", black = "Black"), SideChoice.BLACK, ""))
        assertNull(usernameToRemember(GameHeader(white = "?", black = "?"), SideChoice.WHITE, ""))
        assertNull(usernameToRemember(GameHeader(white = "  ", black = "x"), SideChoice.WHITE, ""))
    }

    // ---- Key-moment selection ----

    private fun moment(ply: Int, cls: MoveClassification, loss: Double = 10.0) =
        KeyMoment(ply = ply, moveNumber = (ply + 1) / 2, san = "x$ply", classification = cls, description = "d$ply", loss = loss)

    private fun report(userColor: PieceColor?, vararg moments: KeyMoment, notMe: Boolean = false) =
        PlaceholderData.sampleReport.copy(userColor = userColor, notMe = notMe, keyMoments = moments.toList())

    @Test
    fun withNoSideEverythingIsOneListInGameOrder() {
        val r = report(
            null,
            moment(15, MoveClassification.BLUNDER),
            moment(4, MoveClassification.MISTAKE),
            moment(10, MoveClassification.BRILLIANT),
        )
        val m = selectSummaryMoments(r)
        assertEquals(listOf(4, 10, 15), m.primary.map { it.ply })
        assertTrue(m.opponent.isEmpty())
    }

    @Test
    fun withASideKnownYourMomentsComeFirstAndTheOpponentsAreSeparate() {
        // Odd plies are White's.
        val r = report(
            PieceColor.WHITE,
            moment(2, MoveClassification.BLUNDER), // black
            moment(5, MoveClassification.MISTAKE), // white
            moment(8, MoveClassification.MISS), // black
            moment(9, MoveClassification.BRILLIANT), // white
        )
        val m = selectSummaryMoments(r)
        assertEquals(listOf(5, 9), m.primary.map { it.ply })
        assertEquals(listOf(2, 8), m.opponent.map { it.ply })
    }

    @Test
    fun asBlackTheSplitFlips() {
        val r = report(PieceColor.BLACK, moment(2, MoveClassification.BLUNDER), moment(5, MoveClassification.MISTAKE))
        val m = selectSummaryMoments(r)
        assertEquals(listOf(2), m.primary.map { it.ply })
        assertEquals(listOf(5), m.opponent.map { it.ply })
    }

    @Test
    fun notMeKeepsNeutralFramingSoThereIsNoOpponentGroup() {
        val r = report(null, moment(2, MoveClassification.BLUNDER), moment(5, MoveClassification.MISTAKE), notMe = true)
        val m = selectSummaryMoments(r)
        assertEquals(listOf(2, 5), m.primary.map { it.ply })
        assertTrue(m.opponent.isEmpty())
    }

    @Test
    fun inaccuraciesAreNotKeyMomentsAndPliesAreNotRepeated() {
        val r = report(
            null,
            moment(3, MoveClassification.INACCURACY),
            moment(5, MoveClassification.MISTAKE),
            moment(5, MoveClassification.MISTAKE),
        )
        assertEquals(listOf(5), selectSummaryMoments(r).primary.map { it.ply })
    }

    @Test
    fun aGameWithNothingToFixHasNoMoments() {
        assertTrue(selectSummaryMoments(report(null)).isEmpty)
        assertTrue(selectSummaryMoments(report(PieceColor.WHITE, moment(3, MoveClassification.INACCURACY))).isEmpty)
    }

    // ---- Move-quality table (A4) ----

    private fun counts(vararg pairs: Pair<MoveClassification, Int>): List<ClassificationCount> {
        val m = pairs.toMap()
        return MoveClassification.entries.map { ClassificationCount(it, m[it] ?: 0) }
    }

    private val white = counts(
        MoveClassification.BRILLIANT to 1, MoveClassification.BEST to 9, MoveClassification.EXCELLENT to 3,
        MoveClassification.GOOD to 2, MoveClassification.BOOK to 6, MoveClassification.MISTAKE to 1,
        MoveClassification.BLUNDER to 1,
    )
    private val black = counts(
        MoveClassification.BEST to 6, MoveClassification.GOOD to 5, MoveClassification.BOOK to 5,
        MoveClassification.FORCED to 2, MoveClassification.INACCURACY to 3, MoveClassification.MISS to 1,
    )

    /** The ten classes the owner listed, in the app's own order. */
    private val ownerTen = listOf(
        MoveClassification.BRILLIANT, MoveClassification.GREAT, MoveClassification.BEST,
        MoveClassification.EXCELLENT, MoveClassification.GOOD, MoveClassification.BOOK,
        MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.MISS,
        MoveClassification.BLUNDER,
    )

    @Test
    fun theTableAlwaysHasTheTenClassesInTheAppsOrderZerosIncluded() {
        val rows = classificationRows(white, black)
        // Forced is the eleventh class and black has two, so it is the last row; the ten come first, in order.
        assertEquals(ownerTen + MoveClassification.FORCED, rows.map { it.badge })
        // GREAT is 0 / 0 and still there.
        val great = rows.single { it.badge == MoveClassification.GREAT }
        assertEquals(0, great.white)
        assertEquals(0, great.black)
        // Nothing is folded into a neighbour: Best, Excellent and Good each keep their own count.
        assertEquals(9, rows.single { it.badge == MoveClassification.BEST }.white)
        assertEquals(3, rows.single { it.badge == MoveClassification.EXCELLENT }.white)
        assertEquals(2, rows.single { it.badge == MoveClassification.GOOD }.white)
        assertEquals(6, rows.single { it.badge == MoveClassification.BOOK }.white)
        assertEquals(5, rows.single { it.badge == MoveClassification.BOOK }.black)
        assertEquals(1, rows.single { it.badge == MoveClassification.MISTAKE }.white)
        assertEquals(1, rows.single { it.badge == MoveClassification.MISS }.black)
        assertEquals(1, rows.single { it.badge == MoveClassification.BLUNDER }.white)
        assertEquals(3, rows.single { it.badge == MoveClassification.INACCURACY }.black)
    }

    @Test
    fun forcedGetsARowOnlyWhenASideHasAForcedMove() {
        val none = classificationRows(counts(MoveClassification.BEST to 4), counts(MoveClassification.GOOD to 4))
        assertEquals(ownerTen, none.map { it.badge })
        val oneSide = classificationRows(counts(MoveClassification.FORCED to 1), counts())
        assertEquals(ownerTen + MoveClassification.FORCED, oneSide.map { it.badge })
        assertEquals(1, oneSide.last().white)
    }

    @Test
    fun anEmptyGameStillShowsEveryClassForBothSides() {
        val rows = classificationRows(emptyList(), emptyList())
        assertEquals(ownerTen, rows.map { it.badge })
        assertTrue(rows.all { it.white == 0 && it.black == 0 })
    }

    @Test
    fun theRowsNeverLoseAMove() {
        val rows = classificationRows(white, black)
        assertEquals(white.sumOf { it.count }, rows.sumOf { it.white })
        assertEquals(black.sumOf { it.count }, rows.sumOf { it.black })
    }
}
