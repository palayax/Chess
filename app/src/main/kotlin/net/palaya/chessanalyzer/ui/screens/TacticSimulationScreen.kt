@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.a11y.isLandscape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.narration.NarrationLocales
import net.palaya.chessanalyzer.core.narration.SimulationIntro
import net.palaya.chessanalyzer.data.mapper.toBoardState
import net.palaya.chessanalyzer.data.mapper.uciToUiSquarePair
import net.palaya.chessanalyzer.ui.board.BoardArrow
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.walkthroughButtonIsDone
import net.palaya.chessanalyzer.ui.model.walkthroughMove
import net.palaya.chessanalyzer.ui.model.walkthroughNextStep
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
 * **One exit** (docs/MOBILE_UX_DESIGN.md 6.5): the back arrow and a single primary button at the
 * bottom. The button reads "Next" and advances, and on the last step reads "Done" and leaves; the
 * arrow and "Done" both return to where the user came from (the Summary or the Board). The first
 * step is captioned "Before the mistake", later steps name the move played ("8. Bxa6") with a
 * "2 / 5" counter.
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
    /**
     * Caption of the first step. A missed-tactic walkthrough starts "Before the mistake"; the textbook
     * example has no mistake in it and passes "Starting position".
     */
    startCaption: String = stringResource(R.string.simulation_before),
    /** When non-null, the final step offers the textbook example of this pattern. */
    onSeeReference: (() -> Unit)? = null,
    /**
     * When non-null, the final step offers "Try it yourself" (a text button) that opens Practise at
     * this move. The caller passes it only when the ply is a practice puzzle; otherwise it is omitted.
     */
    onTryIt: (() -> Unit)? = null,
    /** The one exit: the back arrow and the final "Done" both call it. */
    onDone: () -> Unit,
) {
    var step by remember(simulation) { mutableIntStateOf(0) }
    val totalPlies = simulation.pvUci.size
    // Assembled in :core (two clean sentences, whatever the tactic says), in the language the
    // resources resolved to, so the intro and the rest of the walkthrough cannot disagree.
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag()
    val watchIntro = remember(simulation, languageTag) {
        SimulationIntro.text(simulation, NarrationLocales.forTag(languageTag))
    }

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

    val isDone = walkthroughButtonIsDone(step, totalPlies)
    val landscape = isLandscape()
    val nextButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(
            onClick = { if (isDone) onDone() else step = walkthroughNextStep(step, totalPlies) },
            modifier = buttonModifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(stringResource(if (isDone) R.string.common_done else R.string.common_next))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                // Back arrow, not an X: this is a step deeper in a stack like every other screen. It
                // leaves the same way "Done" does.
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        bottomBar = {
            // The single primary action: "Next" walks the steps and becomes "Done" on the last one,
            // which leaves. It replaces the old second exit ("Back to the game"). Landscape has no
            // room for a bottom bar, so there the same button sits under the card instead.
            if (!landscape) {
                // Lifted above the navigation bar (edge to edge: the bottom bar owns its insets).
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.navigationBarsPadding()) {
                        nextButton(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        },
    ) { innerPadding ->
        // The stepper and the card, shared by the portrait and the landscape arrangements.
        val detail: @Composable () -> Unit = {
            // The caption: the first step reads "Before the mistake"; later steps name the move
            // being played ("8. Bxa6") with a "2 / 5" step counter under it. The counter takes its
            // space on the first step too (just not drawn), so the card below does not jump.
            val caption: String = if (step == 0) {
                startCaption
            } else {
                val move = walkthroughMove(simulation.startFen, step, simulation.pvSan.getOrNull(step - 1).orEmpty())
                stringResource(
                    if (move.isWhite) R.string.simulation_move_white else R.string.simulation_move_black,
                    move.number,
                    move.san,
                )
            }
            val counter = stringResource(R.string.simulation_step_counter, step, totalPlies)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { step = (step - 1).coerceAtLeast(0) }, enabled = step > 0) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = stringResource(R.string.simulation_previous_step))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.titleMedium.copy(
                            // Notation is a left-to-right script in every language; the text itself
                            // also starts with an LRM, so neither the digits nor the dots reorder.
                            textDirection = if (step == 0) TextDirection.Content else TextDirection.Ltr,
                        ),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.asHeading(),
                    )
                    Text(
                        text = counter,
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = if (step == 0) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier,
                    )
                }
                IconButton(
                    onClick = { step = walkthroughNextStep(step, totalPlies) },
                    enabled = step < totalPlies,
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = stringResource(R.string.simulation_next_step))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.medium,
            ) {
                // Polite live region: Next and Previous change this text, and TalkBack speaks the new
                // step without the user having to swipe back to find it.
                Column(modifier = Modifier.padding(16.dp).semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }) {
                    val explanation = when {
                        step > 0 -> simulation.perPlyExplanation.getOrNull(step - 1).orEmpty()
                        introText != null -> introText
                        else -> watchIntro
                    }
                    Text(
                        text = explanation,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    )

                    if (step == totalPlies) {
                        // A line that neither mates nor nets material has no payoff the board proves
                        // (ANALYSIS_SPEC §6.1): the "Result" row is simply not shown for it.
                        if (simulation.payoffDescription.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.simulation_payoff, simulation.payoffDescription),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (onSeeReference != null) {
                            // The offer, not a quiz: it appears once the miss has been understood.
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.simulation_reference_offer, tacticTypeName(simulation.tactic.type).lowercase()),
                                // Content direction: an English sentence keeps its full stop at its own end in RTL
                                // (it was drawn ".board" at the start of the last line).
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = onSeeReference, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.simulation_reference_button))
                            }
                        }
                        if (onTryIt != null) {
                            // Practise this very position: a quiet text button, so the screen keeps its
                            // one primary action ("Done").
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = onTryIt, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.simulation_try_it_yourself))
                            }
                        }
                    }
                }
            }
        }

        val boardView: @Composable (Modifier) -> Unit = { boardModifier ->
            ChessBoard(
                board = board,
                orientation = orientation,
                arrows = listOfNotNull(upcomingArrow),
                modifier = boardModifier,
            )
        }

        if (landscape) {
            // Board on the left (as tall as the window allows, at most half its width), the stepper, the
            // card and the Next/Done button on the right, the stepper and card scrolling.
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                val boardSide = minOf(maxHeight - 16.dp, maxWidth * 0.5f)
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        boardView(Modifier.size(boardSide))
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            detail()
                        }
                        nextButton(Modifier.padding(top = 8.dp))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    boardView(Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(16.dp))
                detail()
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
