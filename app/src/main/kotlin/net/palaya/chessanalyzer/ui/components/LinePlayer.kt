package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.data.mapper.toBoardState
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.data.mapper.uciToUiSquarePair
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.board.BoardArrow
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.linePlayStart
import net.palaya.chessanalyzer.ui.model.linePlayTick
import net.palaya.chessanalyzer.ui.model.linePositions
import net.palaya.chessanalyzer.ui.model.lineNextStep
import net.palaya.chessanalyzer.ui.model.linePreviousStep
import net.palaya.chessanalyzer.ui.theme.GreenPrimary

/**
 * The one line player (V2): a line of moves stepped on a board, with Back / Next / Play-pause. Used by
 * the Walkthrough (a missed tactic, ANALYSIS_SPEC §6) and by the Board's "Show the best line" mode
 * (§6.2), so the two step, number and draw a line the same way.
 *
 * [step] 0 is [startFen]; step k is the position after the k-th move of [uci]. Only the legal prefix of
 * [uci] is playable ([linePositions]), so [totalPlies] can be shorter than the list for a stale line.
 */
@Stable
class LinePlaybackState(val startFen: String, val uci: List<String>) {
    val positions: List<Position> = linePositions(startFen, uci)
    val totalPlies: Int get() = positions.size - 1

    var step by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set

    val atStart: Boolean get() = step <= 0
    val atEnd: Boolean get() = step >= totalPlies

    fun next() {
        playing = false
        step = lineNextStep(step, totalPlies)
    }

    fun previous() {
        playing = false
        step = linePreviousStep(step)
    }

    fun goTo(target: Int) {
        playing = false
        step = target.coerceIn(0, totalPlies)
    }

    fun togglePlay() {
        if (playing) {
            playing = false
        } else {
            step = linePlayStart(step, totalPlies)
            playing = totalPlies > 0
        }
    }

    /** One tick of Play; stops on the last move. */
    internal fun tick() {
        val next = linePlayTick(step, totalPlies)
        if (next == null) playing = false else step = next
        if (step >= totalPlies) playing = false
    }
}

/** A [LinePlaybackState] that starts over whenever [key] or the line changes. */
@Composable
fun rememberLinePlayback(key: Any?, startFen: String, uci: List<String>): LinePlaybackState =
    remember(key, startFen, uci) { LinePlaybackState(startFen, uci) }

/** Drives Play: one move every [stepMs] while [state] is playing. */
@Composable
fun LinePlaybackEffect(state: LinePlaybackState, stepMs: Long) {
    LaunchedEffect(state, state.playing, state.step) {
        if (!state.playing) return@LaunchedEffect
        delay(stepMs)
        state.tick()
    }
}

/**
 * The board of a line: the position of the current step, the move just played highlighted, a check
 * marked, and the next move of the line as a green arrow (what is about to be played).
 */
@Composable
fun LineBoard(
    state: LinePlaybackState,
    modifier: Modifier = Modifier,
    orientation: BoardOrientation = BoardOrientation.WHITE_DOWN,
) {
    val step = state.step
    val board: BoardState = remember(state, step) {
        state.positions.getOrNull(step)?.toBoardState() ?: BoardState.startingPosition()
    }
    val lastMove = remember(state, step) { if (step > 0) state.uci.getOrNull(step - 1)?.let { uciToUiSquarePair(it) } else null }
    val upcoming = remember(state, step) {
        if (step < state.totalPlies) {
            state.uci.getOrNull(step)?.let { uciToUiSquarePair(it) }?.let { (from, to) -> BoardArrow(from, to, GreenPrimary) }
        } else null
    }
    val checked = remember(state, step) {
        state.positions.getOrNull(step)?.let { pos -> if (pos.isInCheck()) pos.kingSquare(pos.sideToMove).toUiSquare() else null }
    }
    ChessBoard(
        board = board,
        orientation = orientation,
        lastMove = lastMove,
        checkedKingSquare = checked,
        arrows = listOfNotNull(upcoming),
        modifier = modifier,
    )
}

/**
 * The stepper of a line: previous, the caption of the current step with a "2 / 5" counter under it
 * (both in a polite live region, so TalkBack speaks the new move after Next, Previous or a Play tick),
 * next, and Play/Pause when [showPlay]. Notation is left to right in every language, so the row is
 * pinned LTR like the Board's transport; the caption itself is a [caption] the caller resolved.
 *
 * @param captionIsNotation true when [caption] is a move ("18… Nf5"), drawn left to right; a sentence
 *   ("Before the mistake") follows its own direction.
 * @param showCounter false on the start step, where the counter keeps its space (so nothing jumps) but
 *   is neither drawn nor read.
 */
@Composable
fun LineStepper(
    state: LinePlaybackState,
    caption: String,
    captionIsNotation: Boolean,
    counter: String,
    showCounter: Boolean,
    modifier: Modifier = Modifier,
    secondLine: String? = null,
    showPlay: Boolean = true,
    previousDescription: String = stringResource(R.string.simulation_previous_step),
    nextDescription: String = stringResource(R.string.simulation_next_step),
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.previous() }, enabled = !state.atStart, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = previousDescription, modifier = Modifier.size(32.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = if (captionIsNotation) TextDirection.Ltr else TextDirection.Content,
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading(),
                )
                secondLine?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = counter,
                    style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = if (showCounter) Modifier else Modifier.alpha(0f).clearAndSetSemantics { },
                )
            }
            IconButton(onClick = { state.next() }, enabled = !state.atEnd, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = nextDescription, modifier = Modifier.size(32.dp))
            }
            if (showPlay) {
                IconButton(onClick = { state.togglePlay() }, enabled = state.totalPlies > 0, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.line_pause else R.string.line_play),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}
