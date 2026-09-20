@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.data.mapper.uciToUiSquarePair
import net.palaya.chessanalyzer.ui.board.BoardArrow
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.components.CommentCard
import net.palaya.chessanalyzer.ui.components.EvalBar
import net.palaya.chessanalyzer.ui.components.MoveList
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.model.Square
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.theme.GreenPrimary

/**
 * The main analysis screen: eval bar + board + move navigation + move list + comment card.
 *
 * State (current ply, board orientation, autoplay) is owned locally for now via
 * `remember { mutableStateOf(...) }`. A later integration pass should hoist this into a
 * `ReviewViewModel` backed by the real game/analysis repositories — the composable
 * signature here (`game: ImportedGame`) is the seam to swap.
 */
@Composable
fun ReviewScreen(
    game: ImportedGame,
    modifier: Modifier = Modifier,
    /** Ply to open at, e.g. when arriving from a key moment or a tactic in the game report. */
    initialPly: Int? = null,
    onShowMeClick: ((MoveRecord) -> Unit)? = null,
    /** Open the textbook example of a pattern the current move carries (ANALYSIS_SPEC §10). */
    onLearnPattern: ((net.palaya.chessanalyzer.core.analysis.TacticType) -> Unit)? = null,
    onViewReportClick: (() -> Unit)? = null,
    onWatchReviewClick: (() -> Unit)? = null,
) {
    var currentPly by remember(game.id, initialPly) {
        mutableIntStateOf(initialPly?.coerceIn(0, (game.moves.size - 1).coerceAtLeast(0)) ?: 0)
    }
    var orientation by remember { mutableStateOf(BoardOrientation.WHITE_DOWN) }
    var autoplay by remember { mutableStateOf(false) }

    val currentMove = game.moves.firstOrNull { it.ply == currentPly }
    val boardState = currentMove?.boardAfter ?: BoardState.startingPosition()
    val evalCp = currentMove?.evalCp ?: 0
    val mateIn = currentMove?.mateInMoves

    val lastMove: Pair<Square, Square>? = remember(currentMove?.uci) {
        currentMove?.uci?.let { uciToUiSquarePair(it) }
    }
    val arrows: List<BoardArrow> = remember(currentMove?.bestMoveUci, currentMove?.uci) {
        val best = currentMove?.bestMoveUci
        if (best != null && best != currentMove.uci) {
            uciToUiSquarePair(best)?.let { (from, to) -> listOf(BoardArrow(from, to, GreenPrimary)) }
                ?: emptyList()
        } else emptyList()
    }
    val checkedKingSquare: Square? = remember(currentMove?.fenAfter) {
        currentMove?.fenAfter?.let { fen ->
            try {
                val pos = Position.fromFen(fen)
                if (pos.isInCheck()) pos.kingSquare(pos.sideToMove).toUiSquare() else null
            } catch (e: Exception) {
                null
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (onWatchReviewClick != null) {
                        TextButton(onClick = onWatchReviewClick) {
                            Text(stringResource(R.string.watch_game_review))
                        }
                    }
                    if (onViewReportClick != null) {
                        TextButton(onClick = onViewReportClick) {
                            Text(stringResource(R.string.review_view_report))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                EvalBar(
                    evalCentipawns = evalCp,
                    mateIn = mateIn,
                    orientationFlipped = orientation == BoardOrientation.BLACK_DOWN,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ChessBoard(
                        board = boardState,
                        orientation = orientation,
                        lastMove = lastMove,
                        checkedKingSquare = checkedKingSquare,
                        arrows = arrows,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            MoveControls(
                onFirst = { currentPly = 0 },
                onPrev = { currentPly = (currentPly - 1).coerceAtLeast(0) },
                onNext = { currentPly = (currentPly + 1).coerceAtMost(game.moves.maxOfOrNull { it.ply } ?: 0) },
                onLast = { currentPly = game.moves.maxOfOrNull { it.ply } ?: 0 },
                onFlip = { orientation = if (orientation == BoardOrientation.WHITE_DOWN) BoardOrientation.BLACK_DOWN else BoardOrientation.WHITE_DOWN },
                autoplay = autoplay,
                onAutoplayToggle = { autoplay = it },
            )

            MoveList(
                moves = game.moves,
                selectedPly = currentPly,
                onMoveSelected = { currentPly = it },
                modifier = Modifier.fillMaxWidth(),
                sequences = game.sequences,
            )

            currentMove?.let { move ->
                CommentCard(
                    move = move,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    // Only when there is a walkthrough to show: the button used to render on every
                    // move (book moves included) and did nothing when tapped.
                    onShowMeClick = onShowMeClick?.takeIf { move.core?.simulation != null }
                        ?.let { callback -> { callback(move) } },
                    onLearnPattern = onLearnPattern,
                )
            } ?: StartPositionCard()
        }
    }
}

@Composable
private fun StartPositionCard() {
    Text(
        text = stringResource(R.string.review_start_position),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun MoveControls(
    onFirst: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onFlip: () -> Unit,
    autoplay: Boolean,
    onAutoplayToggle: (Boolean) -> Unit,
) {
    // The transport controls belong to the move list, which is pinned LTR (see MoveList): "first"
    // and "previous" step towards the start of a left-to-right sequence, so they stay on the
    // left with their icons pointing left in every locale, rather than mirroring as a Row while
    // the icons do not.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onFirst) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.review_first_move))
        }
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.FastRewind, contentDescription = stringResource(R.string.review_previous_move))
        }
        IconToggleButton(checked = autoplay, onCheckedChange = onAutoplayToggle) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.review_autoplay),
                tint = if (autoplay) GreenPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.FastForward, contentDescription = stringResource(R.string.review_next_move))
        }
        IconButton(onClick = onLast) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.review_last_move))
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onFlip) {
            Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.cd_flip_board))
        }
    }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 800)
@Composable
private fun ReviewScreenPreview() {
    ChessAnalyzerTheme {
        ReviewScreen(
            game = PlaceholderData.sampleGame,
            onShowMeClick = {},
            onViewReportClick = {},
        )
    }
}
