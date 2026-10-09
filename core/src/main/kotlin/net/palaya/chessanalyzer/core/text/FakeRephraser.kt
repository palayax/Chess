package net.palaya.chessanalyzer.core.text

/**
 * A [Rephraser] without a model, for host and instrumented tests (docs/LLM_REPHRASE_DESIGN.md §6.1, §8).
 * [rewrite] plays the model: it gets the request and returns the raw output (null = unavailable). The
 * output goes through [RephraseVerdicts.judge] exactly like a real backend's, so a fake can never hand
 * the app a text the checker has not accepted.
 */
class FakeRephraser(
    override val id: String = "fake@p${RephrasePrompt.VERSION}",
    private val rewrite: (RephraseRequest) -> String?,
) : Rephraser {

    /** How many requests reached the "model". */
    @Volatile
    var calls: Int = 0
        private set

    override suspend fun rephrase(request: RephraseRequest): RephraseResult {
        calls++
        val raw = rewrite(request) ?: return RephraseResult.Unavailable("fake: no output")
        return RephraseVerdicts.judge(request, raw)
    }

    companion object {
        /** Returns every text unchanged. */
        fun identity(id: String = "fake-identity@p${RephrasePrompt.VERSION}") = FakeRephraser(id) { it.text }

        /** A fixed table original -> output; a text not in it comes back unchanged. */
        fun table(entries: Map<String, String>, id: String = "fake-table@p${RephrasePrompt.VERSION}") =
            FakeRephraser(id) { entries[it.text] ?: it.text }

        /** An adversary that applies [mutation] to every text; the checker should reject what it returns. */
        fun adversary(id: String = "fake-adversary@p${RephrasePrompt.VERSION}", mutation: (String) -> String) =
            FakeRephraser(id) { mutation(it.text) }
    }
}
