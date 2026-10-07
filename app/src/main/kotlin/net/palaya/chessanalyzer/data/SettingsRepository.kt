package net.palaya.chessanalyzer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.AppLanguage

private val Context.dataStore by preferencesDataStore(
    name = "chess_analyzer_settings",
    corruptionHandler = resetOnCorruption("chess_analyzer_settings"),
)

/**
 * Persists the user's preferences (search depth, username, narration threshold, language) via
 * DataStore Preferences — [SettingsScreen][net.palaya.chessanalyzer.ui.screens.SettingsScreen]
 * reads/writes real values through this instead of holding them only in Compose state.
 *
 * Keys that older builds wrote and nothing reads any more — `time_per_move_ms` (never consumed by
 * the analysis), `multi_pv` (not a user decision: fixed at [DEFAULT_MULTI_PV]),
 * `board_orientation_white_down` and `net_version` — are deliberately left in the store untouched
 * and unread.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEPTH = intPreferencesKey("depth")
        val USERNAME = stringPreferencesKey("username")
        val NARRATION_THRESHOLD_CP = intPreferencesKey("narration_threshold_cp")
        /** BCP-47 tag of the chosen [AppLanguage]; absent or empty means "follow the system". */
        val LANGUAGE = stringPreferencesKey("app_language")
        /** The narrated review's [VideoPace] by name (V3); absent or unknown means [VideoPace.DEFAULT]. */
        val VIDEO_PACE = stringPreferencesKey("video_pace")
        /** When "Check for updates" last finished (D2e, design §1.8); absent = never. */
        val LAST_UPDATE_CHECK_MS = longPreferencesKey("models_last_update_check_ms")
    }

    /** When the user last checked for model updates, or null for never. */
    val lastUpdateCheckMs: Flow<Long?> = context.dataStore.data.map { it[Keys.LAST_UPDATE_CHECK_MS] }

    suspend fun setLastUpdateCheckMs(atMs: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK_MS] = atMs }
    }

    /** Live stream of settings, backed by real persisted values (falls back to defaults). */
    val settingsFlow: Flow<EngineSettings> = context.dataStore.data.map { prefs ->
        EngineSettings(
            depth = prefs[Keys.DEPTH] ?: DEFAULT_DEPTH,
            multiPv = DEFAULT_MULTI_PV,
            username = prefs[Keys.USERNAME] ?: "",
            narrationThresholdCp = prefs[Keys.NARRATION_THRESHOLD_CP] ?: DEFAULT_NARRATION_THRESHOLD_CP,
            language = AppLanguage.fromTag(prefs[Keys.LANGUAGE]),
            videoPace = VideoPace.fromPersistedOrNull(prefs[Keys.VIDEO_PACE]) ?: VideoPace.DEFAULT,
        )
    }

    suspend fun current(): EngineSettings = settingsFlow.first()

    suspend fun setDepth(depth: Int) {
        context.dataStore.edit { it[Keys.DEPTH] = depth.coerceIn(6, 30) }
    }

    suspend fun setUsername(username: String) {
        context.dataStore.edit { it[Keys.USERNAME] = username }
    }

    /**
     * ANALYSIS_SPEC §9.2. Clamped to 0..300cp (0 to 3 pawns): 0 means "narrate every candidate
     * move", and above three pawns the filter would only ever keep outright blunders, at which
     * point `NarrationDepth.MISTAKES_ONLY` is the better control.
     */
    suspend fun setNarrationThresholdCp(cp: Int) {
        context.dataStore.edit { it[Keys.NARRATION_THRESHOLD_CP] = cp.coerceIn(0, 300) }
    }

    /** Persists a whole [EngineSettings] snapshot in one go (used by the settings screen callback). */
    suspend fun save(settings: EngineSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEPTH] = settings.depth.coerceIn(6, 30)
            prefs[Keys.USERNAME] = settings.username
            prefs[Keys.NARRATION_THRESHOLD_CP] = settings.narrationThresholdCp.coerceIn(0, 300)
            prefs[Keys.LANGUAGE] = settings.language.tag
            prefs[Keys.VIDEO_PACE] = settings.videoPace.name
        }
    }

    /** V3: the narrated review's pace (Settings, Video). */
    suspend fun setVideoPace(pace: VideoPace) {
        context.dataStore.edit { it[Keys.VIDEO_PACE] = pace.name }
    }

    companion object {
        /** "Standard" preset per docs/ANALYSIS_SPEC.md §8 (12 fast / 18 standard / 24 deep). */
        const val DEFAULT_DEPTH = 14
        const val DEFAULT_MULTI_PV = 3

        /** Half a pawn — ANALYSIS_SPEC §9.2, mirroring `NarrationOptions`' own default. */
        const val DEFAULT_NARRATION_THRESHOLD_CP = NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP
    }
}
