package net.palaya.chessanalyzer.ui.model

import java.util.Locale

/**
 * The pure logic behind the rebuilt Settings screen (UX step U9): preset mapping for the two
 * segmented controls, the "Custom" labels for stored values that fall between presets, and the
 * voice switch. No Android types, so every rule here is host-testable.
 *
 * The stored values keep their original ints and clamps (`SettingsRepository`: depth 6..30,
 * narration threshold 0..300 cp). The presets are just three named points inside those ranges, so
 * an old install with a value the presets cannot express keeps working and reads as "Custom".
 */

/** "Analysis strength": Quick / Standard / Deep = search depth 12 / 14 / 18 (design §3.1). */
enum class AnalysisStrength(val depth: Int) {
    QUICK(12),
    STANDARD(14),
    DEEP(18);

    /** The per-position search limits for this strength (ANALYSIS_SPEC §8.1). */
    val budget: SearchBudget get() = SearchBudget.forStrength(this)

    companion object {
        /** The preset whose depth is exactly [depth], or null when the stored value is "Custom". */
        fun fromDepth(depth: Int): AnalysisStrength? = entries.firstOrNull { it.depth == depth }
    }
}

/**
 * "What the review talks about": Only big moments / Balanced / Every move = a significance
 * threshold of 100 / 50 / 0 centipawns (design §3.1). 50 is the pinned app default
 * (`SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP`).
 */
enum class ReviewDetail(val thresholdCp: Int) {
    ONLY_BIG_MOMENTS(100),
    BALANCED(50),
    EVERY_MOVE(0);

    companion object {
        /** The preset whose threshold is exactly [thresholdCp], or null when it is "Custom". */
        fun fromThresholdCp(thresholdCp: Int): ReviewDetail? = entries.firstOrNull { it.thresholdCp == thresholdCp }
    }
}

/** Left-to-right mark: keeps digits and signs in one piece when the surrounding text is RTL. */
const val LRM: Char = '‎'

/** The number inside "Custom (18)": an integer wrapped so an RTL paragraph cannot reverse it. */
fun customDepthValue(depth: Int): String = "$LRM$depth$LRM"

/**
 * The value inside "Custom (±0.7)": the threshold in pawns to one decimal with a plus-minus sign,
 * wrapped in LRM. Locale.ROOT so a Hebrew or Arabic device does not substitute other digits or a
 * comma for the decimal point (the same rule `EvalFormat` follows for the board).
 */
fun customThresholdValue(thresholdCp: Int): String =
    "$LRM±" + String.format(Locale.ROOT, "%.1f", thresholdCp / 100.0) + "$LRM"

/** Which narration provider the switch "Use the phone's built-in voice instead" writes. */
fun providerForVoiceSwitch(useDeviceVoice: Boolean): NarrationProviderChoice =
    if (useDeviceVoice) NarrationProviderChoice.DEVICE else NarrationProviderChoice.NEURAL

/** The switch's position for the stored provider: on only for the phone's own voice. */
fun voiceSwitchIsOn(provider: NarrationProviderChoice): Boolean = provider == NarrationProviderChoice.DEVICE

// formatStorageMegabytes moved to SetupLogic.kt (D2c, design §1.2): one place for every size the app prints.

/**
 * State of the "Advanced" expander. A plain holder rather than raw `rememberSaveable { Boolean }`
 * so the toggle rule is testable: collapsed by default, one tap flips it.
 */
data class AdvancedExpander(val expanded: Boolean = false) {
    fun toggled(): AdvancedExpander = AdvancedExpander(!expanded)
}
