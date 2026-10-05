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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import net.palaya.chessanalyzer.ui.board.PieceGeometry
import net.palaya.chessanalyzer.ui.model.PieceType
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.video.versusLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    /** Hoisted so a failed analysis does not lose what the user pasted (see AnalysisViewModel.pasteDraft). */
    pastedPgn: String = "",
    onPastedPgnChange: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
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
        pastedPgn = pastedPgn,
        onPastedPgnChange = onPastedPgnChange,
        snackbarHostState = snackbarHostState,
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
    pastedPgn: String = "",
    onPastedPgnChange: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** Test/preview hook: open the paste sheet on first composition. Saved across rotation. */
    initiallyShowPasteSheet: Boolean = false,
) {
    // The paste field lives in a sheet, not on the page: sharing a game into the app is the main
    // way in, so a permanently open text box was mostly noise. Saveable so rotating the phone with
    // the sheet open does not drop the user back to Home.
    var showPasteSheet by rememberSaveable { mutableStateOf(initiallyShowPasteSheet) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.home_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // Same card either way: the full welcome while there is nothing to look at, a
                // compact "add another" strip once recent games exist so the list dominates.
                StartCard(
                    expanded = recentGames.isEmpty(),
                    onChooseFileClick = onChooseFileClick,
                    onPasteClick = { showPasteSheet = true },
                )
            }

            if (recentGames.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.import_recent_header),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 4.dp).asHeading(),
                    )
                }
                items(recentGames, key = { it.id }) { game ->
                    RecentGameRow(game = game, onClick = { onRecentGameSelected(game.id) })
                }
            }
        }
    }

    if (showPasteSheet) {
        PasteMovesSheet(
            value = pastedPgn,
            onValueChange = onPastedPgnChange,
            onDismiss = { showPasteSheet = false },
            onSubmit = { text ->
                showPasteSheet = false
                onPastePgnSubmit(text)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartCard(
    expanded: Boolean,
    onChooseFileClick: () -> Unit,
    onPasteClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        if (expanded) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KnightIcon(modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.import_empty_headline),
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_empty_body),
                    // Content direction: a sentence's own language decides how its punctuation sits,
                    // so an English sentence does not get its full stop thrown to the wrong end in RTL.
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                // The one primary action on the screen: filled, full width.
                Button(
                    onClick = onChooseFileClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(imageVector = Icons.Filled.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.import_choose_file))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPasteClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(imageVector = Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.import_paste_label))
                }
            }
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_review_another),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.asHeading(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                // FlowRow: at large font scale or on a narrow phone the two buttons wrap onto
                // two lines instead of clipping their labels.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onChooseFileClick,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(imageVector = Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.import_open_file_short))
                    }
                    OutlinedButton(
                        onClick = onPasteClick,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(imageVector = Icons.Filled.ContentPaste, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.import_paste_short))
                    }
                }
            }
        }
    }
}

/** The app's own Cburnett knight (the one on the board), filled in the brand green. */
@Composable
private fun KnightIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val path = PieceGeometry.pathFor(PieceType.KNIGHT)
        val bounds = PieceGeometry.boundsFor(PieceType.KNIGHT)
        val fit = minOf(size.width / bounds.width, size.height / bounds.height)
        val left = (size.width - bounds.width * fit) / 2f - bounds.left * fit
        val top = (size.height - bounds.height * fit) / 2f - bounds.top * fit
        translate(left = left, top = top) {
            scale(scale = fit, pivot = Offset.Zero) {
                drawPath(path = path, color = color)
            }
        }
    }
}

/** Whether the sheet's Analyze button may be pressed: there is something other than whitespace. */
internal fun isPasteSubmittable(text: String): Boolean = text.isNotBlank()

/** What the clipboard button puts in the field, or null when the clipboard holds nothing useful. */
internal fun clipboardPasteText(clip: CharSequence?): String? =
    clip?.toString()?.takeIf { it.isNotBlank() }

@Composable
private fun PasteMovesSheet(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var clipboardEmpty by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.import_paste_label),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.asHeading(),
            )
            OutlinedTextField(
                value = value,
                onValueChange = {
                    clipboardEmpty = false
                    onValueChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 10,
                // A real label, not only a placeholder: TalkBack names the field, and the name stays
                // once text has been typed.
                label = { Text(stringResource(R.string.import_paste_label)) },
                placeholder = { Text(stringResource(R.string.import_paste_placeholder)) },
                supportingText = if (clipboardEmpty) {
                    { Text(stringResource(R.string.import_clipboard_empty)) }
                } else {
                    null
                },
            )
            TextButton(
                onClick = {
                    val pasted = clipboardPasteText(clipboard.getText())
                    clipboardEmpty = pasted == null
                    if (pasted != null) onValueChange(pasted)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(imageVector = Icons.Filled.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.import_paste_from_clipboard))
            }
            Button(
                onClick = { onSubmit(value.trim()) },
                enabled = isPasteSubmittable(value),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.import_paste_action))
            }
        }
    }
}

@Composable
private fun RecentGameRow(game: RecentGameSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = versusLine(stringResource(R.string.game_vs_format), game.white, game.black),
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                )
                Text(
                    text = recentGameSubtitle(game),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // LRM: a score like "1-0" or "½-½" must not be reordered inside an RTL paragraph.
                text = "\u200E" + game.result,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "14 Mar 2026 · 17 moves": the PGN date rendered in the user's locale and the ply count turned
 * into whole moves (a "ply" is half a move; nobody outside engine code says it). A date that is
 * not a real PGN date ("????.??.??", or a free-form string) is dropped rather than shown raw.
 */
@Composable
private fun recentGameSubtitle(game: RecentGameSummary): String {
    // FSI..PDI isolates "17 moves" so its own direction (taken from its first letter) cannot be
    // reshuffled by the surrounding RTL paragraph, e.g. into "moves 17" next to a Hebrew date.
    val moves = "\u2068" +
        pluralStringResource(R.plurals.recent_moves_count, (game.plyCount + 1) / 2, (game.plyCount + 1) / 2) +
        "\u2069"
    val date = formatRecentGameDate(game.date, Locale.getDefault())
    return if (date == null) moves else stringResource(R.string.recent_game_subtitle, date, moves)
}

/** A PGN `yyyy.MM.dd` date as a medium localized date; other plain text unchanged; null when the date is unknown. */
internal fun formatRecentGameDate(raw: String, locale: Locale): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.contains('?')) return null
    val parsed = Regex("""(\d{4})\.(\d{2})\.(\d{2})""").matchEntire(trimmed)
        ?: return trimmed
    return try {
        val (y, m, d) = parsed.destructured
        LocalDate.of(y.toInt(), m.toInt(), d.toInt())
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    } catch (e: Exception) {
        trimmed
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
