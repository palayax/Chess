package net.palaya.chessanalyzer.video

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import net.palaya.chessanalyzer.core.narration.Delivery
import net.palaya.chessanalyzer.core.narration.ScriptSegment

/**
 * The one place a [NarrationStore] key is computed (NC, ANALYSIS_SPEC 9.10). How a beat is delivered (its speed and
 * its sentence gaps) is different audio for the same words, so it is part of the key; every caller that looks a clip up
 * or stores one (the coordinator's sentence and segment keys, the preparer's "what is missing", the player, the
 * export) goes through here, so they can never disagree.
 *
 * Compatibility: a neutral delivery (speed 1, gap scale 1) adds nothing to the hashed text, so every clip made
 * before delivery existed keeps its key and stays valid. A sentence clip is a single utterance, so only the SPEED
 * is in its key; a segment clip is the stitched sentences, gaps included, so its key also carries the gap scale.
 *
 * Pure (no Android), host-tested in `NarrationKeysTest`.
 */
object NarrationKeys {

    private const val EPSILON = 1e-4f

    /** Hash of (provider display name, provider fingerprint, text): [NarrationStore.keyFor]'s body, unchanged. */
    fun hash(text: String, providerDisplayName: String, providerFingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(providerDisplayName.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(providerFingerprint.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** "/spd0.94" for a speed other than 1, else nothing. */
    fun speedSuffix(speed: Float): String =
        if (abs(speed - 1f) < EPSILON) "" else "/spd%.2f".format(Locale.ROOT, speed)

    /** "/gap1.25" for a gap scale other than 1, else nothing. */
    fun gapSuffix(gapScale: Float): String =
        if (abs(gapScale - 1f) < EPSILON) "" else "/gap%.2f".format(Locale.ROOT, gapScale)

    /** The key of one sentence's clip spoken at [delivery]'s speed. */
    fun sentenceKey(sentence: String, providerDisplayName: String, providerFingerprint: String, delivery: Delivery): String =
        hash(sentence + speedSuffix(delivery.speed), providerDisplayName, providerFingerprint)

    /** The key of a whole segment's clip (its sentences stitched with [delivery]'s gaps). */
    fun segmentKey(narration: String, providerDisplayName: String, providerFingerprint: String, delivery: Delivery): String =
        hash(narration + speedSuffix(delivery.speed) + gapSuffix(delivery.gapScale), providerDisplayName, providerFingerprint)

    fun segmentKey(segment: ScriptSegment, providerDisplayName: String, providerFingerprint: String): String =
        segmentKey(segment.narration, providerDisplayName, providerFingerprint, segment.delivery)
}
