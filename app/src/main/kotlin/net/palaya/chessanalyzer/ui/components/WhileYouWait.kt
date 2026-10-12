@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.palaya.chessanalyzer.ui.components

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.chess.Color as ChessColor
import net.palaya.chessanalyzer.data.mapper.fenToBoardState
import net.palaya.chessanalyzer.data.mapper.toBoardState
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.a11y.isLandscape
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.model.FamousEra
import net.palaya.chessanalyzer.ui.model.FamousGame
import net.palaya.chessanalyzer.ui.model.LiveInsight
import net.palaya.chessanalyzer.ui.model.InsightKind
import net.palaya.chessanalyzer.ui.model.WaitingCard
import net.palaya.chessanalyzer.ui.model.WaitingContent
import net.palaya.chessanalyzer.ui.model.WaitingFact
import net.palaya.chessanalyzer.ui.model.WaitingGame
import net.palaya.chessanalyzer.ui.model.WaitingPanelController
import net.palaya.chessanalyzer.ui.model.WaitingPanelState
import net.palaya.chessanalyzer.ui.model.WaitingPlayer
import net.palaya.chessanalyzer.ui.model.WaitingPuzzle
import net.palaya.chessanalyzer.ui.model.WaitingPuzzleFeedback
import net.palaya.chessanalyzer.ui.model.WaitingPuzzleGoal
import net.palaya.chessanalyzer.ui.model.WaitingPuzzleSession
import net.palaya.chessanalyzer.ui.model.WaitingPuzzleStatus
import net.palaya.chessanalyzer.ui.model.insightsPage
import net.palaya.chessanalyzer.ui.model.insightsSignature
import net.palaya.chessanalyzer.ui.model.newWaitingPuzzleSession
import net.palaya.chessanalyzer.ui.model.solutionLineText
import net.palaya.chessanalyzer.ui.model.waitingPuzzleReply
import net.palaya.chessanalyzer.ui.model.waitingPuzzleReveal
import net.palaya.chessanalyzer.ui.model.waitingPuzzleScores
import net.palaya.chessanalyzer.ui.model.waitingPuzzleTap
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import kotlinx.coroutines.delay
import kotlin.random.Random

/*
 * The "While you wait" panel (docs/WAITING_CONTENT.md): live insights about the game being analysed, then fun facts,
 * player profiles, famous games and puzzles, one card at a time.
 *
 * Self-contained and data-in: no ViewModel, no network. The caller owns the [insights] (built from the analysis with
 * `liveInsights(...)`) and the [content] (WaitingContentStore.load). The panel is a plain Column-height card; put it in a
 * scrollable column where the progress screen has room for it.
 *
 * Reference (CHESSCOM_REFERENCE_ALIGNMENT-style, patterns only): chess.com's game-review loading screen shows one tip card
 * with a muted label above it while a progress bar runs; its puzzles show the position with the side to move named,
 * a "Hint"/"Solution" pair and a clear Next once solved, and a wrong move is a short message with the piece going back.
 * We took: one card at a time with a small kind label, the side to move stated above the board, the solution on demand, and
 * Next after a result. Own words and art throughout.
 */

/** Test tags, for the instrumented test. */
object WaitingTags {
    const val PANEL = "waiting_panel"
    const val CARD = "waiting_card"
    const val PREVIOUS = "waiting_previous"
    const val NEXT = "waiting_next"
    const val PAUSE = "waiting_pause"
    const val SHOW_SOLUTION = "waiting_show_solution"
}

/** True while a touch-exploration service (TalkBack) is on; the panel then does not rotate by itself. */
@Composable
fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * @param insights the live insights, newest first computed (see `liveInsights`); an empty list is fine.
 * @param content facts, players, games and puzzles (WaitingContentStore.load); `WaitingContent.EMPTY` shows insights only.
 * @param seed makes the order of the cards reproducible; the default is random per session and survives rotation.
 * @param autoRotate false stops the self-rotation (the default turns it off while TalkBack is on; the buttons still work).
 */
@Composable
fun WhileYouWaitPanel(
    insights: List<LiveInsight>,
    content: WaitingContent,
    modifier: Modifier = Modifier,
    seed: Long = rememberSaveable { Random.nextLong() },
    autoRotate: Boolean = !rememberTouchExplorationEnabled(),
) {
    val controller = remember(content, seed) { WaitingPanelController(content, seed) }
    val signature = insightsSignature(insights)
    val latestSignature by rememberUpdatedState(signature)
    val latestAutoRotate by rememberUpdatedState(autoRotate)
    var state by remember(controller) { mutableStateOf<WaitingPanelState>(controller.start(SystemClock.elapsedRealtime(), signature)) }
    val sessions = remember(controller) { mutableStateMapOf<String, WaitingPuzzleSession>() }

    LaunchedEffect(controller) {
        while (true) {
            delay(1_000L)
            state = controller.tick(state, SystemClock.elapsedRealtime(), latestSignature, latestAutoRotate)
        }
    }

    val density = LocalDensity.current
    val swipePx = with(density) { 64.dp.toPx() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(WaitingTags.PANEL)
            // Any finger on the panel holds the rotation (Initial pass: nothing is consumed, children still get the event).
            .pointerInput(controller) {
                awaitPointerEventScopeLoop { event ->
                    if (event.changes.any { it.pressed }) state = controller.touch(state, SystemClock.elapsedRealtime())
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.wait_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).asHeading(),
                )
                IconButton(
                    onClick = { state = controller.previous(controller.touch(state, SystemClock.elapsedRealtime()), SystemClock.elapsedRealtime()) },
                    enabled = state.canGoBack,
                    modifier = Modifier.size(48.dp).testTag(WaitingTags.PREVIOUS),
                ) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.wait_previous))
                }
                IconButton(
                    onClick = { state = controller.setPaused(controller.touch(state, SystemClock.elapsedRealtime()), !state.paused, SystemClock.elapsedRealtime()) },
                    modifier = Modifier.size(48.dp).testTag(WaitingTags.PAUSE),
                ) {
                    Icon(
                        imageVector = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(if (state.paused) R.string.wait_resume else R.string.wait_pause),
                    )
                }
                IconButton(
                    onClick = { state = controller.next(controller.touch(state, SystemClock.elapsedRealtime()), SystemClock.elapsedRealtime(), latestSignature) },
                    modifier = Modifier.size(48.dp).testTag(WaitingTags.NEXT),
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.wait_next))
                }
            }

            // Swipe left for the next card, right for the previous one (the header's arrows do the same for everyone; they sit
            // in the header so they stay put whatever the card's height, device pass 2026-10-12).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(controller) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                val now = SystemClock.elapsedRealtime()
                                if (total <= -swipePx) state = controller.next(state, now, latestSignature)
                                else if (total >= swipePx) state = controller.previous(state, now)
                                total = 0f
                            },
                            onDragCancel = { total = 0f },
                            onHorizontalDrag = { _, amount -> total += amount },
                        )
                    }
                    .testTag(WaitingTags.CARD),
            ) {
                Crossfade(targetState = state.current, label = "waiting card") { card ->
                    CardBody(
                        card = card,
                        insights = insights,
                        sessions = sessions,
                        onEngaged = { engaged ->
                            if (state.current.key == card.key && state.engaged != engaged) {
                                state = controller.setEngaged(state, engaged, SystemClock.elapsedRealtime())
                            }
                        },
                        onNext = { state = controller.next(state, SystemClock.elapsedRealtime(), latestSignature) },
                    )
                }
            }
        }
    }
}

/** Watches every pointer event on the Initial pass without consuming any. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitPointerEventScopeLoop(
    onEvent: (androidx.compose.ui.input.pointer.PointerEvent) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            onEvent(awaitPointerEvent(PointerEventPass.Initial))
        }
    }
}

@Composable
private fun CardBody(
    card: WaitingCard,
    insights: List<LiveInsight>,
    sessions: SnapshotStateMap<String, WaitingPuzzleSession>,
    onEngaged: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    // Everything but the insights (which change every second) is a polite live region, so a card the user moves to is read out.
    val live = card !is WaitingCard.Insights
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (card) {
            is WaitingCard.Insights -> InsightsCardBody(insights, card.page)
            is WaitingCard.Fact -> FactCardBody(card.fact)
            is WaitingCard.Player -> PlayerCardBody(card.player)
            is WaitingCard.Game -> GameCardBody(card.game)
            is WaitingCard.Puzzle -> PuzzleCardBody(
                puzzle = card.puzzle,
                session = sessions.getOrPut(card.puzzle.id) { newWaitingPuzzleSession(card.puzzle) },
                onSession = { sessions[card.puzzle.id] = it },
                onEngaged = onEngaged,
                onNext = onNext,
            )
        }
    }
}

@Composable
private fun KindLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().asHeading(),
    )
}

@Composable
private fun bodyStyle() = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)

// ---------------------------------------------------------------------------------------------
// Insights
// ---------------------------------------------------------------------------------------------

/** The sentence of one insight, with White/Black written in the app's own words. */
@Composable
fun insightText(insight: LiveInsight): String {
    val args = insight.args.map { arg ->
        when (arg) {
            ChessColor.WHITE -> stringResource(R.string.side_white)
            ChessColor.BLACK -> stringResource(R.string.side_black)
            else -> arg
        }
    }
    return stringResource(insightStringRes(insight.kind), *args.toTypedArray())
}

@Composable
private fun InsightsCardBody(insights: List<LiveInsight>, page: Int) {
    KindLabel(stringResource(R.string.wait_kind_insights))
    val shown = insightsPage(insights, page)
    if (shown.isEmpty()) {
        Text(stringResource(R.string.wait_insights_empty), style = bodyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        shown.forEach { Text(insightText(it), style = bodyStyle(), modifier = Modifier.fillMaxWidth()) }
    }
}

// ---------------------------------------------------------------------------------------------
// Text cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun FactCardBody(fact: WaitingFact) {
    KindLabel(stringResource(R.string.wait_kind_fact))
    Text(fact.text, style = bodyStyle(), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PlayerCardBody(player: WaitingPlayer) {
    KindLabel(stringResource(R.string.wait_kind_player))
    Text(
        player.name,
        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.wait_player_facts, player.years, player.country),
        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    player.role?.let {
        Text(it, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth())
    }
    Text(player.text, style = bodyStyle(), modifier = Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------------------------
// Famous game
// ---------------------------------------------------------------------------------------------

@Composable
private fun GameCardBody(game: WaitingGame) {
    KindLabel(stringResource(R.string.wait_kind_game))
    val g = game.game
    val board = remember(game.finalFen) { fenToBoardState(game.finalFen) }
    val result = stringResource(
        when (g.result) {
            "1-0" -> R.string.wait_game_result_white
            "0-1" -> R.string.wait_game_result_black
            else -> R.string.wait_game_result_draw
        },
    )
    val text: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                g.title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.wait_game_versus, g.white, g.black),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            val whenText = game.event?.let { stringResource(R.string.wait_game_when_event, g.year, it) } ?: g.year.toString()
            Text(
                "$whenText · $result",
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(g.description, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth())
        }
    }
    if (isLandscape()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            MiniBoard(board = board, side = 168.dp)
            Box(modifier = Modifier.weight(1f)) { text() }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val side = minOf(maxWidth, 200.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniBoard(board = board, side = side)
                Text(
                    stringResource(R.string.wait_game_final_position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                text()
            }
        }
    }
}

@Composable
private fun MiniBoard(board: net.palaya.chessanalyzer.ui.model.BoardState, side: Dp) {
    ChessBoard(board = board, modifier = Modifier.size(side), showCoordinates = false)
}

// ---------------------------------------------------------------------------------------------
// Puzzle
// ---------------------------------------------------------------------------------------------

private const val REPLY_DELAY_MS = 700L

@Composable
private fun PuzzleCardBody(
    puzzle: WaitingPuzzle,
    session: WaitingPuzzleSession,
    onSession: (WaitingPuzzleSession) -> Unit,
    onEngaged: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    // The opponent's reply is played a moment after the user's move; if the card is left meanwhile it is played on return.
    LaunchedEffect(session.status, session.step) {
        if (session.status == WaitingPuzzleStatus.REPLYING) {
            delay(REPLY_DELAY_MS)
            onSession(waitingPuzzleReply(session))
        }
    }
    // A started, unfinished puzzle is never rotated away.
    LaunchedEffect(session.inProgress) { onEngaged(session.inProgress) }

    KindLabel(stringResource(R.string.wait_kind_puzzle))
    val side = if (puzzle.sideToMove == ChessColor.WHITE) stringResource(R.string.side_white) else stringResource(R.string.side_black)
    val board = remember(session.position) { session.position.toBoardState() }
    val checked = remember(session.position) {
        if (session.position.isInCheck()) session.position.kingSquare(session.position.sideToMove).toUiSquare() else null
    }

    val info: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.wait_puzzle_from_game, puzzle.gameTitle, puzzle.moveNumber),
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.wait_puzzle_to_play, side),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when (val goal = puzzle.goal) {
                    is WaitingPuzzleGoal.MateIn -> stringResource(R.string.wait_puzzle_goal_mate, goal.moves)
                    WaitingPuzzleGoal.BestMove -> stringResource(R.string.wait_puzzle_goal_best)
                },
                style = bodyStyle(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    val feedback: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PuzzleFeedbackText(puzzle, session)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!session.resolved) {
                TextButton(
                    onClick = { onSession(waitingPuzzleReveal(session)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(WaitingTags.SHOW_SOLUTION),
                ) { Text(stringResource(R.string.wait_puzzle_show_solution)) }
            } else {
                Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_next)) }
            }
        }
    }
    val boardContent: @Composable (Dp) -> Unit = { boardSide ->
        ChessBoard(
            board = board,
            modifier = Modifier.size(boardSide),
            orientation = if (puzzle.sideToMove == ChessColor.WHITE) BoardOrientation.WHITE_DOWN else BoardOrientation.BLACK_DOWN,
            lastMove = session.lastMove,
            checkedKingSquare = checked,
            selectedSquare = if (session.acceptsTaps) session.selected else null,
            legalMoveTargets = session.targets,
            onSquareClick = if (session.acceptsTaps) { square -> onSession(waitingPuzzleTap(session, square)) } else null,
        )
    }

    if (isLandscape()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            boardContent(240.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info()
                feedback()
            }
        }
    } else {
        info()
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            boardContent(minOf(maxWidth, 360.dp))
        }
        feedback()
    }
}

@Composable
private fun PuzzleFeedbackText(puzzle: WaitingPuzzle, session: WaitingPuzzleSession) {
    val body = bodyStyle()
    when (session.status) {
        WaitingPuzzleStatus.ASKING, WaitingPuzzleStatus.REPLYING -> when (session.feedback) {
            WaitingPuzzleFeedback.WRONG -> Text(stringResource(R.string.wait_puzzle_wrong), style = body, fontWeight = FontWeight.SemiBold)
            WaitingPuzzleFeedback.CONTINUE -> {
                val reply = puzzle.san[if (session.status == WaitingPuzzleStatus.REPLYING) session.step else session.step - 1]
                Text(stringResource(R.string.wait_puzzle_continue, reply), style = body, fontWeight = FontWeight.SemiBold)
            }
            WaitingPuzzleFeedback.NONE -> Unit
        }
        WaitingPuzzleStatus.SOLVED -> {
            val goal = puzzle.goal
            val headline = when {
                goal is WaitingPuzzleGoal.MateIn -> stringResource(R.string.wait_puzzle_solved_mate, goal.moves)
                puzzle.solverMoves > 1 -> stringResource(R.string.wait_puzzle_solved_line, solutionLineText(puzzle))
                else -> stringResource(R.string.wait_puzzle_solved_best, puzzle.san[0])
            }
            Text(headline, style = body, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            PuzzleAfterwards(puzzle)
        }
        WaitingPuzzleStatus.REVEALED -> {
            Text(stringResource(R.string.wait_puzzle_revealed, solutionLineText(puzzle)), style = body, fontWeight = FontWeight.SemiBold)
            PuzzleAfterwards(puzzle)
        }
    }
}

@Composable
private fun PuzzleAfterwards(puzzle: WaitingPuzzle) {
    val body = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    Text(stringResource(R.string.wait_puzzle_first_move_played), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    waitingPuzzleScores(puzzle)?.let { scores ->
        val sideName = stringResource(if (puzzle.sideToMove == ChessColor.WHITE) R.string.side_white else R.string.side_black)
        Text(
            text = scores.secondBest?.let { stringResource(R.string.wait_puzzle_scores, sideName, puzzle.depth, scores.best, it) }
                ?: stringResource(R.string.wait_puzzle_score_best_only, sideName, puzzle.depth, scores.best),
            style = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

/** A tiny content set for previews and tests of the composable (the real content is WaitingContentStore's). */
object WaitingPreviewContent {
    val insights = listOf(
        LiveInsight(InsightKind.OPENING, listOf("Sicilian Defense: Najdorf Variation", "B90")),
        LiveInsight(InsightKind.SWING, listOf("17…Qxd4", "+0.4", "-2.8")),
        LiveInsight(InsightKind.MATERIAL_AHEAD, listOf("17…Qxd4", ChessColor.BLACK, 2)),
        LiveInsight(InsightKind.CHECKS, listOf(3, 1)),
        LiveInsight(InsightKind.PROGRESS, listOf(24, 62)),
    )

    private val game = FamousGame(
        id = "opera-game-1858",
        era = FamousEra.ROMANTIC,
        title = "The Opera Game",
        white = "Paul Morphy",
        black = "Duke Karl of Brunswick and Count Isouard",
        year = 1858,
        result = "1-0",
        description = "Played in 1858 in a box at the Paris opera, against two noblemen consulting together; known as the Opera Game.",
    )

    val content = WaitingContent(
        facts = listOf(
            WaitingFact("first-moves", "maths", "White has exactly 20 legal first moves: sixteen pawn moves and four knight moves."),
            WaitingFact("knight-colour", "rules", "A knight changes the colour of its square with every move."),
        ),
        players = listOf(
            WaitingPlayer(
                "lasker", "Emanuel Lasker", 1868, 1941, "Germany", "World champion 1894–1921",
                "He held the title for 27 years, the longest reign of any champion. He also earned a doctorate in mathematics.",
            ),
        ),
        games = listOf(
            WaitingGame(game, "Paris", "1n2kb1r/p4ppp/4q3/4p1B1/4P3/8/PPP2PPP/2KR4 b k - 1 17", 33),
        ),
        puzzles = listOf(
            WaitingPuzzle(
                id = "preview", gameId = game.id, ply = 7,
                fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                prevUci = "g8f6", goal = WaitingPuzzleGoal.MateIn(1),
                solution = listOf("h5f7"), san = listOf("Qxf7#"), bestCp = null, secondCp = null, depth = 22,
                gameTitle = game.title, gameYear = game.year,
            ),
        ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, widthDp = 360)
@Composable
private fun WhileYouWaitInsightsPreview() {
    ChessAnalyzerTheme {
        WhileYouWaitPanel(
            insights = WaitingPreviewContent.insights,
            content = WaitingPreviewContent.content,
            modifier = Modifier.padding(16.dp),
            seed = 1L,
            autoRotate = false,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, widthDp = 360, fontScale = 2f)
@Composable
private fun WhileYouWaitLargeFontPreview() {
    ChessAnalyzerTheme {
        WhileYouWaitPanel(
            insights = WaitingPreviewContent.insights,
            content = WaitingPreviewContent.content,
            modifier = Modifier.padding(16.dp),
            seed = 1L,
            autoRotate = false,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, widthDp = 360)
@Composable
private fun PuzzleCardPreview() {
    ChessAnalyzerTheme {
        val puzzle = WaitingPreviewContent.content.puzzles.first()
        Card(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PuzzleCardBody(puzzle, newWaitingPuzzleSession(puzzle), onSession = {}, onEngaged = {}, onNext = {})
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, widthDp = 360)
@Composable
private fun GameCardPreview() {
    ChessAnalyzerTheme {
        Card(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameCardBody(WaitingPreviewContent.content.games.first())
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, widthDp = 640, heightDp = 360)
@Composable
private fun WhileYouWaitLandscapePreview() {
    ChessAnalyzerTheme {
        WhileYouWaitPanel(
            insights = WaitingPreviewContent.insights,
            content = WaitingPreviewContent.content,
            modifier = Modifier.padding(16.dp).widthIn(max = 600.dp),
            seed = 1L,
            autoRotate = false,
        )
    }
}
