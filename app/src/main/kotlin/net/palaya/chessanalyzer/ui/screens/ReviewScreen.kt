@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.data.mapper.uciToUiSquarePair
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.isLandscape
import net.palaya.chessanalyzer.ui.board.BoardArrow
import net.palaya.chessanalyzer.ui.board.BoardBadge
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.components.CommentCard
import net.palaya.chessanalyzer.ui.components.EvalBar
import net.palaya.chessanalyzer.ui.components.MoveList
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.model.Square
import net.palaya.chessanalyzer.ui.model.algebraic
import net.palaya.chessanalyzer.ui.model.boardBadgeFor
import net.palaya.chessanalyzer.ui.model.defaultBoardOrientation
import net.palaya.chessanalyzer.ui.model.flippedIf
import net.palaya.chessanalyzer.ui.model.nextKeyMomentPly
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.theme.GreenPrimary

/** Eval bar width and its gap to the board (the design draws 20 dp, which cannot hold "+0.9" or "M12" legibly). */
private val EVAL_BAR_WIDTH = 28.dp
private val EVAL_BAR_GAP = 6.dp
private val BOARD_SIDE_PADDING = 12.dp

/**
 * Room the rest of the screen needs under the board: the move chips (48 dp + band + padding), the
 * transport (56 dp + padding) and a card of at least 120 dp. The board shrinks below its
 * width-based size only when the window is too short for all of that (landscape, split-screen).
 */
private val BELOW_BOARD_MIN_HEIGHT = 48.dp + 12.dp + 64.dp + 120.dp + 16.dp

/**
 * The Board: look at one position and step through the game (docs/MOBILE_UX_DESIGN.md 6.4).
 *
 * From the top: eval bar and board in a row exactly as tall as the board (no dead band under it),
 * the move chips, a four-button transport (first, previous, next, last; no autoplay), and the
 * comment card filling the rest and scrolling when it is long. The flip control is the one icon in
 * the app bar.
 *
 * State (current ply, manual flip) is owned locally. The board opens from the user's side when it
 * is known ([userColor] = Black shows Black at the bottom); the flip is never persisted.
 */
@Composable
fun ReviewScreen(
    game: ImportedGame,
    modifier: Modifier = Modifier,
    /** Ply to open at, e.g. when arriving from a key moment or a tactic in the game report. */
    initialPly: Int? = null,
    /** The side the user played, when known: the board opens from that side. */
    userColor: PieceColor? = null,
    onShowMeClick: ((MoveRecord) -> Unit)? = null,
    /** Open the textbook example of a pattern the current move carries (ANALYSIS_SPEC section 10). */
    onLearnPattern: ((net.palaya.chessanalyzer.core.analysis.TacticType) -> Unit)? = null,
    /** Back to wherever the user came from (the summary, usually). */
    onBack: (() -> Unit)? = null,
    /**
     * The plies of the report's key moments (any order). With [initialPly] set (the board was opened
     * from a key moment) and a later one in this list, the comment card offers "Next key moment".
     */
    keyMomentPlies: List<Int> = emptyList(),
) {
    var currentPly by remember(game.id, initialPly) {
        mutableIntStateOf(initialPly?.coerceIn(0, (game.moves.size - 1).coerceAtLeast(0)) ?: 0)
    }
    // The manual flip is layered on the colour-derived default and not persisted: a new visit
    // (or a new answer to "Which side were you?") starts from the default again.
    var flipped by remember(game.id, userColor) { mutableStateOf(false) }
    val orientation = defaultBoardOrientation(userColor).flippedIf(flipped)
    val lastPly = game.moves.maxOfOrNull { it.ply } ?: 0

    val currentMove = game.moves.firstOrNull { it.ply == currentPly }
    val nextKeyPly: Int? = if (initialPly != null) nextKeyMomentPly(keyMomentPlies, currentPly) else null
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
    // The badge of the current ply (B1), on the destination square, for the highlight tier only.
    val badgeSpec = remember(currentMove?.ply, currentMove?.classification, lastMove) {
        boardBadgeFor(currentMove?.classification, lastMove?.second)
    }
    val badge: BoardBadge? = badgeSpec?.let { spec ->
        BoardBadge(
            square = spec.square,
            classification = spec.classification,
            contentDescription = stringResource(
                R.string.cd_board_badge,
                stringResource(spec.classification.displayNameRes),
                spec.square.algebraic(),
            ),
        )
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
                title = { AppBarTitle(stringResource(R.string.review_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { flipped = !flipped }) {
                        Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.cd_flip_board))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        // The pieces of the screen, defined once and arranged two ways: stacked (portrait) or board on
        // the left and everything else on the right (landscape, where a phone is only ~360 dp tall
        // and the stacked layout pushed the controls and the card off the bottom).
        val boardRow: @Composable (Dp) -> Unit = { boardSize ->
            Row(modifier = Modifier.height(boardSize)) {
                EvalBar(
                    evalCentipawns = evalCp,
                    mateIn = mateIn,
                    width = EVAL_BAR_WIDTH,
                    orientationFlipped = orientation == net.palaya.chessanalyzer.ui.board.BoardOrientation.BLACK_DOWN,
                )
                Spacer(modifier = Modifier.width(EVAL_BAR_GAP))
                ChessBoard(
                    board = boardState,
                    orientation = orientation,
                    lastMove = lastMove,
                    checkedKingSquare = checkedKingSquare,
                    arrows = arrows,
                    badge = badge,
                    modifier = Modifier.size(boardSize),
                )
            }
        }
        val chips: @Composable () -> Unit = {
            MoveList(
                moves = game.moves,
                selectedPly = currentPly,
                onMoveSelected = { currentPly = it },
                modifier = Modifier.fillMaxWidth(),
                sequences = game.sequences,
            )
        }
        val transport: @Composable () -> Unit = {
            MoveControls(
                onFirst = { currentPly = 0 },
                onPrev = { currentPly = (currentPly - 1).coerceAtLeast(0) },
                onNext = { currentPly = (currentPly + 1).coerceAtMost(lastPly) },
                onLast = { currentPly = lastPly },
                canGoBack = currentPly > 0,
                canGoForward = currentPly < lastPly,
            )
        }
        // The card sits at the top of its area and scrolls when it is long (a big font scale, a long
        // explanation) instead of being clipped.
        val card: @Composable (Modifier) -> Unit = { areaModifier ->
            Column(
                modifier = areaModifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BOARD_SIDE_PADDING, vertical = 8.dp),
            ) {
                currentMove?.let { move ->
                    CommentCard(
                        move = move,
                        modifier = Modifier.fillMaxWidth(),
                        // Only when there is a walkthrough to show: the button used to render on
                        // every move (book moves included) and did nothing when tapped.
                        onShowMeClick = onShowMeClick?.takeIf { move.core?.simulation != null }
                            ?.let { callback -> { callback(move) } },
                        onLearnPattern = onLearnPattern,
                        sequence = game.sequences.firstOrNull { move.ply in it },
                        // "Next key moment" only when the board was opened from one (a ply was
                        // asked for) and there is a later one; at the last it is gone.
                        onNextKeyMoment = nextKeyPly?.let { target -> { currentPly = target } },
                    )
                } ?: StartPositionCard()
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isLandscape()) {
                // Board (with its eval bar) as tall as the window allows and at most half its width; the
                // chips, transport and card share the other side, the card scrolling.
                val boardByHeight = maxHeight - 16.dp
                val boardByWidth = maxWidth / 2 - BOARD_SIDE_PADDING - EVAL_BAR_WIDTH - EVAL_BAR_GAP
                val boardSize: Dp = minOf(boardByHeight, boardByWidth).coerceAtLeast(120.dp)
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = BOARD_SIDE_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        boardRow(boardSize)
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        chips()
                        transport()
                        card(Modifier.weight(1f))
                    }
                }
            } else {
                // The board is as wide as the screen allows (minus the eval bar), and the row is exactly
                // as tall as the board, so nothing is left over between the board and the controls.
                val boardByWidth = maxWidth - BOARD_SIDE_PADDING * 2 - EVAL_BAR_WIDTH - EVAL_BAR_GAP
                val boardByHeight = maxHeight - BELOW_BOARD_MIN_HEIGHT
                val boardSize: Dp = minOf(boardByWidth, maxOf(boardByHeight, 160.dp))
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        boardRow(boardSize)
                    }
                    chips()
                    transport()
                    card(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StartPositionCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.review_start_position_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun MoveControls(
    onFirst: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
) {
    // The transport controls belong to the move list, which is pinned LTR (see MoveList): "first"
    // and "previous" step towards the start of a left-to-right sequence, so they stay on the
    // left with their icons pointing left in every locale, rather than mirroring as a Row while
    // the icons do not.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onFirst, enabled = canGoBack) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.review_first_move))
            }
            IconButton(onClick = onPrev, enabled = canGoBack, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(R.string.review_previous_move),
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onNext, enabled = canGoForward, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.review_next_move),
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onLast, enabled = canGoForward) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.review_last_move))
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
            onBack = {},
        )
    }
}
