@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme

/** MIME types accepted by the SAF file picker for PGN import. */
val PGN_PICKER_MIME_TYPES = arrayOf(
    "application/x-chess-pgn",
    "application/vnd.chess-pgn",
    "text/x-chess-pgn",
    "text/plain",
    "application/octet-stream",
)

/**
 * Stateful entry point used by navigation: owns the SAF document-picker launcher and
 * forwards results to the caller. [onFilePicked] receives the raw content [Uri] — reading
 * its text is left to the caller (see `net.palaya.chessanalyzer.util.readTextFromUri`)
 * so this screen has no ContentResolver dependency of its own.
 */
@Composable
fun ImportScreen(
    onFilePicked: (Uri) -> Unit,
    onPastePgnSubmit: (String) -> Unit,
    onRecentGameSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Real data is always passed by the caller (see ChessAnalyzerNavHost). Defaulting to the
    // placeholder sample here was one omitted argument away from showing fake games in
    // production; previews pass PlaceholderData.sampleRecent explicitly instead.
    recentGames: List<RecentGameSummary> = emptyList(),
    onSettingsClick: (() -> Unit)? = null,
) {
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onFilePicked) }

    ImportScreenContent(
        modifier = modifier,
        recentGames = recentGames,
        onChooseFileClick = { pickerLauncher.launch(PGN_PICKER_MIME_TYPES) },
        onPastePgnSubmit = onPastePgnSubmit,
        onRecentGameSelected = onRecentGameSelected,
        onSettingsClick = onSettingsClick,
    )
}

/** Pure, previewable content — no SAF/activity-result dependency, safe for @Preview. */
@Composable
fun ImportScreenContent(
    onChooseFileClick: () -> Unit,
    onPastePgnSubmit: (String) -> Unit,
    onRecentGameSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    recentGames: List<RecentGameSummary> = emptyList(),
    onSettingsClick: (() -> Unit)? = null,
) {
    var pastedPgn by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    if (onSettingsClick != null) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                EmptyStateCard(onChooseFileClick = onChooseFileClick)
            }

            item {
                PastePgnCard(
                    value = pastedPgn,
                    onValueChange = { pastedPgn = it },
                    onSubmit = { onPastePgnSubmit(pastedPgn) },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.import_recent_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (recentGames.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.import_recent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(recentGames, key = { it.id }) { game ->
                    RecentGameRow(game = game, onClick = { onRecentGameSelected(game.id) })
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(onChooseFileClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.import_empty_headline),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.import_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onChooseFileClick) {
                Icon(imageVector = Icons.Filled.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text(text = "  " + stringResource(R.string.import_choose_file))
            }
        }
    }
}

@Composable
private fun PastePgnCard(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.import_paste_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                placeholder = { Text(stringResource(R.string.import_paste_placeholder)) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSubmit,
                enabled = value.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text(text = "  " + stringResource(R.string.import_paste_action))
            }
        }
    }
}

@Composable
private fun RecentGameRow(game: RecentGameSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${game.white} vs ${game.black}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${game.date} · ${game.plyCount} plies",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = game.result,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun ImportScreenPreview() {
    ChessAnalyzerTheme {
        ImportScreenContent(
            onChooseFileClick = {},
            onPastePgnSubmit = {},
            onRecentGameSelected = {},
            recentGames = PlaceholderData.sampleRecent,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun ImportScreenEmptyPreview() {
    ChessAnalyzerTheme {
        ImportScreenContent(
            onChooseFileClick = {},
            onPastePgnSubmit = {},
            onRecentGameSelected = {},
            recentGames = emptyList(),
        )
    }
}
