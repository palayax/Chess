package net.palaya.chessanalyzer.ui.model

import java.security.MessageDigest
import net.palaya.chessanalyzer.rephrase.RephraseSupport

// Pure rules of the narration recording (RF, owner's phone test 2026-10-13: "after the analysis finished, watching the
// video review does an additional process that takes forever"). Host-tested in NarrationPrepLogicTest.

/**
 * Threads for the Kokoro voice: the wording model's fast-core heuristic ([RephraseSupport.recommendedThreads]) held to
 * 2..4. More than 4 onnxruntime threads gain little on a phone and fight the UI; a phone that does not report its core
 * frequencies gets 2 (the heuristic's answer), not the old fixed 4 that also ran on the slow cores.
 */
fun kokoroThreads(maxFreqKhzPerCore: List<Long>): Int =
    RephraseSupport.recommendedThreads(maxFreqKhzPerCore).coerceIn(2, 4)

/** What a recording is of: the provider's name and its cache fingerprint (voice files, speaker, pace of speech). */
fun narrationIdentity(providerDisplayName: String, providerFingerprint: String): String =
    "$providerDisplayName|$providerFingerprint"

/**
 * A key of one finished script, stable across processes (a `hashCode()` of the script is not: it holds enums). It is the
 * game and the texts that are spoken; two scripts that sound the same are the same recording job.
 */
fun narrationScriptKey(gameId: String, narrationTexts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(gameId.toByteArray(Charsets.UTF_8))
    for (text in narrationTexts) {
        digest.update(0.toByte())
        digest.update(text.toByteArray(Charsets.UTF_8))
    }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}

/**
 * How many of [segmentKeys] (the narration-store key of each spoken segment) have no recording, not counting the keys in
 * [ignoring] (segments that fell back to the device voice in an earlier attempt for this voice identity, so retrying
 * them would only load the voice again to fail the same way).
 */
fun missingNarrationCount(segmentKeys: List<String>, ignoring: Set<String>, isRecorded: (String) -> Boolean): Int =
    segmentKeys.count { it !in ignoring && !isRecorded(it) }

/**
 * Whether a recording should start by itself, without the Video screen (the Summary, after the side was answered or a
 * plain reopen): the game asks for the prepared video, the natural voice is installed with segments to record
 * ([narrationNeedsPreparation]), and this process has not tried it for this game, voice and side already
 * ([alreadyTried]: a Skip or a Cancel is the user's answer and is not asked again until something changes).
 */
fun backgroundPreparationWanted(
    prepareVideo: Boolean,
    voice: NarrationProviderChoice,
    voiceInstalled: Boolean,
    missingSegments: Int,
    alreadyTried: Boolean,
): Boolean = prepareVideo && !alreadyTried && narrationNeedsPreparation(voice, voiceInstalled, missingSegments)

/** What one background attempt is keyed by: a different game, voice, speaker or side is a new question. */
fun backgroundPreparationKey(gameId: String, options: ReviewOptions): String =
    "$gameId|${options.voice.name}|${options.speakerId}|${options.side.name}"

/** What [runAnalysis][net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel.runAnalysis] does with a call for a game. */
enum class RunRequestAction {
    /** Nothing in memory for the game, or its run is over: do the run. */
    START,

    /** The game is in memory and no run of it is active: the processing screen can go to the Summary. */
    COMPLETE_NOW,

    /**
     * The game is in memory (the engine stage stored it) but its run is still doing the other stages: the run's own
     * completion takes the screen to the Summary. Reporting completion now would show the Summary while the stages go
     * on, and the Video tap that follows would start a second, competing recording.
     */
    KEEP_WAITING,
}

fun runRequestAction(gameInMemory: Boolean, runActiveForGame: Boolean): RunRequestAction = when {
    !gameInMemory -> RunRequestAction.START
    runActiveForGame -> RunRequestAction.KEEP_WAITING
    else -> RunRequestAction.COMPLETE_NOW
}

/**
 * What was recorded, per voice identity ([narrationIdentity]), kept on disk so a restart does not start over (RF):
 *  - [Identity.scripts]: the scripts ([narrationScriptKey]) a recording was run for. An attempt that left segments
 *    unrecorded (the device voice reads them) is not repeated every time the Video screen opens; "Prepare the
 *    narration" forces it.
 *  - [Identity.fallback]: the narration-store keys of the segments that fell back to the device voice. They are not
 *    counted as missing for that identity. A voice update or another speaker is another identity, so they are tried
 *    again then.
 * Bounded (the file is small JSON): the last [MAX_IDENTITIES] identities, [MAX_SCRIPTS] scripts and [MAX_FALLBACK] keys each.
 */
data class PreparedNarrationLedger(val identities: List<Identity> = emptyList()) {

    data class Identity(val id: String, val scripts: List<String>, val fallback: List<String>)

    fun isPrepared(identity: String, scriptKey: String): Boolean =
        identities.any { it.id == identity && scriptKey in it.scripts }

    fun fallbackKeys(identity: String): Set<String> =
        identities.firstOrNull { it.id == identity }?.fallback?.toSet().orEmpty()

    /**
     * A recording of [scriptKey] finished in [identity]. [scriptSegmentKeys] are the store keys of all its spoken
     * segments: their old fallback marks are replaced by [fallback] (this attempt's), so a retry that worked clears them.
     */
    fun record(identity: String, scriptKey: String, scriptSegmentKeys: Set<String>, fallback: Set<String>): PreparedNarrationLedger {
        val old = identities.firstOrNull { it.id == identity }
        val scripts = (old?.scripts.orEmpty().filter { it != scriptKey } + scriptKey).takeLast(MAX_SCRIPTS)
        val marks = (old?.fallback.orEmpty().filter { it !in scriptSegmentKeys } + fallback.sorted()).distinct().takeLast(MAX_FALLBACK)
        val kept = identities.filter { it.id != identity } + Identity(identity, scripts, marks)
        return PreparedNarrationLedger(kept.takeLast(MAX_IDENTITIES))
    }

    companion object {
        const val MAX_IDENTITIES = 6
        const val MAX_SCRIPTS = 60
        const val MAX_FALLBACK = 2_000
    }
}
