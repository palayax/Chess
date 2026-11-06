package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.narration.VideoPace

/*
 * The "Review options" screen (owner's phone test, 2026-10-11): everything a review needs is decided on one screen,
 * once, before any processing, and stored with the game. Settings holds only the defaults for new games. The rules are
 * here, pure, so each has a host test (ReviewOptionsLogicTest); the screen and the ViewModel only apply them.
 *
 * Reference (owner's standing rule, recorded in RUN_LOG "RF"): chess.com's Game Review asks for the few choices that
 * matter before it starts (which side you were, how deep the engine looks) on one short page with one primary button,
 * and runs the whole review from there; the choices belong to that review. Taken as a pattern: one scrollable page, a
 * game header, single-choice lists with a one-line description, a sticky "Start review" button. Our own words and layout.
 */

/** Everything one review of one game runs with. Stored with the game (`GameRepository.StoredGame`). */
data class ReviewOptions(
    /** The engine level (ANALYSIS_SPEC §8.6). */
    val level: EngineLevel,
    /** The side the user played; [SideChoice.UNKNOWN] leaves it to the Summary's chooser. */
    val side: SideChoice,
    /** Natural wording (on-device AI) for this game; only used when the model is installed and supported. */
    val naturalWording: Boolean,
    /** Narration text, its rewording and its audio are produced in the same processing run. */
    val prepareVideo: Boolean,
    val voice: NarrationProviderChoice,
    val speakerId: Int,
    val pace: VideoPace,
) {
    /** The narration voice of this game, in the shape the narration code reads. */
    val voiceSettings: NarrationVoiceSettings
        get() = NarrationVoiceSettings(provider = voice, speakerId = speakerId, providerExplicitlyChosen = true)
}

/** What a game has stored; null = never stored (a game from before this screen, or a new import). */
data class StoredReviewOptions(
    val depth: Int? = null,
    /** Analysed with Auto-depth (ANALYSIS_SPEC §8.5); [depth] is then its first pass's depth. */
    val autoDepth: Boolean = false,
    val side: SideChoice = SideChoice.UNKNOWN,
    val naturalWording: Boolean? = null,
    val prepareVideo: Boolean? = null,
    val voice: NarrationProviderChoice? = null,
    val speakerId: Int? = null,
    val pace: VideoPace? = null,
)

/** The Settings values, which are the defaults for a game with nothing stored. */
data class ReviewDefaults(
    val depth: Int,
    /** Legacy: Settings from before the Elo levels said Auto ([EngineLevel.forLegacyDepth] maps it). */
    val autoDepth: Boolean = false,
    val naturalWording: Boolean,
    val prepareVideo: Boolean,
    val voice: NarrationVoiceSettings,
    val pace: VideoPace,
)

/**
 * The options the screen opens with, in order of authority: this game's stored value, then the Settings default. The
 * side: an answer stored with the game, then the side the game opened with (a famous game: "Not me"), then the
 * username detection ([resolveSide], the same rule the Summary uses).
 */
fun prefillReviewOptions(
    stored: StoredReviewOptions?,
    defaults: ReviewDefaults,
    detectedSide: PieceColor? = null,
    initialSide: SideChoice = SideChoice.UNKNOWN,
): ReviewOptions {
    val s = stored ?: StoredReviewOptions()
    val voice = s.voice ?: defaults.voice.provider
    return ReviewOptions(
        // A level code is taken as it is; a legacy depth (an old game, an old Settings value) pre-selects the nearest
        // level ([EngineLevel.forLegacyDepth]); the stored game itself is not rewritten.
        level = when {
            s.depth != null -> EngineLevel.fromStored(s.depth, s.autoDepth)
            else -> EngineLevel.fromStored(defaults.depth, defaults.autoDepth)
        },
        side = resolveSide(stored = s.side, detected = detectedSide, initial = initialSide),
        naturalWording = s.naturalWording ?: defaults.naturalWording,
        prepareVideo = s.prepareVideo ?: defaults.prepareVideo,
        voice = voice,
        speakerId = s.speakerId ?: defaults.voice.speakerId,
        pace = s.pace ?: defaults.pace,
    )
}

/** What the screen offers for Natural wording. */
enum class WordingOption {
    /** The phone cannot run the model: nothing is shown. */
    HIDDEN,

    /** Supported but not installed: one line with a link to Settings, where it is downloaded. */
    DOWNLOAD_IN_SETTINGS,

    /** Installed: the on/off switch. */
    SWITCH,
}

fun wordingOption(supported: Boolean, installed: Boolean): WordingOption = when {
    !supported -> WordingOption.HIDDEN
    !installed -> WordingOption.DOWNLOAD_IN_SETTINGS
    else -> WordingOption.SWITCH
}

/** Wording runs for a game only when it is asked for and the model can run. */
fun wordingInEffect(options: ReviewOptions, modelUsable: Boolean): Boolean = options.naturalWording && modelUsable

/** Where a review was started from. */
enum class ReviewEntry {
    /** A paste, a file, a share or a Famous game. */
    IMPORT,

    /** "Re-analyse with different settings…" on a library row. */
    LIBRARY,

    /** The Summary's re-analyse entry. */
    SUMMARY,
}

/** The options screen opens for every re-analyse; for an import only while "Show this screen before each review" is on. */
fun showReviewOptions(entry: ReviewEntry, showBeforeEachReview: Boolean): Boolean =
    entry != ReviewEntry.IMPORT || showBeforeEachReview

/**
 * The rough engine time of a legacy strength on this phone: [positions] x the nodes a position typically takes at that
 * strength ([SearchBudget.typicalNodes], calibrated on the host) x this phone's measured milliseconds per node.
 * Null when the phone has not been measured yet (nothing is invented), or there is no typical node count.
 * [cached] = the game's evaluations are already on disk: no engine time at all (0).
 */
fun strengthEstimateMs(positions: Int, typicalNodes: Long?, msPerNode: Double?, cached: Boolean = false): Long? {
    if (cached) return 0L
    if (positions <= 0 || typicalNodes == null || typicalNodes <= 0L || msPerNode == null || msPerNode <= 0.0) return null
    return Math.round(positions * typicalNodes.toDouble() * msPerNode)
}

/**
 * The engine time of an Elo [level] for a game of [positions] searched positions (spec §8.4, §8.6):
 * min(positions x the level's nodes x this phone's ms per node, the game budget [budgetMs]). Max has no node count: it is
 * min(positions x [MAX_LEVEL_MOVETIME_CEILING_MS], the budget), known without any measurement. Without a measurement every
 * other level is null (nothing is invented; the Review options screen then uses [levelEngineEstimate]'s bound).
 * [cached] = already on disk: 0.
 */
fun levelEstimateMs(
    level: EngineLevel,
    positions: Int,
    msPerNode: Double?,
    cached: Boolean = false,
    budgetMs: Long = GAME_ENGINE_BUDGET_MS,
): Long? {
    if (cached) return 0L
    val nodes = level.nodes ?: return minOf(positions.coerceAtLeast(0) * MAX_LEVEL_MOVETIME_CEILING_MS, budgetMs)
    val byNodes = strengthEstimateMs(positions, nodes, msPerNode) ?: return null
    return minOf(byNodes, budgetMs)
}

/**
 * The time of the WHOLE review for the Review options screen (spec §8.4): the engine stage's [engine] estimate plus every
 * other stage of [plan]: its measured time ([ReviewSpeeds.estimateMs]) or, when this phone never measured it, the stage's
 * bound ([boundMs]; never 0). Capped at [REVIEW_TOTAL_BUDGET_MS]. An upper bound ([TimeEstimate.upperBound]) as soon as any
 * part is one. [wording] = the game's Natural wording runs.
 */
fun wholeReviewEstimateMs(engine: TimeEstimate, plan: List<ReviewStage>, speeds: ReviewSpeeds, plies: Int, wording: Boolean): TimeEstimate {
    val others = plan.filter { it != ReviewStage.ENGINE }.map { stage ->
        speeds.estimateMs(stage, plies)?.let { TimeEstimate(it, upperBound = false) }
            ?: TimeEstimate(stage.boundMs(wording), upperBound = true)
    }
    return wholeReviewEstimate(engine, others)
}

/**
 * What the Review options screen needs to time a review for ANY set of options: everything that does not depend on the
 * options being edited. The time per level is then computed from the options as they are NOW ([levelEstimates]), so
 * switching "Prepare the video now", Natural wording or the voice changes the stages of the plan and the figures with it.
 */
data class ReviewEstimateInputs(
    /** Searched positions of the game (plies + 1). */
    val positions: Int,
    val plies: Int,
    val speeds: ReviewSpeeds,
    /** The levels whose evaluations of this game are already on the phone. */
    val cachedLevels: Set<EngineLevel>,
    /** The wording model is installed and supported ([wordingInEffect] with the options' switch). */
    val wordingUsable: Boolean,
    /** The natural voice is installed. */
    val kokoroInstalled: Boolean,
)

/** The whole-review time per level for [options] (the plan changes with the video, wording and voice choices). */
fun levelEstimates(inputs: ReviewEstimateInputs, options: ReviewOptions): Map<EngineLevel, TimeEstimate> {
    val plan = reviewStagePlan(options, inputs.wordingUsable, inputs.kokoroInstalled)
    val wording = wordingInEffect(options, inputs.wordingUsable)
    return EngineLevel.entries.associateWith { level ->
        val engine = levelEngineEstimate(
            level,
            inputs.positions,
            inputs.speeds.engineMsPerNode,
            cached = level in inputs.cachedLevels,
        )
        wholeReviewEstimateMs(engine, plan, inputs.speeds, inputs.plies, wording)
    }
}

/**
 * The narration audio must be made before the video plays: the voice is the natural (Kokoro) voice, it is installed,
 * and some segment of the finished script has no recording in that voice yet. The device voice speaks live and never
 * needs it; a voice that is not installed yet falls back to the device voice with its own notice (Finish setup).
 */
fun narrationNeedsPreparation(voice: NarrationProviderChoice, voiceInstalled: Boolean, missingSegments: Int): Boolean =
    voice == NarrationProviderChoice.NEURAL && voiceInstalled && missingSegments > 0

/**
 * A change of the voice in the Video screen's sheet changes the recording identity (provider or speaker), so the
 * narration is checked again (and recorded inline when [narrationNeedsPreparation] says so). A pace change does not:
 * the pace is laid over the same sentences (ANALYSIS_SPEC §9.8), whose recordings stay valid.
 */
fun voiceIdentityChanged(before: ReviewOptions, after: ReviewOptions): Boolean =
    before.voice != after.voice || (after.voice == NarrationProviderChoice.NEURAL && before.speakerId != after.speakerId)

/**
 * The side the username names in a game's tags (the same rule the analysis uses: an exact, case-insensitive match of
 * the White or Black name), or null when there is no username or neither name matches.
 */
fun detectSide(white: String, black: String, username: String): PieceColor? {
    if (username.isBlank()) return null
    return when {
        white.equals(username, ignoreCase = true) -> PieceColor.WHITE
        black.equals(username, ignoreCase = true) -> PieceColor.BLACK
        else -> null
    }
}

/** What the Review options screen shows for one game. */
data class ReviewOptionsDraft(
    val gameId: String,
    val header: GameHeader,
    val plies: Int,
    val options: ReviewOptions,
    val wording: WordingOption,
    /** The natural voice is installed (else its voices are listed as "after setup"). */
    val voiceInstalled: Boolean,
    /** What the whole-review time per level is computed from, with the options as edited ([levelEstimates]). */
    val estimateInputs: ReviewEstimateInputs,
)

/** The Review options screen's load state. */
sealed interface ReviewOptionsLoad {
    data object Loading : ReviewOptionsLoad

    /** The game's text cannot be read: the processing screen shows the parse error (with Back to fix the paste). */
    data object Unreadable : ReviewOptionsLoad

    /** Nothing is registered under this id any more (process death): back to Home. */
    data object Missing : ReviewOptionsLoad

    data class Ready(val draft: ReviewOptionsDraft) : ReviewOptionsLoad
}

/** The Video screen's narration recording, done inline in the player area when audio is missing (RF). */
sealed interface NarrationPrepState {
    /** Nothing to report (no game checked yet, or the device voice). */
    data object Idle : NarrationPrepState

    /** Recording [done] of [total] segments for [gameId], [timeLeft] measured. */
    data class Preparing(val gameId: String, val done: Int, val total: Int, val timeLeft: net.palaya.chessanalyzer.video.ExportTimeLeft) : NarrationPrepState

    /** Stopped (Cancel) or some segments could not be recorded: [missing] segments are read by the device voice. */
    data class Incomplete(val gameId: String, val missing: Int) : NarrationPrepState

    /** Every segment of [gameId] is recorded in its voice. [version] rises with each recording, so the player is rebuilt. */
    data class Ready(val gameId: String, val version: Int) : NarrationPrepState
}
