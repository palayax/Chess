package net.palaya.chessanalyzer.data

import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice

/**
 * The narration provider read back from persisted settings, after tolerating values this build no
 * longer knows. Pure (no Android types) so the migration is unit-testable on the host.
 *
 * The neural voice ships inside the app, so it is the default: a missing value (a fresh install, or
 * a build that never stored one) and any value this build does not know all read as
 * [NarrationProviderChoice.NEURAL].
 *
 * Builds before Round 13 could persist `CLOUD` (Google Cloud TTS, since removed). Such a user reads
 * as NEURAL and, because they never actually chose a provider this build offers, with
 * [explicitlyChosen] cleared. A missing value keeps the stored flag as-is.
 */
data class ResolvedProvider(val provider: NarrationProviderChoice, val explicitlyChosen: Boolean) {
    companion object {
        fun from(storedProvider: String?, storedExplicit: Boolean): ResolvedProvider {
            val parsed = NarrationProviderChoice.fromPersistedOrNull(storedProvider)
            return ResolvedProvider(
                provider = parsed ?: NarrationProviderChoice.NEURAL,
                explicitlyChosen = storedExplicit && (storedProvider == null || parsed != null),
            )
        }
    }
}
