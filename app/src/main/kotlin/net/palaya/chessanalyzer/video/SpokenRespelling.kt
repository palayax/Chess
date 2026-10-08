package net.palaya.chessanalyzer.video

import java.security.MessageDigest

/**
 * Spoken-only respellings for the neural narration voice (C1-device).
 *
 * The Kokoro voice phonemizes English with espeak-ng's own rules, which know no German or French. Two of the
 * professional terms C1 added to the narration are read wrongly (measured with the phonemizer inside
 * sherpa-onnx 1.13.8, the same library the app ships, RUN_LOG "C1-device"):
 *
 *  - "zwischenzug" comes out `zwˈɪʃənzˌʌɡ`, "ZWISH-un-zug" with a short *u* as in "bug", where the term is
 *    "TSVISH-en-tsook" (German /tsvɪʃn̩tsuːk/);
 *  - "en prise" comes out `ˈɛn pɹˈaɪz`, "en PRIZE", where the term is French "on PREEZ" (/ɑ̃ pʁiz/).
 *
 * The text the viewer sees (cards, walkthrough, captions) keeps the real term. The respelling is applied to the
 * text handed to the voice engine and nowhere else: [NeuralTtsProvider.synthesize] calls [apply] on each
 * sentence just before the engine. The narration cache is still keyed by the real sentence, plus [tableId] in
 * the provider's fingerprint, so the key is a pure function of the text and of this table, and changing an entry
 * can never serve audio made from the old spelling.
 *
 * Only the neural voice uses it: the device voice (the fallback, [DeviceTtsProvider]) is another engine with its
 * own rules and is not measured here, so it keeps the real term.
 *
 * Add an entry only with evidence: the espeak-ng phonemes of the real term and of the respelling, each pinned in
 * [SpokenRespellingTest].
 */
internal object SpokenRespelling {

    /** One term: a whole-word, case-insensitive [pattern], and what to say instead. The first letter keeps its case. */
    private class Entry(val pattern: Regex, val spoken: String, val pluralSuffix: Boolean)

    private val entries: List<Entry> = listOf(
        // espeak-ng: zwishentsuuk -> zwˈɪʃəntsˌuːk (ZWISH-un-TSOOK), the ending and the ts of the German term.
        Entry(Regex("\\bzwischenzug(s?)\\b", RegexOption.IGNORE_CASE), "zwishentsuuk", pluralSuffix = true),
        // espeak-ng: "on preez" -> ˌɔn pɹˈiːz (on PREEZ), the French nasal vowel and the long ee.
        Entry(Regex("\\ben prise\\b", RegexOption.IGNORE_CASE), "on preez", pluralSuffix = false),
    )

    /** [text] with every listed term respelled for the voice; unchanged when it holds none. */
    fun apply(text: String): String {
        var out = text
        for (e in entries) {
            out = e.pattern.replace(out) { m ->
                val first = m.value.first()
                val spoken = if (first.isUpperCase()) e.spoken.replaceFirstChar { it.uppercaseChar() } else e.spoken
                if (e.pluralSuffix) spoken + m.groupValues[1].lowercase() else spoken
            }
        }
        return out
    }

    /**
     * A short, stable id of the whole table (first 6 hex of the SHA-256 of its entries). It goes into the
     * provider's cache fingerprint, so any edit to the table moves every key that could have used it.
     */
    val tableId: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        for (e in entries) {
            digest.update(e.pattern.pattern.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(e.spoken.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it) }.take(6)
    }
}
