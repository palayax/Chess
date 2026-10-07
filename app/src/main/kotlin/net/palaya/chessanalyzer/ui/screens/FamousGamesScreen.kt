@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.model.FamousEra
import net.palaya.chessanalyzer.ui.model.FamousGame
import net.palaya.chessanalyzer.ui.model.FamousGamesLibrary
import net.palaya.chessanalyzer.ui.model.groupFamousGames
import net.palaya.chessanalyzer.ui.model.pgnPlyCount
import net.palaya.chessanalyzer.ui.model.pgnTags
import net.palaya.chessanalyzer.ui.model.searchFamousGames
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.video.versusLine

/** What the library screen has to show: still reading the assets, the library, or a failure to read them. */
sealed interface FamousGamesState {
    data object Loading : FamousGamesState
    data class Ready(val library: FamousGamesLibrary) : FamousGamesState
    data object Failed : FamousGamesState
}

/** Test tag of the search field (FamousGamesInstrumentedTest types into it). */
const val FAMOUS_SEARCH_TAG = "famous_search"

/**
 * The famous-games library (G1, docs/FAMOUS_GAMES.md): a search field, the games grouped by era, and a sheet
 * per game with our description and one action, "Review this game", which hands the game's PGN text to the
 * same flow as a shared game ([onReview]; the Setup gate included). Pattern from chess.com's master-games
 * library (search by player or year, rows with players, year and result, one action to analyse); see
 * RUN_LOG "G1". The list scrolls in landscape too; nothing is clipped at a 2.0 font.
 */
@Composable
fun FamousGamesScreen(
    state: FamousGamesState,
    onBack: () -> Unit,
    onReview: (pgnText: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // The open sheet survives rotation (by id; the library itself is re-read from the cache).
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.famous_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        when (state) {
            FamousGamesState.Loading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            FamousGamesState.Failed -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.famous_load_failed),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                )
            }
            is FamousGamesState.Ready -> {
                val matches = remember(state.library, query) { searchFamousGames(state.library.games, query) }
                val groups = remember(matches) { groupFamousGames(matches) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(FAMOUS_SEARCH_TAG),
                            singleLine = true,
                            label = { Text(stringResource(R.string.famous_search_label)) },
                            placeholder = { Text(stringResource(R.string.famous_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.famous_search_clear))
                                    }
                                }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        )
                    }
                    if (groups.isEmpty()) {
                        item(key = "none") {
                            Text(
                                text = stringResource(R.string.famous_no_results, query.trim()),
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Said once the list has emptied, as the Practise feedback is.
                                modifier = Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                    groups.forEach { (era, games) ->
                        item(key = "era-${era.key}") {
                            Text(
                                text = famousEraLabel(era),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).asHeading(),
                            )
                        }
                        items(games, key = { it.id }) { game ->
                            FamousGameRow(game = game, onClick = {
                                focus.clearFocus()
                                selectedId = game.id
                            })
                        }
                    }
                }
                val selected = selectedId?.let { id -> state.library.games.firstOrNull { it.id == id } }
                val selectedPgn = selected?.let { state.library.pgnFor(it.id) }
                if (selected != null && selectedPgn != null) {
                    FamousGameSheet(
                        game = selected,
                        pgnText = selectedPgn,
                        onDismiss = { selectedId = null },
                        onReview = {
                            // Closed first, so coming back from the review shows the list, not the sheet again.
                            selectedId = null
                            onReview(selectedPgn)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun famousEraLabel(era: FamousEra): String = stringResource(
    when (era) {
        FamousEra.ROMANTIC -> R.string.famous_era_romantic
        FamousEra.CLASSICAL -> R.string.famous_era_classical
        FamousEra.INTERWAR -> R.string.famous_era_interwar
        FamousEra.POSTWAR -> R.string.famous_era_postwar
        FamousEra.KASPAROV -> R.string.famous_era_kasparov
        FamousEra.MODERN -> R.string.famous_era_modern
        FamousEra.COMPUTERS -> R.string.famous_era_computers
    },
)

/** "1851 · 1-0": the year and the result, LRM-protected so a score is never reordered in a right-to-left line. */
internal fun famousYearAndResult(year: Int, result: String): String = "\u200E$year · $result\u200E"

@Composable
private fun FamousGameRow(game: FamousGame, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = game.title,
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
            )
            Text(
                // Names stay left to right (notation and names are Latin script), as on Home's recent games.
                text = versusLine(stringResource(R.string.game_vs_format), game.white, game.black),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = famousYearAndResult(game.year, game.result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FamousGameSheet(
    game: FamousGame,
    pgnText: String,
    onDismiss: () -> Unit,
    onReview: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tags = remember(pgnText) { pgnTags(pgnText) }
    val moves = remember(pgnText) { (pgnPlyCount(pgnText) + 1) / 2 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = game.title,
                style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                modifier = Modifier.asHeading(),
            )
            Text(
                text = versusLine(stringResource(R.string.game_vs_format), game.white, game.black),
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
            )
            val place = listOfNotNull(tags["Event"], tags["Site"]).filter { it.isNotBlank() && it != "?" }.distinct().joinToString(", ")
            if (place.isNotEmpty()) {
                Text(
                    text = place,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.famous_sheet_facts,
                    famousYearAndResult(game.year, game.result),
                    "\u2068" + pluralStringResource(R.plurals.recent_moves_count, moves, moves) + "\u2069",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = game.description,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onReview,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.famous_review_action))
            }
        }
    }
}

/**
 * The Home entry (G1): one card under "Review a game you played", like chess.com's entry into its game library
 * (pattern only). The whole card is the button.
 */
@Composable
internal fun FamousGamesEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LocalLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.famous_home_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.famous_home_entry_body),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun FamousGamesScreenPreview() {
    val pgn = "[Event \"London\"]\n[Site \"London\"]\n[Date \"1851.06.21\"]\n[White \"Adolf Anderssen\"]\n" +
        "[Black \"Lionel Kieseritzky\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. f4 exf4 1-0"
    val game = FamousGame("immortal", FamousEra.ROMANTIC, "The Immortal Game", "Adolf Anderssen", "Lionel Kieseritzky", 1851, "1-0", "Played in London in 1851.")
    ChessAnalyzerTheme {
        FamousGamesScreen(
            state = FamousGamesState.Ready(FamousGamesLibrary(listOf(game), mapOf(game.id to pgn))),
            onBack = {},
            onReview = {},
        )
    }
}
