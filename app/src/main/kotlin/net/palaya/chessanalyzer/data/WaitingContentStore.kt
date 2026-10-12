package net.palaya.chessanalyzer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.ui.model.WaitingAssets
import net.palaya.chessanalyzer.ui.model.WaitingContent
import net.palaya.chessanalyzer.ui.model.parseWaitingContent
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the "While you wait" content (docs/WAITING_CONTENT.md) from the APK's assets once per process: the three
 * JSON files under `assets/waiting/` (about 60 KB together) and the famous-games library the app already has
 * ([FamousGamesStore]), from which the game cards are built. Nothing is downloaded and nothing is written.
 *
 * [load] runs on `Dispatchers.IO`, so it can be called from any coroutine, e.g. `produceState`. A failure (a broken
 * asset would already have failed the host tests) is logged and gives [WaitingContent.EMPTY], so the panel still shows
 * the live insights instead of the analysis screen failing; a failure is not cached, the next call tries again.
 */
object WaitingContentStore {
    private const val TAG = "WaitingContentStore"

    @Volatile private var cached: WaitingContent? = null
    @Volatile private var cachedBook: OpeningBook? = null

    suspend fun load(context: Context): WaitingContent = cached ?: withContext(Dispatchers.IO) { loadBlocking(context) }

    /** The same, on the calling thread. Call off the main thread. */
    fun loadBlocking(context: Context): WaitingContent = cached ?: synchronized(this) {
        cached ?: try {
            read(context).also { cached = it }
        } catch (e: Exception) {
            Log.w(TAG, "The waiting content could not be read", e)
            WaitingContent.EMPTY
        }
    }

    private fun read(context: Context): WaitingContent {
        val app = context.applicationContext
        fun text(name: String) = app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseWaitingContent(
            factsJson = text(WaitingAssets.FACTS),
            playersJson = text(WaitingAssets.PLAYERS),
            puzzlesJson = text(WaitingAssets.PUZZLES),
            library = FamousGamesStore.load(app),
        )
    }

    /**
     * The opening book for the "Opening: ..." insight (null if it cannot be read), cached per process. It is the same
     * `openings.tsv` the analysis reads; a screen that already holds an [OpeningBook] should pass that one instead.
     */
    suspend fun openingBook(context: Context): OpeningBook? = cachedBook ?: withContext(Dispatchers.IO) {
        cachedBook ?: try {
            val reader = BufferedReader(InputStreamReader(context.applicationContext.assets.open("openings.tsv"), Charsets.UTF_8))
            OpeningBook.load(reader).also { cachedBook = it }
        } catch (e: Exception) {
            Log.w(TAG, "The opening book could not be read", e)
            null
        }
    }
}
