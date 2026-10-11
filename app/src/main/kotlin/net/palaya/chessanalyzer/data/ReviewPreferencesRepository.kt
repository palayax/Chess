package net.palaya.chessanalyzer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reviewPreferencesDataStore by preferencesDataStore(
    name = "chess_analyzer_review_prefs",
    corruptionHandler = resetOnCorruption("chess_analyzer_review_prefs"),
)

/**
 * The two Settings switches of the review flow (RF), defaults for new games like the rest of Settings:
 *  - "Show this screen before each review" (default on): off, an import goes straight to processing with the defaults
 *    (the library menu and the Summary still open the Review options screen);
 *  - "Prepare the video with each review" (default on): the default of the Review options screen's switch.
 *
 * Its own DataStore so the review flow does not share a file with the engine settings.
 */
class ReviewPreferencesRepository(private val context: Context) {

    data class Prefs(val showBeforeEachReview: Boolean = true, val prepareVideoByDefault: Boolean = true)

    private object Keys {
        val SHOW_BEFORE_EACH_REVIEW = booleanPreferencesKey("show_review_options")
        val PREPARE_VIDEO = booleanPreferencesKey("prepare_video_by_default")
    }

    val prefsFlow: Flow<Prefs> = context.reviewPreferencesDataStore.data.map { p ->
        Prefs(
            showBeforeEachReview = p[Keys.SHOW_BEFORE_EACH_REVIEW] ?: true,
            prepareVideoByDefault = p[Keys.PREPARE_VIDEO] ?: true,
        )
    }

    suspend fun current(): Prefs = prefsFlow.first()

    suspend fun setShowBeforeEachReview(show: Boolean) {
        context.reviewPreferencesDataStore.edit { it[Keys.SHOW_BEFORE_EACH_REVIEW] = show }
    }

    suspend fun setPrepareVideoByDefault(prepare: Boolean) {
        context.reviewPreferencesDataStore.edit { it[Keys.PREPARE_VIDEO] = prepare }
    }
}
