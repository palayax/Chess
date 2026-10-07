package net.palaya.chessanalyzer.data

import android.content.Context
import net.palaya.chessanalyzer.ui.model.FamousGameAssets
import net.palaya.chessanalyzer.ui.model.FamousGamesLibrary

/**
 * Reads the built-in famous-games library (G1) from the APK's assets once per process: the index and the PGN
 * (about 100 games, well under 100 KB). Nothing is downloaded. Call off the main thread.
 */
object FamousGamesStore {
    @Volatile private var cached: FamousGamesLibrary? = null

    fun load(context: Context): FamousGamesLibrary = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }

    private fun read(context: Context): FamousGamesLibrary {
        val assets = context.applicationContext.assets
        val index = assets.open(FamousGameAssets.INDEX).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val pgn = assets.open(FamousGameAssets.PGN).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return FamousGamesLibrary.from(index, pgn)
    }
}
