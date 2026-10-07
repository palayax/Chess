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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import net.palaya.chessanalyzer.core.analysis.BestLine
import net.palaya.chessanalyzer.core.analysis.BestLineCaption
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.data.mapper.toCoreColor
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.components.LineBoard
import net.palaya.chessanalyzer.ui.components.LinePlaybackEffect
import net.palaya.chessanalyzer.ui.components.LineStepper
import net.palaya.chessanalyzer.ui.components.rememberLinePlayback
import net.palaya.chessanalyzer.ui.model.bestLineOffered
import net.palaya.chessanalyzer.ui.model.bestLinesFor
import net.palaya.chessanalyzer.ui.model.lineSideToMove
import net.palaya.chessanalyzer.ui.model.lineStepMove
import net.palaya.chessanalyzer.ui.theme.MoveNotationStyle
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
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
     * A key moment also offers "Show the best line" (V2), whatever its class.
     */
    keyMomentPlies: List<Int> = emptyList(),
    /** Open straight into the best-line mode of [initialPly] (the Summary's "Show the best line"). */
    openBestLine: Boolean = false,
    /** One move every this long while a line plays: the video's line rate (Settings, Video, Pace). */
    playStepMs: Long = VideoPace.DEFAULT.lineMoveMinMs,
) {
    var currentPly by remember(game.id, initialPly) {
        // Plies run 1..N: the last move (a key moment on the final move, its best line) can be opened too.
        mutableIntStateOf(initialPly?.coerceIn(0, (game.moves.maxOfOrNull { it.ply } ?: 0)) ?: 0)
    }
    // The manual flip is layered on the colour-derived default and not persisted: a new visit
    // (or a new answer to "Which side were you?") starts from the default again.
    var flipped by remember(game.id, userColor) { mutableStateOf(false) }
    val orientation = defaultBoardOrientation(userColor).flippedIf(flipped)
    val lastPly = game.moves.maxOfOrNull { it.ply } ?: 0

    val currentMove = game.moves.firstOrNull { it.ply == currentPly }

    // V2, "Show the best line" (ANALYSIS_SPEC 6.2): the engine's line from the position BEFORE the move,
    // played on this board with the shared line player. The mode belongs to one ply: stepping the game
    // elsewhere (or "Back to the game") leaves it.
    val lines = remember(currentMove?.core) { bestLinesFor(currentMove) }
    val lineOffered = currentMove != null && bestLineOffered(currentMove, keyMomentPlies, lines)
    var lineMode by remember(game.id, initialPly) {
        mutableStateOf(if (openBestLine && initialPly != null) BestLineMode(ply = currentPly, index = 0) else null)
    }
    val activeLine: BestLine? = lineMode?.takeIf { it.ply == currentPly }?.let { lines.getOrNull(it.index) }
    val playback = rememberLinePlayback(activeLine, activeLine?.startFen.orEmpty(), activeLine?.ucis.orEmpty())
    LinePlaybackEffect(playback, playStepMs)
    BackHandler(enabled = activeLine != null) { lineMode = null }
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
                    // In line mode the bar shows the engine's score for the line (White-relative, §9.4).
                    evalCentipawns = if (activeLine != null) activeLine.whiteCp ?: 0 else evalCp,
                    mateIn = if (activeLine != null) activeLine.whiteMateIn else mateIn,
                    width = EVAL_BAR_WIDTH,
                    orientationFlipped = orientation == net.palaya.chessanalyzer.ui.board.BoardOrientation.BLACK_DOWN,
                )
                Spacer(modifier = Modifier.width(EVAL_BAR_GAP))
                if (activeLine != null) {
                    LineBoard(state = playback, orientation = orientation, modifier = Modifier.size(boardSize))
                } else {
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
                        onShowBestLine = if (lineOffered) {
                            { lineMode = BestLineMode(ply = move.ply, index = 0) }
                        } else null,
                    )
                } ?: StartPositionCard()
            }
        }
        // Line mode: the line chooser (when the engine has near-equal alternatives), the line stepper and
        // the line's card, in place of the game's chips, transport and comment card.
        val lineControls: @Composable () -> Unit = {
            val line = activeLine
            val move = currentMove
            if (line != null && move != null) {
                if (lines.size > 1) {
                    LineChoices(
                        lines = lines,
                        selected = lineMode?.index ?: 0,
                        onSelect = { lineMode = BestLineMode(ply = move.ply, index = it) },
                    )
                }
                LineModeStepper(line = line, state = playback)
            }
        }
        val lineCard: @Composable (Modifier) -> Unit = { areaModifier ->
            val line = activeLine
            val move = currentMove
            Column(
                modifier = areaModifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BOARD_SIDE_PADDING, vertical = 8.dp),
            ) {
                if (line != null && move != null) {
                    BestLineCard(
                        line = line,
                        index = lineMode?.index ?: 0,
                        playedMove = move,
                        step = playback.step,
                        viewer = userColor,
                        onBackToGame = { lineMode = null },
                    )
                }
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
                        if (activeLine != null) {
                            lineControls()
                            lineCard(Modifier.weight(1f))
                        } else {
                            chips()
                            transport()
                            card(Modifier.weight(1f))
                        }
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
                    if (activeLine != null) {
                        lineControls()
                        lineCard(Modifier.weight(1f))
                    } else {
                        chips()
                        transport()
                        card(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Which line of which ply the Board is playing (V2): [index] 0 is the best line, 1 and 2 the alternatives. */
private data class BestLineMode(val ply: Int, val index: Int)

/** "Best", "Line 2", ... as the chooser and the card name a line. */
@Composable
private fun lineName(index: Int, multiPv: Int): String =
    if (index == 0) stringResource(R.string.line_choice_best) else stringResource(R.string.line_choice_number, multiPv)

/**
 * The lines the engine rates within the margin of the best (ANALYSIS_SPEC 6.2), one chip each: its first
 * move and its score. Like chess.com's analysis board, which lists the engine's top lines with their
 * scores and lets you step through any of them. Notation, so the row is left to right in every language.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LineChoices(lines: List<BestLine>, selected: Int, onSelect: (Int) -> Unit) {
    val selectedWord = stringResource(R.string.cd_line_selected)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BOARD_SIDE_PADDING, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lines.forEachIndexed { i, line ->
                val name = lineName(i, line.multiPv)
                val first = line.steps.first()
                val description = stringResource(R.string.cd_line_choice, name, first.san, line.scoreText) +
                    if (i == selected) ", $selectedWord" else ""
                // One TalkBack stop per line: a sentence, a tab role and its selected state, and the same
                // action a tap has. The chip's own semantics (a checkbox with only the label) are replaced.
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Tab
                        this.selected = i == selected
                        onClick { onSelect(i); true }
                    },
                ) {
                    FilterChip(
                        selected = i == selected,
                        onClick = { onSelect(i) },
                        label = { Text("$name · ${first.san} ${line.scoreText}") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/**
 * The line mode's stepper: the shared [LineStepper] with the move just played ("18… Nf5") and who is to
 * move in the position on the board, Back / Next / Play-pause.
 */
@Composable
private fun LineModeStepper(line: BestLine, state: net.palaya.chessanalyzer.ui.components.LinePlaybackState) {
    val step = state.step
    val move = lineStepMove(line.startFen, step, line.sans)
    val caption = if (move == null) {
        stringResource(R.string.line_start)
    } else {
        stringResource(if (move.isWhite) R.string.simulation_move_white else R.string.simulation_move_black, move.number, move.san)
    }
    val toMove = lineSideToMove(state.positions, step)?.let { side ->
        stringResource(R.string.panel_to_move, stringResource(if (side == CoreColor.WHITE) R.string.side_white else R.string.side_black))
    }
    LineStepper(
        state = state,
        caption = caption,
        captionIsNotation = move != null,
        counter = stringResource(R.string.simulation_step_counter, step, state.totalPlies),
        showCounter = true,
        secondLine = toMove,
        previousDescription = stringResource(R.string.line_previous),
        nextDescription = stringResource(R.string.line_next),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * The line's card: which line it is and what it replaces, the line in move-number notation with the
 * current move marked, the verified caption (ANALYSIS_SPEC 6.2: the engine's score as the engine's, and
 * only what the board proves), the depth it was searched to, and "Back to the game".
 */
@Composable
private fun BestLineCard(
    line: BestLine,
    index: Int,
    playedMove: MoveRecord,
    step: Int,
    viewer: PieceColor?,
    onBackToGame: () -> Unit,
) {
    val played = stringResource(
        if (playedMove.moverColor == PieceColor.WHITE) R.string.simulation_move_white else R.string.simulation_move_black,
        playedMove.moveNumber,
        playedMove.san,
    )
    // A key moment that was itself the engine's choice (a brilliancy) has no "instead of": its line goes on from it.
    val title = when {
        line.ucis.first() == playedMove.uci -> stringResource(R.string.line_title_played, played)
        index == 0 -> stringResource(R.string.line_title_best, played)
        else -> stringResource(R.string.line_title_alternative, line.multiPv, played)
    }
    val caption = remember(line, viewer) { BestLineCaption.text(line, viewer?.toCoreColor()) }
    val notation = remember(line, step) { lineNotation(line, step) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.asHeading(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = notation,
                style = MoveNotationStyle.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.line_depth, line.depth),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onBackToGame, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.line_back_to_game))
            }
        }
    }
}

/** The line as a scoresheet writes it, the move on the board in bold and green. */
private fun lineNotation(line: BestLine, step: Int): AnnotatedString = buildAnnotatedString {
    append("\u200E")
    line.steps.forEachIndexed { i, s ->
        if (i > 0) append(' ')
        // A no-break space keeps a move number on the line of its move at any font size.
        val token = when {
            s.color == CoreColor.WHITE -> "${s.moveNumber}.\u00A0${s.san}"
            i == 0 -> "${s.moveNumber}…\u00A0${s.san}"
            else -> s.san
        }
        if (i == step - 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = GreenPrimary)) { append(token) }
        } else {
            append(token)
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
