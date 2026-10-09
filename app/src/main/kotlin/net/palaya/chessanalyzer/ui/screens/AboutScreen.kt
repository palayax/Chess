@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.BuildConfig
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import androidx.compose.ui.semantics.Role
import net.palaya.chessanalyzer.data.GeneratedEngineVersion
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * "About" screen — brand identity, version info, and the license/attribution notices the app is
 * legally required to surface because it links Stockfish (GPLv3). Reachable from
 * Settings > About > [R.string.settings_about_open].
 *
 * License text is read from bundled assets rather than hardcoded so it always matches exactly
 * what ships in the APK:
 *  - `engine/src/main/assets/STOCKFISH_LICENSE.txt` (packaged into the app via the `:engine`
 *    module's assets) — the full GPLv3 text, shown in a scrollable dialog.
 *  - `app/src/main/assets/PIECES_LICENSE.txt` — piece artwork attribution (CC BY-SA 3.0
 *    Cburnett), added by a separate in-flight change. If it isn't present yet this screen falls
 *    back to [R.string.about_license_pieces_body_fallback] rather than omitting the section.
 */
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val appVersion = BuildConfig.VERSION_NAME

    var licenseDialog by rememberSaveable { mutableStateOf<LicenseDialog?>(null) }

    val sourceUrl = stringResource(R.string.about_license_source_url)
    val ownerName = stringResource(R.string.about_owner_name)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                AboutHeader(appVersion = appVersion)
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                SectionHeader(stringResource(R.string.about_owner_header))
                SettingsCard {
                    Text(
                        text = ownerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val contact = stringResource(R.string.about_owner_contact)
                        Text(
                            text = stringResource(R.string.about_owner_contact_label) + ": " + contact,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickableEmail(context, contact),
                        )
                    }
                    // Privacy policy: opened in the browser by the platform, like the other links (the app
                    // makes no request). The whole row is the 48 dp target, a button to TalkBack.
                    val privacyUrl = stringResource(R.string.about_privacy_policy_url)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableUrl(uriHandler, privacyUrl),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PrivacyTip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.about_privacy_policy_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_parent_brand),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickableUrl(uriHandler, "https://palaya.net"),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            item {
                SectionHeader(stringResource(R.string.about_licenses_header))
                SettingsCard {
                    // --- Stockfish / GPLv3 ---
                    Text(
                        text = stringResource(R.string.about_license_stockfish_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.asHeading(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.about_license_stockfish_body,
                            GeneratedEngineVersion.LABEL,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.about_license_stockfish_link),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickableUrl(uriHandler, "https://stockfishchess.org"),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_license_source_label) + ": " + sourceUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (sourceUrl != "SOURCE_REPO_URL") {
                            Modifier.clickableUrl(uriHandler, sourceUrl)
                        } else {
                            Modifier
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { licenseDialog = LicenseDialog.STOCKFISH_GPL }) {
                        Icon(Icons.Filled.Gavel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.about_license_stockfish_view))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

                    // --- Opening book / CC0 ---
                    Text(
                        text = stringResource(R.string.about_license_openings_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.asHeading(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_license_openings_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

                    // --- Famous games (G1): the scores are factual records, the words are ours ---
                    Text(
                        text = stringResource(R.string.about_license_famous_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.asHeading(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_license_famous_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

                    // --- Piece artwork ---
                    Text(
                        text = stringResource(R.string.about_license_pieces_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.asHeading(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val piecesFullText by producePieceAttributionText()
                    Text(
                        text = if (piecesFullText != null) {
                            stringResource(R.string.about_license_pieces_body_found)
                        } else {
                            stringResource(R.string.about_license_pieces_body_fallback)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (piecesFullText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { licenseDialog = LicenseDialog.PIECES_ATTRIBUTION }) {
                            Icon(Icons.Filled.Gavel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.about_license_pieces_view))
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

                    // --- On-device neural narration: sherpa-onnx + voice models ---
                    Text(
                        text = stringResource(R.string.about_license_neural_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.asHeading(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_license_neural_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { licenseDialog = LicenseDialog.NEURAL_VOICE_ATTRIBUTION }) {
                        Icon(Icons.Filled.Gavel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.about_license_neural_view))
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            item {
                Text(
                    text = stringResource(R.string.about_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }

    val dialog = licenseDialog
    if (dialog != null) {
        val licenseText by produceState(initialValue = "Loading…", dialog) {
            value = when (dialog) {
                LicenseDialog.STOCKFISH_GPL -> readAssetText(context, "STOCKFISH_LICENSE.txt")
                    ?: "STOCKFISH_LICENSE.txt not found in this build."
                LicenseDialog.PIECES_ATTRIBUTION -> readAssetText(context, "PIECES_LICENSE.txt")
                    ?: "PIECES_LICENSE.txt not found in this build."
                LicenseDialog.NEURAL_VOICE_ATTRIBUTION -> readAssetText(context, "NEURAL_VOICE_LICENSE.txt")
                    ?: "NEURAL_VOICE_LICENSE.txt not found in this build."
            }
        }
        val dialogTitle = when (dialog) {
            LicenseDialog.STOCKFISH_GPL -> stringResource(R.string.about_license_stockfish_view)
            LicenseDialog.PIECES_ATTRIBUTION -> stringResource(R.string.about_license_pieces_view)
            LicenseDialog.NEURAL_VOICE_ATTRIBUTION -> stringResource(R.string.about_license_neural_view)
        }
        AlertDialog(
            onDismissRequest = { licenseDialog = null },
            title = { Text(dialogTitle) },
            text = {
                Text(
                    text = licenseText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { licenseDialog = null }) {
                    Text(stringResource(R.string.about_dialog_close))
                }
            },
        )
    }
}

private enum class LicenseDialog { STOCKFISH_GPL, PIECES_ATTRIBUTION, NEURAL_VOICE_ATTRIBUTION }

@Composable
private fun AboutHeader(appVersion: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Image(
                painter = painterResource(R.drawable.palaya_logo),
                contentDescription = stringResource(R.string.about_cd_logo),
                modifier = Modifier
                    .size(96.dp)
                    .padding(12.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.asHeading(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_app_version, appVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.about_engine_version, GeneratedEngineVersion.LABEL),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            // The engine's network file, named by its own hash; moved here from Settings (U9).
            text = stringResource(R.string.about_net_version, NetStore.NET_FILENAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp).asHeading(),
    )
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/**
 * Reads `app/src/main/assets/PIECES_LICENSE.txt` (piece artwork attribution, expected to be
 * added alongside new Cburnett-derived piece art by a separate change). Returns null when the
 * asset doesn't exist yet, in which case the caller shows
 * [R.string.about_license_pieces_body_fallback] instead of a "view full attribution" dialog.
 */
@Composable
private fun producePieceAttributionText(): androidx.compose.runtime.State<String?> {
    val context = LocalContext.current
    return produceState<String?>(initialValue = null) {
        value = readAssetText(context, "PIECES_LICENSE.txt")?.trim()?.ifBlank { null }
    }
}

private fun readAssetText(context: android.content.Context, assetName: String): String? {
    return try {
        context.assets.open(assetName).use { stream ->
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        }
    } catch (e: IOException) {
        null
    }
}

@Composable
private fun Modifier.clickableUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String): Modifier =
    this.clickableAction { uriHandler.openUri(url) }

@Composable
private fun Modifier.clickableEmail(context: android.content.Context, address: String): Modifier =
    this.clickableAction {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address"))
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No mail app available — nothing else to do from here.
        }
    }

@Composable
private fun Modifier.clickableAction(onClick: () -> Unit): Modifier =
    // A link is a button to TalkBack, and its target is at least 48 dp tall (a line of body text
    // is about 20 dp). The text sits at the start of that target.
    this.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).wrapContentHeight(Alignment.CenterVertically)

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 1400)
@Composable
private fun AboutScreenPreview() {
    ChessAnalyzerTheme {
        AboutScreen()
    }
}
