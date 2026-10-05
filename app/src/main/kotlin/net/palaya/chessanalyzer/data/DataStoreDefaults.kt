package net.palaya.chessanalyzer.data

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences

/**
 * What every DataStore in the app does when its file cannot be parsed: throw the file away and
 * carry on with defaults.
 *
 * Without a handler a corrupt preferences file makes the first `data` read throw
 * `CorruptionException`, and since both settings flows are collected at launch
 * (`AnalysisViewModel`) that is a crash on every start, permanently, until the user clears app
 * data. Everything stored here is a preference with a sensible default (depth, threshold, username,
 * language, voice choice), so losing it is a small annoyance and crashing is not.
 *
 * Use this for **every** `preferencesDataStore(...)` the app declares.
 */
internal fun resetOnCorruption(storeName: String = "datastore"): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { error ->
        // Log is not available in plain JVM unit tests; never let the diagnostics break recovery.
        runCatching { Log.w("DataStore", "$storeName was corrupt; resetting to defaults", error) }
        emptyPreferences()
    }
