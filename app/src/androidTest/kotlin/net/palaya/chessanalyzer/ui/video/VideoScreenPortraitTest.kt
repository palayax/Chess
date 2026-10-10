package net.palaya.chessanalyzer.ui.video

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.screens.PORTRAIT_FRAME_ASPECT
import net.palaya.chessanalyzer.ui.screens.VideoScreen
import net.palaya.chessanalyzer.ui.screens.VideoSection
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.video.TestScripts
import net.palaya.chessanalyzer.video.VoiceSamplePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V4 on the device: "Watch the video review" opens the player in portrait the way "Show me" opens the
 * Walkthrough (a bar with the back arrow and the title, the picture as wide as the screen under it, the controls
 * under the picture, no overlay, the system bars as they are), and the player's gear opens the voice and the pace
 * (the Settings screen's own Video section) in a sheet.
 */
@RunWith(AndroidJUnit4::class)
class VideoScreenPortraitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    @Test
    fun thePictureIsAsWideAsTheScreenAndTallerThanWideInPortrait() {
        val script = TestScripts.shortScript()
        var backs = 0
        compose.setContent {
            ChessAnalyzerTheme {
                VideoScreen(script = script, onBack = { backs++ }, voiceAndPaceSettings = { Text("settings") })
            }
        }
        compose.waitForIdle()
        val frame = compose.onNodeWithContentDescription(str(R.string.cd_video_frame, script.title)).assertIsDisplayed()
        val bounds = frame.fetchSemanticsNode().boundsInRoot
        val rootWidth = compose.activity.window.decorView.width.toFloat()
        assertTrue("portrait test device", compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
        // As wide as the screen (the Walkthrough's board takes the width too) and 3:4, so the board in it is that wide.
        assertTrue("frame ${bounds.width} of $rootWidth px", bounds.width >= rootWidth - 2f)
        assertEquals(PORTRAIT_FRAME_ASPECT, bounds.width / bounds.height, 0.01f)
        // The bar: the title and the back arrow, which leaves the way "Show me"'s arrow does.
        compose.onNodeWithText(str(R.string.video_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.common_back)).performClick()
        assertEquals(1, backs)
        // The controls sit under the picture, always shown: play is there without tapping the picture first.
        compose.onNodeWithContentDescription(str(R.string.video_play)).assertIsDisplayed()
        val play = compose.onNodeWithContentDescription(str(R.string.video_play)).fetchSemanticsNode().boundsInRoot
        assertTrue("controls under the picture", play.top >= bounds.bottom - 1f)
    }

    @Test
    fun theGearOpensVoiceAndPaceWithTheSettingsScreensOwnControls() {
        val script = TestScripts.shortScript()
        val chosen = mutableListOf<EngineSettings>()
        compose.setContent {
            ChessAnalyzerTheme {
                var settings by remember { mutableStateOf(EngineSettings(videoPace = VideoPace.RELAXED)) }
                VideoScreen(
                    script = script,
                    voiceAndPaceSettings = {
                        VideoSection(
                            settings = settings,
                            onSettingsChange = { settings = it; chosen.add(it) },
                            narrationVoiceSettings = NarrationVoiceSettings(),
                            voiceInstalled = true,
                            onNarratorSpeakerChange = {},
                            voiceSampleState = VoiceSamplePlayer.State.Idle,
                            onPlayVoiceSample = {},
                            onStopVoiceSample = {},
                            onVoicePickerClosed = {},
                            heading = str(R.string.video_voice_and_pace),
                        )
                    },
                )
            }
        }
        compose.onNodeWithContentDescription(str(R.string.video_voice_and_pace)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        // The sheet: the heading, the narrator voice row and the pace control, as in Settings.
        compose.onAllNodesWithText(str(R.string.video_voice_and_pace)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.settings_voice_row)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.settings_pace)).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.settings_pace_brisk)).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(VideoPace.BRISK, chosen.last().videoPace)
        // The voice row opens the same picker as Settings, with every voice.
        compose.onAllNodesWithText(str(R.string.settings_voice_row)).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(str(R.string.voice_picker_help)).onFirst().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(str(R.string.voice_sample_play)).fetchSemanticsNodes().size >= 2)
        // The picker is a dialog over the sheet: its Done is the last one in the tree.
        compose.onAllNodesWithText(str(R.string.voice_picker_done)).onLast().performClick()
        compose.waitForIdle()
        // Done closes the sheet.
        compose.onAllNodesWithText(str(R.string.common_done)).onFirst().performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(str(R.string.settings_pace)).fetchSemanticsNodes().isEmpty())
    }
}
