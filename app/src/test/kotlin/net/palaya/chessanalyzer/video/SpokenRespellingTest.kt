package net.palaya.chessanalyzer.video

import java.io.File
import net.palaya.chessanalyzer.core.narration.NarrationCatalogue
import net.palaya.chessanalyzer.core.narration.NarrationLocales
import net.palaya.chessanalyzer.core.narration.NarrationStyle
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1-device: the spoken-only respellings for the Kokoro voice. The evidence for each entry is the espeak-ng
 * phonemizer inside sherpa-onnx 1.13.8 (the library the app ships), recorded in RUN_LOG "C1-device":
 *
 *  - "zwischenzug" -> `zwˈɪʃənzˌʌɡ` (ZWISH-un-zug, wrong); "zwishentsuuk" -> `zwˈɪʃəntsˌuːk` (right)
 *  - "en prise"    -> `ˈɛn pɹˈaɪz`  (en PRIZE, wrong);      "on preez"     -> `ˌɔn pɹˈiːz`  (right)
 *
 * The terms the owner asked about that are read well stay as they are (skewer `skjˈuːɚ`, decisively
 * `dᵻsˈaɪsɪvli`, exchange `ɛkstʃˈeɪndʒ`, desperado `dᵻspɚɹˈɑːdoʊ`), pinned here as unchanged.
 */
class SpokenRespellingTest {

    @Test
    fun zwischenzugIsRespelledForTheVoice() {
        assertEquals("A zwishentsuuk, an in-between move.", SpokenRespelling.apply("A zwischenzug, an in-between move."))
    }

    @Test
    fun zwischenzugKeepsItsCapitalAndItsPlural() {
        assertEquals("Zwishentsuuk first.", SpokenRespelling.apply("Zwischenzug first."))
        assertEquals("Two zwishentsuuks in a row.", SpokenRespelling.apply("Two zwischenzugs in a row."))
        assertEquals("ZWISHENTSUUK", SpokenRespelling.apply("ZWISCHENZUG").uppercase())
    }

    @Test
    fun enPriseIsRespelledForTheVoice() {
        assertEquals(
            "The queen on c six is on preez: nothing defends it.",
            SpokenRespelling.apply("The queen on c six is en prise: nothing defends it."),
        )
        assertEquals("It was on preez, with nothing defending it.", SpokenRespelling.apply("It was en prise, with nothing defending it."))
        assertEquals("On preez is the word.", SpokenRespelling.apply("En prise is the word."))
    }

    @Test
    fun onlyTheWholeTermIsTouched() {
        // "en" and "prise" alone, and words that merely contain them, are not the term.
        assertEquals("Then the surprise came.", SpokenRespelling.apply("Then the surprise came."))
        assertEquals("a pen prised open", SpokenRespelling.apply("a pen prised open"))
        assertEquals("en route to prise", SpokenRespelling.apply("en route to prise"))
        assertEquals("zwischenzugzwang", SpokenRespelling.apply("zwischenzugzwang"))
    }

    @Test
    fun theTermsThatAreReadWellAreLeftAlone() {
        for (s in listOf(
            "That's a skewer: the piece on d five is attacked.",
            "A desperado: the piece is lost anyway.",
            "Black has gone from clearly worse to decisively lost on one move.",
            "White comes out of it the exchange up.",
            "An overloaded defender: one piece holding two things.",
        )) {
            assertEquals(s, SpokenRespelling.apply(s))
        }
    }

    @Test
    fun theTextWithoutATermIsReturnedAsItIs() {
        val s = "White plays knight to f three. Pause the video. Can you find it?"
        assertEquals(s, SpokenRespelling.apply(s))
        assertEquals("", SpokenRespelling.apply(""))
    }

    @Test
    fun theRespellingIsDeterministic() {
        val s = "A zwischenzug first, then the queen is en prise."
        assertEquals(SpokenRespelling.apply(s), SpokenRespelling.apply(s))
        assertEquals(SpokenRespelling.apply(s), SpokenRespelling.apply(SpokenRespelling.apply(s)))
    }

    @Test
    fun everyNarrationSentenceThatSaysATermIsRespelled() {
        // The whole English catalogue, both styles: after the respelling no sentence still holds a term the
        // voice reads wrongly, and every sentence that held one did change.
        var seenZwischenzug = 0
        var seenEnPrise = 0
        for (sample in NarrationCatalogue.samples()) {
            for (style in NarrationStyle.entries) {
                for (text in NarrationLocales.default.render(sample, style)) {
                    val spoken = SpokenRespelling.apply(text)
                    assertFalse(text, spoken.contains("zwischenzug", ignoreCase = true))
                    assertFalse(text, Regex("\ben prise\b", RegexOption.IGNORE_CASE).containsMatchIn(spoken))
                    if (text.contains("zwischenzug", ignoreCase = true)) {
                        seenZwischenzug++
                        assertNotEquals(text, spoken)
                    }
                    if (text.contains("en prise", ignoreCase = true)) {
                        seenEnPrise++
                        assertNotEquals(text, spoken)
                    }
                }
            }
        }
        assertTrue("the catalogue has a zwischenzug sentence", seenZwischenzug > 0)
        assertTrue("the catalogue has an en prise sentence", seenEnPrise > 0)
    }

    @Test
    fun theCacheKeyStaysTheRealSentenceAndCarriesTheTableId() {
        val id = SpokenRespelling.tableId
        assertTrue(id, Regex("[0-9a-f]{6}").matches(id))
        assertEquals(id, SpokenRespelling.tableId)
        val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, File("unused"), voiceVersionId = "7190c4801645")
        assertTrue(provider.cacheFingerprint.endsWith("/pr$id"))
        val store = NarrationStore.forDirectory(File(System.getProperty("java.io.tmpdir"), "narration-respell-test"))
        try {
            val real = "The queen on c six is en prise: nothing defends it."
            val key = store.keyFor(real, provider.displayName, provider.narrationCacheFingerprint())
            assertEquals(key, store.keyFor(real, provider.displayName, provider.narrationCacheFingerprint()))
            // The key is the real sentence's, not the respelled one's: the respelled text is only the engine's input.
            assertNotEquals(
                key,
                store.keyFor(SpokenRespelling.apply(real), provider.displayName, provider.narrationCacheFingerprint()),
            )
        } finally {
            store.dir.deleteRecursively()
        }
    }
}
