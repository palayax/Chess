package net.palaya.chessanalyzer.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.ui.model.RephraseRow
import net.palaya.chessanalyzer.ui.model.RephraseRowView
import net.palaya.chessanalyzer.ui.model.SetupPrecheck
import net.palaya.chessanalyzer.ui.model.gigabytesLabel
import net.palaya.chessanalyzer.ui.screens.RephraseSettingsContent
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C2 (docs/LLM_REPHRASE_DESIGN.md §7, §8.2): Settings > Commentary > "Natural wording (on-device AI)" in every state,
 * with its buttons' 48 dp targets and the switch as one toggle. The states come from the pure `rephraseRowView`
 * (host-tested); here the words, the roles and the taps are checked on a device.
 */
@RunWith(AndroidJUnit4::class)
class RephraseSettingsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = TestApp.app
    private val size = 1_117_320_736L
    private val taps = ArrayList<String>()

    private fun show(view: RephraseRowView, cacheBytes: Long = 0, precheck: SetupPrecheck? = null) {
        compose.setContent {
            ChessAnalyzerTheme {
                RephraseSettingsContent(
                    view = view, sizeBytes = size, cacheBytes = cacheBytes, precheckError = precheck,
                    onToggle = { taps += "toggle:$it" }, onDownload = { taps += "download" }, onPause = { taps += "pause" },
                    onCancel = { taps += "cancel" }, onClearCache = { taps += "clear" }, onRemove = { taps += "remove" },
                )
            }
        }
    }

    private fun s(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test
    fun notInstalledExplainsTheFeatureAndOffersTheDownloadWithItsSize() {
        show(RephraseRowView(RephraseRow.NOT_INSTALLED, false, 0, size))
        compose.onNodeWithText(s(R.string.settings_commentary_header)).assertExists()
        compose.onNodeWithText(s(R.string.settings_rephrase_title)).assertExists()
        compose.onNodeWithText(s(R.string.settings_rephrase_not_installed)).assertExists()
        val download = s(R.string.settings_rephrase_download, gigabytesLabel(size))
        compose.onNodeWithText(download).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("download"), taps)
    }

    @Test
    fun aDownloadInProgressShowsItsBytesWithPauseAndCancel() {
        show(RephraseRowView(RephraseRow.DOWNLOADING, false, 300_000_000, size))
        compose.onNodeWithText(s(R.string.settings_rephrase_downloading, "‎300 MB‎", "‎1117 MB‎")).assertExists()
        compose.onNodeWithText(s(R.string.setup_pause)).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(s(R.string.setup_cancel)).performClick()
        assertEquals(listOf("pause", "cancel"), taps)
    }

    @Test
    fun pausedOffersResumeAndAPrecheckErrorReplacesTheLine() {
        show(RephraseRowView(RephraseRow.PAUSED, false, 558_660_368, size), precheck = SetupPrecheck.NO_NETWORK)
        compose.onNodeWithText(s(R.string.settings_rephrase_no_network)).assertExists()
        compose.onNodeWithText(s(R.string.setup_resume)).performClick()
        assertEquals(listOf("download"), taps)
    }

    @Test
    fun installedIsOneSwitchWithItsExplanationTheCacheLineAndRemove() {
        show(RephraseRowView(RephraseRow.INSTALLED, true, size, size), cacheBytes = 4096)
        val switch = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
        switch.assertIsOn().assertHeightIsAtLeast(48.dp)
        switch.performClick()
        assertTrue(compose.onAllNodesWithText(s(R.string.settings_rephrase_installed, gigabytesLabel(size))).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(s(R.string.settings_rephrase_cache_clear)).performClick()
        compose.onNodeWithText(s(R.string.settings_rephrase_remove)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("toggle:false", "clear", "remove"), taps)
    }

    @Test
    fun theSwitchIsOffWhenTheSettingIsOff() {
        show(RephraseRowView(RephraseRow.INSTALLED, false, size, size))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch)).assertIsOff()
    }

    @Test
    fun aPhoneThatCannotRunItSaysSoAndOffersNothing() {
        show(RephraseRowView(RephraseRow.UNAVAILABLE, false, 0, size))
        compose.onNodeWithText(s(R.string.settings_rephrase_unavailable)).assertExists()
        assertTrue(compose.onAllNodes(hasText(s(R.string.settings_rephrase_download, gigabytesLabel(size)))).fetchSemanticsNodes().isEmpty())
    }
}
