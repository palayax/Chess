@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.a11y.isLandscape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import net.palaya.chessanalyzer.core.analysis.PracticePuzzle
import net.palaya.chessanalyzer.core.analysis.PuzzleGoal
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.text.EnglishGrammar
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.data.mapper.toBoardState
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.ui.board.BoardArrow
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.components.costText
import net.palaya.chessanalyzer.ui.model.PuzzleFeedback
import net.palaya.chessanalyzer.ui.model.PuzzlePhase
import net.palaya.chessanalyzer.ui.model.PuzzleUiState
import net.palaya.chessanalyzer.ui.model.justSolved
import net.palaya.chessanalyzer.ui.model.positionAfter
import net.palaya.chessanalyzer.ui.model.practiceCostHalves
import net.palaya.chessanalyzer.ui.model.practiceFollowUp
import net.palaya.chessanalyzer.ui.model.practiceHint
import net.palaya.chessanalyzer.ui.model.practiceShowAnswer
import net.palaya.chessanalyzer.ui.model.practiceTap
import net.palaya.chessanalyzer.ui.model.practiceTargets
import net.palaya.chessanalyzer.ui.model.puzzlePosition
import net.palaya.chessanalyzer.ui.model.puzzleUiStateFromSaved
import net.palaya.chessanalyzer.ui.model.solvedCount
import net.palaya.chessanalyzer.ui.model.uciToUiSquares
import net.palaya.chessanalyzer.ui.theme.GreenPrimary
import net.palaya.chessanalyzer.ui.theme.PracticeResultBadge
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import androidx.compose.ui.unit.min as dpMin

/** One puzzle's state survives rotation and a trip into the walkthrough (rememberSaveable). */
private val PuzzleUiStateSaver = listSaver<PuzzleUiState, Any>(
    save = { it.toSaved() },
    restore = { puzzleUiStateFromSaved(it) },
)

/**
 * Practise your own mistakes (docs/PRACTICE_DESIGN.md §2, §5; R3): "Here's a position from your game
 * where you went wrong. Find the better move." One board, one card, two buttons, tap-to-move.
 *
 * The solver's colour is at the bottom. The first tap must hit an own piece; the second plays the
 * move (promotion auto-queens, castling is the king's move to its destination), and `:core`'s
 * [net.palaya.chessanalyzer.core.analysis.PracticeJudge] answers from the cached MultiPV lines, so
 * the screen never calls the engine. The hint is two taps (the piece, then the square) and neither
 * plays the move. There is no rating, no score, no streak and no timer.
 *
 * Reference (CHESSCOM_REFERENCE_ALIGNMENT.md §2 A): a hint that is first the piece and then the
 * square; a result badge beside the card title so colour is not the only signal.
 *
 * The board is the only fixed part: the card scrolls (`weight(1f)`, no fixed heights), and its
 * buttons wrap, so a large system font cannot clip it.
 *
 * @param initialIndex which puzzle to open at (see `initialPuzzleIndex`); it is read once.
 * @param solvedPlies the plies solved so far, for the closing "Solved 2 of 3".
 * @param explanationFor the first walkthrough sentence of a ply, shown after a correct answer; null
 *   falls back to "Nf6 was the best move."
 * @param onSolved called once, the moment a puzzle is solved.
 * @param onShowMissed opens the walkthrough of this ply; Practise returns here in the same state.
 */
@Composable
fun PracticeScreen(
    puzzles: List<PracticePuzzle>,
    initialIndex: Int,
    solvedPlies: Set<Int>,
    explanationFor: (Int) -> String?,
    onSolved: (Int) -> Unit,
    onShowMissed: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var index by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(0, puzzles.size)) }
    var state by rememberSaveable(stateSaver = PuzzleUiStateSaver) { mutableStateOf(PuzzleUiState()) }
    val puzzle = puzzles.getOrNull(index)
    val atEnd = puzzle == null
    val positionOf = stringResource(R.string.cd_practice_position, index + 1, puzzles.size)

    val landscape = isLandscape()
    // The one primary action, only when there is something to move on to: Next after a solved or
    // revealed puzzle (the last one leads to the closing card), Done on the closing card. Portrait
    // pins it under the content; landscape (too short for a bottom bar) puts it under the card.
    val showPrimary = atEnd || state.phase != PuzzlePhase.QUESTION
    val primaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(
            onClick = {
                if (atEnd) {
                    onDone()
                } else {
                    state = PuzzleUiState()
                    index += 1
                }
            },
            modifier = buttonModifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(stringResource(if (atEnd) R.string.common_done else R.string.practice_next))
        }
    }
    val card: @Composable () -> Unit = {
        if (puzzle != null) {
            PuzzleCard(
                puzzle = puzzle,
                state = state,
                explanation = explanationFor(puzzle.ply),
                onHint = { state = practiceHint(state, puzzle) },
                onShowAnswer = { state = practiceShowAnswer(state, puzzle) },
                onShowMissed = { onShowMissed(puzzle.ply) },
            )
        } else {
            ClosingCard(solved = solvedCount(puzzles, solvedPlies), total = puzzles.size)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.practice_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (!atEnd) {
                        // "2 / 3" starts with an LRM in the resource, so it never reorders in RTL.
                        Text(
                            text = stringResource(R.string.practice_counter, index + 1, puzzles.size),
                            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .semantics { contentDescription = positionOf },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!landscape && showPrimary) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    primaryButton(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (landscape) {
                // Board on the left as tall as the window allows, the card (scrolling) and the
                // primary button on the right.
                val boardSide = dpMin(maxHeight - 16.dp, maxWidth * 0.5f)
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (puzzle != null) {
                        Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            PuzzleBoard(
                                puzzle = puzzle,
                                state = state,
                                boardSide = boardSide,
                                modifier = Modifier.size(boardSide),
                                onStateChange = { next ->
                                    if (justSolved(state, next)) onSolved(puzzle.ply)
                                    state = next
                                },
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            card()
                        }
                        if (showPrimary) primaryButton(Modifier.padding(top = 8.dp))
                    }
                }
            } else {
                // The board is edge to edge in portrait: its 64 squares are the touch targets, and the
                // full width is what gets them to 48 dp on a 384 dp-wide phone (they were 47 dp with
                // 16 dp of padding on each side). Only the card below is padded.
                val boardSide = dpMin(maxWidth, maxHeight * 0.55f)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (puzzle != null) {
                        PuzzleBoard(
                            puzzle = puzzle,
                            state = state,
                            boardSide = boardSide,
                            onStateChange = { next ->
                                if (justSolved(state, next)) onSolved(puzzle.ply)
                                state = next
                            },
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        card()
                    }
                }
            }
        }
    }
}

@Composable
private fun PuzzleBoard(
    puzzle: PracticePuzzle,
    state: PuzzleUiState,
    boardSide: androidx.compose.ui.unit.Dp,
    onStateChange: (PuzzleUiState) -> Unit,
    /** Full width in portrait; exactly the board's size in landscape, where the card sits beside it. */
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val start = remember(puzzle.fenBefore) { puzzlePosition(puzzle) }
    // After a correct move or "Show answer" the board shows the position with that move played.
    val shown: Position = remember(puzzle.fenBefore, state.shownUci) {
        state.shownUci?.let { positionAfter(puzzle.fenBefore, it) } ?: start
    }
    val boardState = remember(shown) { shown.toBoardState() }
    val shownSquares = remember(state.shownUci) { state.shownUci?.let { uciToUiSquares(it) } }
    val arrow = shownSquares?.let { (from, to) -> BoardArrow(from, to, GreenPrimary) }
    val checkedKing = remember(shown) { if (shown.isInCheck()) shown.kingSquare(shown.sideToMove).toUiSquare() else null }
    val interactive = state.acceptsTaps
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ChessBoard(
            board = boardState,
            modifier = Modifier.size(boardSide),
            // The solver's colour is at the bottom, and there is no flip control.
            orientation = if (puzzle.sideToMove == CoreColor.WHITE) BoardOrientation.WHITE_DOWN else BoardOrientation.BLACK_DOWN,
            lastMove = shownSquares,
            checkedKingSquare = checkedKing,
            selectedSquare = if (interactive) state.selected else null,
            legalMoveTargets = if (interactive) practiceTargets(state, puzzle, start) else emptySet(),
            arrows = listOfNotNull(arrow),
            onSquareClick = if (interactive) { square -> onStateChange(practiceTap(state, puzzle, start, square)) } else null,
        )
    }
}

@Composable
private fun PuzzleCard(
    puzzle: PracticePuzzle,
    state: PuzzleUiState,
    explanation: String?,
    onHint: () -> Unit,
    onShowAnswer: () -> Unit,
    onShowMissed: () -> Unit,
) {
    val body = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The title carries the result badge for SOLVED (check) and a wrong attempt (cross).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        if (puzzle.sideToMove == CoreColor.WHITE) R.string.practice_side_to_play_white else R.string.practice_side_to_play_black,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).asHeading(),
                )
                when {
                    state.phase == PuzzlePhase.SOLVED ->
                        PracticeResultBadge(correct = true, contentDescription = stringResource(R.string.practice_badge_correct))
                    state.feedback != PuzzleFeedback.NONE ->
                        PracticeResultBadge(correct = false, contentDescription = stringResource(R.string.practice_badge_wrong))
                }
            }
            Text(text = goalText(puzzle.goal), style = body, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.practice_from_move, puzzle.moveNumber),
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // Everything below that appears after the player acts (wrong, played in the game, the hints,
            // correct, the revealed answer) sits in one polite live region, so TalkBack speaks it the
            // moment it appears instead of leaving a screen-reader user to hunt for what changed.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            when (state.feedback) {
                PuzzleFeedback.NONE -> Unit
                PuzzleFeedback.WRONG -> Text(
                    text = stringResource(R.string.practice_wrong),
                    style = body,
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                )
                PuzzleFeedback.PLAYED_IN_GAME -> {
                    Text(
                        text = stringResource(R.string.practice_played_in_game),
                        style = body,
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    // "This cost about 2 pawns", left out across a mate boundary (the number is not honest there).
                    practiceCostHalves(puzzle)?.let { halves -> Text(text = costText(halves), style = body, modifier = Modifier.fillMaxWidth()) }
                }
            }

            if (state.phase == PuzzlePhase.QUESTION && state.hintStep >= 1) {
                Text(
                    text = stringResource(R.string.practice_hint_piece, stringResource(pieceNameRes(puzzle.hintPiece)), puzzle.hintSquare.toString()),
                    style = body,
                    modifier = Modifier.fillMaxWidth(),
                )
                puzzle.hintMotif?.let { motif ->
                    Text(
                        text = stringResource(R.string.practice_hint_idea, EnglishGrammar.withArticle(tacticTypeName(motif).lowercase())),
                        style = body,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.hintStep >= 2) {
                    Text(
                        text = stringResource(R.string.practice_hint_target, puzzle.bestUci.substring(2, 4)),
                        style = body,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            when (state.phase) {
                PuzzlePhase.QUESTION -> Unit
                PuzzlePhase.SOLVED -> {
                    Text(
                        text = stringResource(R.string.practice_correct),
                        style = body,
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = explanation?.takeIf { it.isNotBlank() } ?: stringResource(R.string.practice_correct_best, puzzle.bestSan),
                        style = body,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PuzzlePhase.REVEALED -> {
                    Text(
                        text = stringResource(R.string.practice_revealed, puzzle.bestSan),
                        style = body,
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    val followUp = practiceFollowUp(puzzle)
                    if (followUp.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.practice_revealed_line, followUp.joinToString(" ")),
                            style = body,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canHint) {
                    OutlinedButton(onClick = onHint, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (state.hintStep == 0) R.string.practice_hint else R.string.practice_hint_second))
                    }
                }
                if (state.canShowAnswer) {
                    TextButton(onClick = onShowAnswer, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.practice_show_answer))
                    }
                }
                // The walkthrough of the miss, only when one exists; Practise comes back here unchanged.
                if (state.phase != PuzzlePhase.QUESTION && puzzle.hasSimulation) {
                    OutlinedButton(onClick = onShowMissed, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.review_show_me_missed))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClosingCard(solved: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.practice_done_all),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.asHeading(),
            )
            Text(
                text = stringResource(R.string.practice_done_solved, solved, total),
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
        }
    }
}

@Composable
private fun goalText(goal: PuzzleGoal): String = when (goal) {
    is PuzzleGoal.MateIn -> pluralStringResource(R.plurals.practice_goal_mate, goal.moves, goal.moves)
    PuzzleGoal.WinRookOrBetter -> stringResource(R.string.practice_goal_rook_or_better)
    PuzzleGoal.WinPiece -> stringResource(R.string.practice_goal_piece)
    PuzzleGoal.WinMaterial -> stringResource(R.string.practice_goal_material)
    PuzzleGoal.BetterMove -> stringResource(R.string.practice_goal_better_move)
}

@StringRes
private fun pieceNameRes(type: CorePieceType): Int = when (type) {
    CorePieceType.PAWN -> R.string.piece_pawn
    CorePieceType.KNIGHT -> R.string.piece_knight
    CorePieceType.BISHOP -> R.string.piece_bishop
    CorePieceType.ROOK -> R.string.piece_rook
    CorePieceType.QUEEN -> R.string.piece_queen
    CorePieceType.KING -> R.string.piece_king
}
