package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The i18n contract itself: every sentence id is coverable, English speaks no notation, the
 * locale registry resolves Android's legacy Hebrew code, and — the property the whole design
 * exists for — a locale can restructure a sentence entirely and is handed the viewer's gender.
 */
class NarrationStringsTest {

    private val notation = Regex("\\b[KQRBN][a-h]?[1-8]?x?[a-h][1-8]\\b|\\b[a-h]x[a-h][1-8]\\b|\\bO-O(-O)?\\b")
    private val bareSquare = Regex("\\b[a-h][1-8]\\b")

    @Test
    fun `english renders every catalogued sentence in both styles without notation`() {
        val ids = HashSet<String>()
        for (sentence in NarrationCatalogue.samples()) {
            ids += sentence::class.simpleName!!
            for (style in NarrationStyle.entries) {
                val variants = EnglishNarration.render(sentence, style)
                assertTrue("$sentence has no variants", variants.isNotEmpty())
                for (v in variants) {
                    if (sentence == Sentence.Filler && v.isEmpty()) continue
                    assertTrue("$sentence rendered blank", v.isNotBlank())
                    if (sentence.isSpoken()) {
                        assertFalse("$sentence speaks notation: $v", notation.containsMatchIn(v))
                        assertFalse("$sentence speaks a bare square: $v", bareSquare.containsMatchIn(v))
                    }
                }
            }
        }
        // The catalogue is the translator's checklist, so it must not quietly fall behind the
        // contract: every Sentence subclass the generator can emit has to be represented.
        val declared = Sentence::class.java.declaredClasses.map { it.simpleName }.filter { it != "DefaultImpls" }.toSet()
        val missing = declared - ids
        assertTrue("catalogue is missing samples for: $missing", missing.isEmpty())
    }

    @Test
    fun `the catalogue markdown lists every sentence`() {
        val md = NarrationCatalogue.markdown(EnglishNarration)
        for (sentence in NarrationCatalogue.samples()) {
            assertTrue(md.contains("`${sentence::class.simpleName}`"))
        }
        // Left behind for docs/NARRATION_STRINGS.md; a test writing into docs/ would be a side
        // effect, so it lands in the build directory and is copied by hand.
        File("build/narration_catalogue.md").apply { parentFile.mkdirs() }.writeText(md)
    }

    @Test
    fun `locale registry resolves language subtags and Android's legacy Hebrew code`() {
        assertSame(EnglishNarration, NarrationLocales.forTag("en"))
        assertSame(EnglishNarration, NarrationLocales.forTag("en-GB"))
        assertSame(EnglishNarration, NarrationLocales.forTag("en_US"))
        assertSame(EnglishNarration, NarrationLocales.forTag(null))
        // No Hebrew implementation yet: both spellings fall back to the default rather than
        // to nothing, and both will resolve to the same implementation once one is registered.
        assertSame(NarrationLocales.forTag("he-IL"), NarrationLocales.forTag("iw-IL"))
        assertSame(NarrationLocales.default, NarrationLocales.forTag("iw"))
    }

    /**
     * A stand-in for Hebrew that only needs to prove two things: the played move is spoken
     * as one whole sentence the locale can order however it likes (here: move first, subject
     * last), and the viewer's gender reaches the locale so the verb can inflect on it.
     */
    private object RestructuringLocale : NarrationStrings {
        override val languageTag = "xx"
        override val vocabulary: SpokenVocabulary = EnglishNarration.Vocabulary
        override fun render(sentence: Sentence, style: NarrationStyle): List<String> = when (sentence) {
            is Sentence.Played -> {
                val verb = when (sentence.subject.gender) {
                    Gender.FEMININE -> "played-f"
                    Gender.MASCULINE -> "played-m"
                    Gender.UNSPECIFIED -> "played"
                }
                val who = if (sentence.subject.person == Person.SECOND) "you" else vocabulary.side(sentence.subject.color)
                listOf("[${vocabulary.movePhrase(sentence.move)} $verb $who]")
            }
            else -> EnglishNarration.render(sentence, style)
        }
    }

    @Test
    fun `a locale restructures the whole sentence and receives the viewer's gender`() {
        val report = NarrationFixture.report(userColor = Color.WHITE)
        val game = NarrationFixture.game()
        val script = VideoScriptGenerator(Color.WHITE, RestructuringLocale).generate(
            report, game, NarrationOptions(depth = NarrationDepth.EVERY_MOVE, viewerGender = Gender.FEMININE)
        )
        val text = script.segments.joinToString(" ") { it.narration }
        assertTrue("the viewer's moves should be restructured with a feminine verb:\n$text", text.contains("played-f you]"))
        assertTrue("the opponent should stay ungendered:\n$text", text.contains("played Black]"))
        // Only Played is restructured by this locale (ExcursionMove is a separate sentence and
        // stays English here), so the check is that no *ordinary move beat* kept the English.
        val moveBeats = script.segments.filter { it.kind == SegmentKind.NORMAL_MOVE && it.ply != null }
        assertTrue(moveBeats.isNotEmpty())
        for (seg in moveBeats) {
            assertFalse("English 'you play' survived in ${seg.index}: ${seg.narration}", Regex("\\byou play\\b").containsMatchIn(seg.narration))
        }
    }

    @Test
    fun `english output is unchanged in shape by the contract`() {
        val report = NarrationFixture.report(userColor = Color.WHITE)
        val game = NarrationFixture.game()
        val script = VideoScriptGenerator(Color.WHITE, EnglishNarration).generate(report, game, NarrationOptions())
        val intro = script.segments.first { it.kind == SegmentKind.INTRO }.narration
        assertTrue(intro, intro.contains("has the white pieces"))
        assertEquals("Intro", script.chapters.first().title)
    }

    private fun Sentence.isSpoken(): Boolean = when (this) {
        is Sentence.VideoTitle, is Sentence.VideoSubtitle, is Sentence.ChapterMove,
        is Sentence.CaptionMove, is Sentence.CaptionMissed, is Sentence.CaptionMissedLine,
        is Sentence.CaptionBackToGame, is Sentence.CaptionTurningPoint -> false
        else -> true
    }
}
