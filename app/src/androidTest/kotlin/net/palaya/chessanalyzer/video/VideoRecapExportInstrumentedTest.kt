package net.palaya.chessanalyzer.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.RecapCount
import net.palaya.chessanalyzer.core.narration.RecapMoment
import net.palaya.chessanalyzer.core.narration.RecapSide
import net.palaya.chessanalyzer.core.narration.VideoRecap
import net.palaya.chessanalyzer.ui.theme.ClassBlunder
import net.palaya.chessanalyzer.ui.theme.ClassBrilliant
import net.palaya.chessanalyzer.ui.theme.ClassGreat
import net.palaya.chessanalyzer.ui.theme.ClassInaccuracy
import net.palaya.chessanalyzer.ui.theme.ClassMiss
import net.palaya.chessanalyzer.ui.theme.ClassMistake
import net.palaya.chessanalyzer.ui.theme.GreenPrimary
import net.palaya.chessanalyzer.ui.theme.SurfaceDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R6b: the recap end card, drawn and exported on a real device.
 *
 *  - [renderedCardNeverReachesTheMarginsOrTheCentreChannel]: the text-fit proof at 1280x720 with the
 *    shortest and the longest realistic names (Latin, Hebrew, mixed), by looking at pixels, not at the
 *    layout's own arithmetic: outside the content box and in the channel between the two columns
 *    every pixel must still be the background.
 *  - [theCardUsesTheVideosOwnPalette]: the colours on the card are the app's.
 *  - [theExportedMp4EndsWithTheSilentRecapCard]: the export is longer by exactly the card's duration,
 *    its last frames are the card, the frames before are not, and the audio is silent there while the
 *    narration before it is audible (a tone narrator, so the silence means something).
 */
@RunWith(AndroidJUnit4::class)
class VideoRecapExportInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val w = VideoExporter.VIDEO_WIDTH
    private val h = VideoExporter.VIDEO_HEIGHT
    private val background = SurfaceDark.toArgb()

    private fun side(
        color: Color, name: String, accuracy: Double, you: Boolean = false, counts: List<RecapCount> = emptyList(),
    ) = RecapSide(color, name, you, accuracy, counts)

    private val sixCounts = listOf(
        RecapCount(MoveClassification.BRILLIANT, 1), RecapCount(MoveClassification.GREAT, 12),
        RecapCount(MoveClassification.INACCURACY, 12), RecapCount(MoveClassification.MISTAKE, 7),
        RecapCount(MoveClassification.MISS, 3), RecapCount(MoveClassification.BLUNDER, 5),
    )

    private val summary = "Black was fine until move 11, then a blunder decided it."

    private fun recap(white: String, black: String, whiteYou: Boolean = true, counts: List<RecapCount> = sixCounts) = VideoRecap(
        white = side(Color.WHITE, white, 91.4, whiteYou, counts),
        black = side(Color.BLACK, black, 62.0, !whiteYou, counts.take(3)),
        summary = summary,
        biggestMoment = RecapMoment(11, Color.BLACK, "Nf6", MoveClassification.BLUNDER),
    )

    private fun render(recap: VideoRecap, withText: Boolean = true): Bitmap {
        val labels = BoardFrameRenderer.PanelLabels.from(context).recap
        var card = RecapCardContent.from(recap, labels)
        // Without the text the card is only its two columns: the heading, the sentence and the moment
        // line are centred across the channel between them, so they are left out of the channel check.
        if (!withText) card = card.copy(heading = "", summary = null, momentLine = null, momentClassification = null)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        BoardFrameRenderer.renderRecapFrame(Canvas(bitmap), w, h, card)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(context.getExternalFilesDir(null), "recap_frames").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels in the rectangle that are not the background colour. */
    private fun painted(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) for (x in left until right) if (bitmap.getPixel(x, y) != background) n++
        return n
    }

    // -----------------------------------------------------------------------
    // Fit
    // -----------------------------------------------------------------------

    private val names = listOf(
        "A" to "B",
        "Bo" to "Li",
        "MorphyFan1857" to "DukeAndCount",
        "ABCDEFGHIJKLMNOPQRSTUVWXY" to "SomeVeryLongUsername_1234567890_extra",
        "Magnus Carlsen (Norway) - World Champion" to "Hikaru Nakamura (USA) - Grandmaster, streamer",
        "דוד בן־גוריון" to "משה דיין",
        "ש".repeat(60) to "ת".repeat(45),
        "יוסי Cohen-לוי" to "Dr. עמית 1234567890 Levi-Strauss",
    )

    @Test
    fun renderedCardNeverReachesTheMarginsOrTheCentreChannel() {
        for ((i, pair) in names.withIndex()) {
            val (white, black) = pair
            for (withText in listOf(true, false)) {
                val bitmap = render(recap(white, black), withText)
                save(bitmap, "recap_fit_${i}_${if (withText) "full" else "names"}")
                val tag = "\"$white\" vs \"$black\" text=$withText"

                // Outer margin: 4 percent left and right (past the accent bar), 3 percent top and bottom.
                val mx = (w * 0.04f).toInt()
                val barEnd = (w * 0.015f).toInt() + 1
                val my = (h * 0.03f).toInt()
                assertEquals("$tag: top margin", 0, painted(bitmap, barEnd, 0, w, my))
                assertEquals("$tag: bottom margin", 0, painted(bitmap, barEnd, h - my, w, h))
                assertEquals("$tag: left margin", 0, painted(bitmap, barEnd, 0, mx, h))
                assertEquals("$tag: right margin", 0, painted(bitmap, w - mx, 0, w, h))

                if (!withText) {
                    // Without the sentence and the moment (which span both columns) the strip between
                    // the columns holds nothing: a long name stayed in its own column.
                    assertEquals("$tag: centre channel", 0, painted(bitmap, (w * 0.48f).toInt(), 0, (w * 0.52f).toInt(), h))
                }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun theShortestAndLongestNamesBothRenderSomethingReadable() {
        // A name is drawn (pixels in the name row of its column), however long or short it is.
        for ((white, black) in listOf(names.first(), names[4], names[6])) {
            val bitmap = render(recap(white, black), withText = true)
            val leftColumn = painted(bitmap, (w * 0.07f).toInt(), (h * 0.24f).toInt(), (w * 0.47f).toInt(), (h * 0.34f).toInt())
            val rightColumn = painted(bitmap, (w * 0.53f).toInt(), (h * 0.24f).toInt(), (w * 0.93f).toInt(), (h * 0.34f).toInt())
            assertTrue("\"$white\": left column empty", leftColumn > 100)
            assertTrue("\"$black\": right column empty", rightColumn > 100)
            bitmap.recycle()
        }
    }

    @Test
    fun aCleanGameDrawsNoChipsAndStillFits() {
        val clean = recap("MorphyFan1857", "DukeAndCount").let {
            // Both above 85 percent so the accuracy digits are the good green, not a class colour.
            it.copy(white = it.white.copy(counts = emptyList()), black = it.black.copy(counts = emptyList(), accuracy = 88.0), biggestMoment = null)
        }
        val bitmap = render(clean)
        save(bitmap, "recap_clean_game")
        for (c in listOf(ClassBrilliant, ClassGreat, ClassInaccuracy, ClassMistake, ClassMiss, ClassBlunder)) {
            assertEquals("no chip colour on a clean card", 0, countColour(bitmap, c.toArgb()))
        }
        bitmap.recycle()
    }

    // -----------------------------------------------------------------------
    // Palette
    // -----------------------------------------------------------------------


    private fun countColour(bitmap: Bitmap, argb: Int): Int {
        var n = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) == argb) n++
        return n
    }

    @Test
    fun theCardUsesTheVideosOwnPalette() {
        // The renderer's class colours are the app theme's, which the move list, the badges and the video panel use.
        val theme = mapOf(
            MoveClassification.BRILLIANT to ClassBrilliant, MoveClassification.GREAT to ClassGreat,
            MoveClassification.INACCURACY to ClassInaccuracy, MoveClassification.MISTAKE to ClassMistake,
            MoveClassification.MISS to ClassMiss, MoveClassification.BLUNDER to ClassBlunder,
        )
        for ((cls, colour) in theme) {
            assertEquals("$cls", colour.toArgb(), BoardFrameRenderer.classificationColors[cls])
        }

        val bitmap = render(recap("MorphyFan1857", "DukeAndCount"))
        save(bitmap, "recap_palette")
        // Every chip colour is on the card; the 91 percent figure is the Summary's good green, 62 the low red.
        for ((cls, colour) in theme) {
            assertTrue("$cls chip colour missing", countColour(bitmap, colour.toArgb()) > 200)
        }
        assertTrue("good-accuracy green missing", countColour(bitmap, GreenPrimary.toArgb()) > 500)
        assertTrue("background is the cards' dark surface", countColour(bitmap, background) > w * h / 2)
        bitmap.recycle()
    }

    // -----------------------------------------------------------------------
    // The exported file
    // -----------------------------------------------------------------------

    private fun rms(pcm: ExportedAudioProbe.Pcm, fromMs: Long, toMs: Long): Double {
        val from = (fromMs * pcm.sampleRate / 1000L).toInt().coerceIn(0, pcm.mono.size)
        val to = (toMs * pcm.sampleRate / 1000L).toInt().coerceIn(from, pcm.mono.size)
        if (to == from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += pcm.mono[i].toDouble() * pcm.mono[i]
        return sqrt(sum / (to - from))
    }

    /** Mean absolute difference per colour channel over a sparse grid of pixels. */
    private fun meanDiff(a: Bitmap, b: Bitmap): Double {
        var sum = 0L
        var n = 0
        for (y in 0 until h step 6) for (x in 0 until w step 6) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
            n += 3
        }
        return sum.toDouble() / n
    }

    @Test
    fun theExportedMp4EndsWithTheSilentRecapCard(): Unit = runBlocking {
        val base = TestScripts.syntheticScript()
        val withRecap = base.copy(recap = recap("TestWhite", "TestBlack"))
        val card = RecapCardContent.from(withRecap.recap!!, BoardFrameRenderer.PanelLabels.from(context).recap)
        val expectedRecapMs = recapDurationMs(card.readingWords)
        assertTrue("4 to 6 s: $expectedRecapMs", expectedRecapMs in 4_000L..6_000L)

        val plainProvider = ToneVoiceProvider()
        val plainExporter = VideoExporter(context)
        val plainFile = plainExporter.export(base, "recap_test_without", plainProvider)
        val plain = plainExporter.state.value as VideoExporter.State.Completed

        val recapProvider = ToneVoiceProvider()
        val recapExporter = VideoExporter(context)
        val recapFile = recapExporter.export(withRecap, "recap_test_with", recapProvider)
        val completed = recapExporter.state.value as VideoExporter.State.Completed

        // (1) The export is longer by exactly the card's duration; the narrated part is untouched.
        assertEquals("the narrated length is unchanged", plain.durationMs, completed.durationMs - expectedRecapMs)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(recapFile.absolutePath)
        val reported = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val plainRetriever = MediaMetadataRetriever()
        plainRetriever.setDataSource(plainFile.absolutePath)
        val plainReported = plainRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        android.util.Log.i("VideoRecapExport", "plain=$plainReported ms, with recap=$reported ms, recap=$expectedRecapMs ms")
        assertTrue(
            "the file with the card is $reported ms, the one without $plainReported ms; expected +$expectedRecapMs",
            abs((reported - plainReported) - expectedRecapMs) <= 150L,
        )

        // (2) The last frames are the card, the frames before it are not.
        val expectedCard = render(withRecap.recap!!)
        val dir = File(context.getExternalFilesDir(null), "recap_frames").apply { mkdirs() }
        val narratedEndMs = completed.durationMs - expectedRecapMs
        var worstCardDiff = 0.0
        val lastFrames = listOf(reported - 150L, reported - expectedRecapMs / 2, narratedEndMs + 600L)
        for ((i, t) in lastFrames.withIndex()) {
            val frame = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)!!
            FileOutputStream(File(dir, "export_last_$i.png")).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val diff = meanDiff(frame, expectedCard)
            worstCardDiff = maxOf(worstCardDiff, diff)
            assertTrue("frame at ${t} ms should be the recap card, mean diff $diff", diff < 8.0)
        }
        val before = retriever.getFrameAtTime((narratedEndMs - 700L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)!!
        FileOutputStream(File(dir, "export_before_recap.png")).use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val diffBefore = meanDiff(before, expectedCard)
        android.util.Log.i("VideoRecapExport", "mean diff: card=$worstCardDiff before=$diffBefore")
        assertTrue(
            "the frame just before the card must not be the card: mean diff $diffBefore vs $worstCardDiff on the card",
            diffBefore > 3.0 * worstCardDiff && diffBefore > 3.0,
        )
        retriever.release()
        plainRetriever.release()

        // (3) The audio runs as long as the picture, and the card is silent while the narration is not.
        val pcm = ExportedAudioProbe.decodeAudioTrack(recapFile)
        val audioMs = pcm.mono.size * 1000L / pcm.sampleRate
        assertTrue("audio $audioMs ms vs video $reported ms", abs(audioMs - reported) <= 200L)
        val narrated = rms(pcm, 0L, narratedEndMs - 300L)
        val onCard = rms(pcm, narratedEndMs + 200L, reported - 200L)
        android.util.Log.i("VideoRecapExport", "audio rms narrated=$narrated onCard=$onCard")
        assertTrue("narration should be audible (rms $narrated)", narrated > 500.0)
        assertTrue("the recap card must be silent (rms $onCard)", onCard < 5.0)
        val tone = ExportedAudioProbe.toneWindows(pcm, recapProvider.toneHz)
        assertTrue("the tone narrator must be in the file: $tone", tone.toneDominatedWindows >= 10)
        assertTrue("and nothing was narrated for the card: every request is a script segment", recapProvider.requested.all { r -> base.segments.any { it.narration == r } })
        assertEquals("no extra sentence was sent to the voice for the card", base.segments.count { it.narration.isNotBlank() }, recapProvider.requested.size)
        assertEquals("the same sentences as without the card", plainProvider.requested.sorted(), recapProvider.requested.sorted())
    }
}
