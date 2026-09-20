@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudTtsProtocol
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel.CloudKeyCheckState

/**
 * The Google Cloud key wizard. Obtaining a key is genuinely fiddly (project -> enable API ->
 * **billing** -> key -> restrict), and a wizard that walks someone into a dead end is worse than
 * no wizard — so the billing requirement is the FIRST thing on the screen, before step 1, in a
 * card that also says what exceeding the free tier costs and who pays (the user). Each step offers
 * a Cloud Console deep link; the last step takes the key and validates it with a real request via
 * [onTestAndSave] before anything is stored.
 *
 * Pure presentation: all state comes in as parameters and every action goes out as a callback,
 * the same shape as [SettingsScreen], so the nav host owns the ViewModel plumbing.
 */
@Composable
fun CloudVoiceSetupScreen(
    voice: GoogleCloudVoice,
    checkState: CloudKeyCheckState,
    onTestAndSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowserTemplate = stringResource(R.string.cloud_setup_no_browser)

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(noBrowserTemplate.format(url)) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.cloud_setup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                BillingNoticeCard(voice, onOpenPricing = { open(GoogleCloudTtsProtocol.URL_PRICING) })
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.cloud_setup_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                StepCard(1, R.string.cloud_setup_step1_title, stringResource(R.string.cloud_setup_step1_body)) {
                    open(GoogleCloudTtsProtocol.URL_CREATE_PROJECT)
                }
                StepCard(2, R.string.cloud_setup_step2_title, stringResource(R.string.cloud_setup_step2_body)) {
                    open(GoogleCloudTtsProtocol.URL_ENABLE_API)
                }
                StepCard(3, R.string.cloud_setup_step3_title, stringResource(R.string.cloud_setup_step3_body)) {
                    open(GoogleCloudTtsProtocol.URL_BILLING)
                }
                StepCard(4, R.string.cloud_setup_step4_title, stringResource(R.string.cloud_setup_step4_body)) {
                    open(GoogleCloudTtsProtocol.URL_CREDENTIALS)
                }
                StepCard(5, R.string.cloud_setup_step5_title, stringResource(R.string.cloud_setup_step5_body)) {
                    open(GoogleCloudTtsProtocol.URL_CREDENTIALS)
                }
            }

            item {
                KeyEntryStep(voice = voice, checkState = checkState, onTestAndSave = onTestAndSave, onDone = onBack)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The up-front disclosure. Deliberately the loudest element on the screen — it is the one that saves a user six wasted steps. */
@Composable
private fun BillingNoticeCard(voice: GoogleCloudVoice, onOpenPricing: () -> Unit) {
    val tier = voice.tier
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.cloud_setup_billing_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.cloud_setup_billing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.cloud_setup_billing_cost,
                    "%,d".format(tier.freeCharsPerMonth),
                    tier.label,
                    tier.freeReviewsPerMonth,
                    tier.usdPerMillionChars,
                    GoogleCloudTtsProtocol.NEW_CUSTOMER_TRIAL_USD,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onOpenPricing, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.cloud_setup_pricing_link), color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, titleRes: Int, body: String, onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.cloud_setup_step, number),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpen) {
                Text(stringResource(R.string.cloud_setup_open_console))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Step 6: paste + "Test and save". The field is password-masked by default (a key is a secret,
 * and screenshots happen) with a Show toggle for checking a paste. The result line reports the
 * plain-language verdict from [GoogleCloudTtsProtocol], never raw JSON.
 */
@Composable
private fun KeyEntryStep(
    voice: GoogleCloudVoice,
    checkState: CloudKeyCheckState,
    onTestAndSave: (String) -> Unit,
    onDone: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val checking = checkState is CloudKeyCheckState.Checking
    val saved = checkState as? CloudKeyCheckState.Saved

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.cloud_setup_step, 6),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.cloud_setup_step6_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.cloud_setup_step6_body, voice.keyCheckText, "${voice.languageCode} ${voice.label}"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.cloud_setup_key_label)) },
                singleLine = true,
                enabled = !checking && saved == null,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                trailingIcon = {
                    TextButton(onClick = { reveal = !reveal }) {
                        Text(stringResource(if (reveal) R.string.cloud_setup_key_hide else R.string.cloud_setup_key_show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            when (checkState) {
                CloudKeyCheckState.Idle -> Unit
                CloudKeyCheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_setup_testing), style = MaterialTheme.typography.bodyMedium)
                }
                is CloudKeyCheckState.Rejected -> Text(
                    checkState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is CloudKeyCheckState.Saved -> Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cloud_setup_saved, "${checkState.voice.languageCode} ${checkState.voice.label}", checkState.durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (checkState !is CloudKeyCheckState.Idle) Spacer(Modifier.height(12.dp))

            if (saved != null) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_setup_done)) }
            } else {
                Button(
                    onClick = { onTestAndSave(key) },
                    enabled = !checking && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.cloud_setup_test_and_save)) }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 1600)
@Composable
private fun CloudVoiceSetupPreview() {
    ChessAnalyzerTheme {
        CloudVoiceSetupScreen(
            voice = GoogleCloudVoice.DEFAULT,
            checkState = CloudKeyCheckState.Rejected("Google rejected this API key. (HTTP 400: API key not valid.)"),
            onTestAndSave = {},
            onBack = {},
        )
    }
}
