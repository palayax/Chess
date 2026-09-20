@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.data.mapper.toBoardState
import net.palaya.chessanalyzer.data.mapper.uciToUiSquarePair
import net.palaya.chessanalyzer.ui.board.BoardArrow
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.theme.GreenPrimary

/**
 * Guided "Show me" walkthrough of a missed tactic: steps forward/back through
 * [TacticSimulation.pvUci], replaying each move on its own board state (never touching the
 * main game line — the board here is rebuilt from [TacticSimulation.startFen] each time) and
 * drawing the *next* move as an arrow via [ChessBoard]'s existing `arrows` param before it's
 * played, then showing that ply's explanation once it lands. The final step shows
 * [TacticSimulation.payoffDescription] as the payoff.
 *
 * The same screen plays a **textbook reference** of a pattern (ANALYSIS_SPEC §10): the caller
 * passes a [title] and an [introText] (the teaching point) and the rest is identical — one board,
 * one stepper, one explanation card — so learning a pattern feels like reviewing a miss, not like
 * a separate lesson. From a missed-tactic walkthrough, [onSeeReference] offers that reference on
 * the final step: first the user's own miss, then "want to see it done cleanly?".
 */
@Composable
fun TacticSimulationScreen(
    simulation: TacticSimulation,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.simulation_missed_title, tacticTypeName(simulation.tactic.type)),
    /** Text for the starting position; defaults to the "watch what happens" lead-in. */
    introText: String? = null,
    doneLabel: String = stringResource(R.string.simulation_back_to_game),
    /** When non-null, the final step offers the textbook example of this pattern. */
    onSeeReference: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    var step by remember(simulation) { mutableIntStateOf(0) }
    val totalPlies = simulation.pvUci.size

    val positions = remember(simulation) { replayPositions(simulation) }
    val board: BoardState = remember(positions, step) {
        positions.getOrNull(step)?.toBoardState() ?: BoardState.startingPosition()
    }
    val upcomingArrow = remember(simulation, step) {
        if (step < totalPlies) {
            uciToUiSquarePair(simulation.pvUci[step])?.let { (from, to) -> BoardArrow(from, to, GreenPrimary) }
        } else null
    }
    // White-down always, matching the review screen the user just came from — a walkthrough that
    // silently flips the board reads as a different position.
    val orientation = BoardOrientation.WHITE_DOWN

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.simulation_close))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChessBoard(
                    board = board,
                    orientation = orientation,
                    arrows = listOfNotNull(upcomingArrow),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { step = (step - 1).coerceAtLeast(0) }, enabled = step > 0) {
                    Icon(Icons.Filled.NavigateBefore, contentDescription = stringResource(R.string.simulation_previous_step))
                }
                Text(
                    text = if (step == 0) stringResource(R.string.simulation_starting_position)
                    else "${step}. ${simulation.pvSan.getOrNull(step - 1).orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = { step = (step + 1).coerceAtMost(totalPlies) },
                    enabled = step < totalPlies,
                ) {
                    Icon(Icons.Filled.NavigateNext, contentDescription = stringResource(R.string.simulation_next_step))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val explanation = when {
                        step > 0 -> simulation.perPlyExplanation.getOrNull(step - 1).orEmpty()
                        introText != null -> introText
                        else -> stringResource(
                            R.string.simulation_watch_intro,
                            simulation.pvSan.firstOrNull().orEmpty(),
                            simulation.tactic.description.ifBlank { tacticTypeName(simulation.tactic.type).lowercase() },
                        )
                    }
                    Text(text = explanation, style = MaterialTheme.typography.bodyLarge)

                    if (step == totalPlies) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.simulation_payoff, simulation.payoffDescription),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        if (onSeeReference != null) {
                            // The offer, not a quiz: it appears once the miss has been understood.
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.simulation_reference_offer, tacticTypeName(simulation.tactic.type).lowercase()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = onSeeReference) {
                                Text(stringResource(R.string.simulation_reference_button))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(doneLabel)
            }
        }
    }
}

/** Replays [simulation]'s PV from its start FEN, returning one Position per step (0..pvUci.size). */
private fun replayPositions(simulation: TacticSimulation): List<Position> {
    val out = ArrayList<Position>(simulation.pvUci.size + 1)
    var pos = try {
        Position.fromFen(simulation.startFen)
    } catch (e: Exception) {
        Position.startPosition()
    }
    out.add(pos)
    for (uci in simulation.pvUci) {
        val move = try {
            pos.parseUci(uci)
        } catch (e: Exception) {
            break
        }
        pos = pos.makeMove(move)
        out.add(pos)
    }
    return out
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 900)
@Composable
private fun TacticSimulationScreenPreview() {
    val startFen = "rnbqkb1r/pppp1ppp/5n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 4 3"
    val sample = TacticSimulation(
        startFen = startFen,
        pvUci = listOf("f3e5"),
        pvSan = listOf("Nxe5"),
        perPlyExplanation = listOf("Nxe5 wins material."),
        tactic = net.palaya.chessanalyzer.core.analysis.TacticInstance(
            type = net.palaya.chessanalyzer.core.analysis.TacticType.HANGING_PIECE,
            byColor = net.palaya.chessanalyzer.core.chess.Color.WHITE,
            moveUci = "f3e5",
            materialSwing = 100,
            description = "wins a hanging pawn",
        ),
        payoffDescription = "wins a pawn",
    )
    ChessAnalyzerTheme {
        TacticSimulationScreen(simulation = sample, onDone = {}, onSeeReference = {})
    }
}
