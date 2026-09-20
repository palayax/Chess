package net.palaya.chessanalyzer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.engine.NetworkProvider
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.AppLanguage

private val Context.dataStore by preferencesDataStore(name = "chess_analyzer_settings")

/**
 * Persists engine/user preferences (search depth, MultiPV, username, board orientation) via
 * DataStore Preferences — [SettingsScreen][net.palaya.chessanalyzer.ui.screens.SettingsScreen]
 * reads/writes real values through this instead of holding them only in Compose state.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEPTH = intPreferencesKey("depth")
        val TIME_PER_MOVE_MS = intPreferencesKey("time_per_move_ms")
        val MULTI_PV = intPreferencesKey("multi_pv")
        val USERNAME = stringPreferencesKey("username")
        val BOARD_ORIENTATION_WHITE_DOWN = booleanPreferencesKey("board_orientation_white_down")
        val NET_VERSION = stringPreferencesKey("net_version")
        val NARRATION_THRESHOLD_CP = intPreferencesKey("narration_threshold_cp")
        /** BCP-47 tag of the chosen [AppLanguage]; absent or empty means "follow the system". */
        val LANGUAGE = stringPreferencesKey("app_language")
    }

    /** Live stream of settings, backed by real persisted values (falls back to defaults). */
    val settingsFlow: Flow<EngineSettings> = context.dataStore.data.map { prefs ->
        EngineSettings(
            depth = prefs[Keys.DEPTH] ?: DEFAULT_DEPTH,
            timePerMoveMs = prefs[Keys.TIME_PER_MOVE_MS] ?: 500,
            multiPv = prefs[Keys.MULTI_PV] ?: DEFAULT_MULTI_PV,
            username = prefs[Keys.USERNAME] ?: "",
            engineVersion = EngineInfo.VERSION_LABEL,
            netVersion = prefs[Keys.NET_VERSION] ?: NetworkProvider.NET_FILENAME,
            narrationThresholdCp = prefs[Keys.NARRATION_THRESHOLD_CP] ?: DEFAULT_NARRATION_THRESHOLD_CP,
            language = AppLanguage.fromTag(prefs[Keys.LANGUAGE]),
        )
    }

    suspend fun current(): EngineSettings = settingsFlow.first()

    val boardOrientationWhiteDownFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BOARD_ORIENTATION_WHITE_DOWN] ?: true
    }

    suspend fun setDepth(depth: Int) {
        context.dataStore.edit { it[Keys.DEPTH] = depth.coerceIn(6, 30) }
    }

    suspend fun setTimePerMoveMs(ms: Int) {
        context.dataStore.edit { it[Keys.TIME_PER_MOVE_MS] = ms.coerceIn(100, 3000) }
    }

    suspend fun setMultiPv(multiPv: Int) {
        context.dataStore.edit { it[Keys.MULTI_PV] = multiPv.coerceIn(1, 5) }
    }

    suspend fun setUsername(username: String) {
        context.dataStore.edit { it[Keys.USERNAME] = username }
    }

    suspend fun setBoardOrientationWhiteDown(whiteDown: Boolean) {
        context.dataStore.edit { it[Keys.BOARD_ORIENTATION_WHITE_DOWN] = whiteDown }
    }

    /**
     * ANALYSIS_SPEC §9.2. Clamped to 0..300cp (0 to 3 pawns): 0 means "narrate every candidate
     * move", and above three pawns the filter would only ever keep outright blunders, at which
     * point `NarrationDepth.MISTAKES_ONLY` is the better control.
     */
    suspend fun setNarrationThresholdCp(cp: Int) {
        context.dataStore.edit { it[Keys.NARRATION_THRESHOLD_CP] = cp.coerceIn(0, 300) }
    }

    suspend fun setNetVersion(version: String) {
        context.dataStore.edit { it[Keys.NET_VERSION] = version }
    }

    /** Persists a whole [EngineSettings] snapshot in one go (used by the settings screen callback). */
    suspend fun save(settings: EngineSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEPTH] = settings.depth.coerceIn(6, 30)
            prefs[Keys.TIME_PER_MOVE_MS] = settings.timePerMoveMs.coerceIn(100, 3000)
            prefs[Keys.MULTI_PV] = settings.multiPv.coerceIn(1, 5)
            prefs[Keys.USERNAME] = settings.username
            prefs[Keys.NARRATION_THRESHOLD_CP] = settings.narrationThresholdCp.coerceIn(0, 300)
            prefs[Keys.LANGUAGE] = settings.language.tag
        }
    }

    companion object {
        /** "Standard" preset per docs/ANALYSIS_SPEC.md §8 (12 fast / 18 standard / 24 deep). */
        const val DEFAULT_DEPTH = 14
        const val DEFAULT_MULTI_PV = 3

        /** Half a pawn — ANALYSIS_SPEC §9.2, mirroring `NarrationOptions`' own default. */
        const val DEFAULT_NARRATION_THRESHOLD_CP = NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP
    }
}

/** Static engine identity surfaced in Settings — read from the vendored Stockfish version file. */
object EngineInfo {
    /** Set once at process start by [net.palaya.chessanalyzer.ChessAnalyzerApplication]. */
    @Volatile
    var VERSION_LABEL: String = "Stockfish (unknown version)"
}
