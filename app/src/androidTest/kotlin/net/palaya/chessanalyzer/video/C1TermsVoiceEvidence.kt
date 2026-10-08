package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ManualEvidenceTool
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C1-device evidence: the narration sentences that carry the professional terms C1 added, through the
 * Kokoro voice (default speaker, Bella sid 1) with the app's own engine call. For each sentence two WAVs:
 * `<name>_raw.wav` is the real text handed to the engine as it is ([NeuralTtsProvider.synthesizeAs]);
 * `<name>_spoken.wav` is the narration path ([NeuralTtsProvider.synthesize], which respells the term for
 * the voice, [SpokenRespelling]). A manifest lists text, spoken text, duration and SHA-256. Not part of the
 * suite ([ManualEvidenceTool]):
 * ```
 * adb shell am instrument -w -r -e class 'net.palaya.chessanalyzer.video.C1TermsVoiceEvidence' \
 *   net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/net.palaya.chessanalyzer/files/c1_terms <host dir>
 * ```
 */
@RunWith(AndroidJUnit4::class)
class C1TermsVoiceEvidence {

    private val terms = listOf(
        "zwischenzug" to "A zwischenzug, an in-between move: something forcing goes in first, and the capture is still there afterwards.",
        "zwischenzug_bare" to "zwischenzug",
        "en_prise" to "The queen on c six is en prise: nothing defends it.",
        "en_prise_bare" to "en prise",
        "desperado" to "A desperado: the piece is lost anyway, so it sells itself as dearly as it can.",
        "desperado_bare" to "desperado",
        "skewer" to "That's a skewer: the piece on d five is attacked, and whatever stands behind it on a eight is next.",
        "skewer_bare" to "skewer",
        "decisively" to "Black has gone from clearly worse to decisively lost on one move.",
        "decisively_bare" to "decisively",
        "exchange_up" to "White comes out of it the exchange up.",
        "exchange_bare" to "the exchange up",
        "overloaded" to "An overloaded defender: one piece holding two things, and it can only keep one.",
        "deflection" to "A deflection: the defender is pulled away from d seven, and what it was guarding is left open.",
        "decoy" to "A decoy: it drags a piece onto the wrong square, and that is the whole idea.",
        "smothered_mate" to "It's a smothered mate. The king suffocates between its own pieces.",
        "back_rank" to "Back-rank mate on e eight: the king's own pawns shut it in.",
        "greek_gift" to "It's the Greek gift. Bishop goes in, the king gets dragged out, the knight and queen finish it.",
        "discovered" to "A discovered attack: the piece steps aside and unmasks the one behind it.",
        "absolute_pin" to "That's an absolute pin: the piece on c six is tied to its own king and cannot step off the line.",
        "relative_pin" to "That's a relative pin: the piece on b seven is tied to something worth more behind it.",
        "double_check" to "Double check. The king has to move, and nothing else is legal.",
        "pawn_fork" to "That's a pawn fork: c five and e five are both attacked, and only one of them can get away.",
    )

    @ManualEvidenceTool
    @Test
    fun renderTheC1TermsRawAndRespelled(): Unit = runBlocking {
        val app = TestApp.app
        val modelDir = TestApp.installedVoiceDir()
        val out = File(app.getExternalFilesDir(null), "c1_terms").apply { deleteRecursively(); mkdirs() }
        val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir, voiceVersionId = app.voiceStore.installedVersionId())
        assertTrue(provider.prepare())
        val manifest = StringBuilder("sid=${NeuralVoiceTier.KOKORO.speakerId} fingerprint=${provider.cacheFingerprint}\n")
        try {
            for ((name, text) in terms) {
                val spoken = SpokenRespelling.apply(text)
                for (suffix in listOf("raw", "spoken")) {
                    val f = File(out, "${name}_$suffix.wav")
                    val r = if (suffix == "raw") provider.synthesizeAs(NeuralVoiceTier.KOKORO.speakerId, text, f) else provider.synthesize(text, f)
                    assertTrue("$name $suffix: $r", r is SynthesisResult.Success)
                    val ms = (r as SynthesisResult.Success).durationMs
                    val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }.take(12)
                    manifest.append("$name\t$suffix\t${ms}ms\t$sha\ttext=$text\tspoken=$spoken\n")
                }
            }
        } finally {
            provider.release()
        }
        File(out, "manifest.tsv").writeText(manifest.toString())
    }
}
