package net.palaya.chessanalyzer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.ui.screens.AboutScreen
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google Play wants the privacy policy linked inside the app. The About screen shows a "Privacy policy"
 * row; a tap hands this exact URL to the platform's [UriHandler] (the browser opens it, the app makes
 * no request itself). A recording handler stands in for the browser. No `assumeTrue`.
 */
@RunWith(AndroidJUnit4::class)
class AboutPrivacyLinkTest {

    @get:Rule
    val compose = createComposeRule()

    private class RecordingUriHandler : UriHandler {
        val opened = ArrayList<String>()
        override fun openUri(uri: String) { opened += uri }
    }

    @Test
    fun aboutShowsAPrivacyPolicyLinkThatOpensTheExactUrl() {
        val handler = RecordingUriHandler()
        compose.setContent {
            ChessAnalyzerTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) { AboutScreen() }
            }
        }
        val link = compose.onNodeWithText("Privacy policy")
        link.assertExists()
        link.assertHasClickAction()
        link.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        link.assertHeightIsAtLeast(48.dp)

        link.performClick()
        assertEquals(listOf("https://palayax.github.io/Chess/privacy/"), handler.opened)
    }
}
