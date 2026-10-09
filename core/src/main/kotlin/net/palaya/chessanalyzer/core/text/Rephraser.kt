package net.palaya.chessanalyzer.core.text

/**
 * C2 (docs/LLM_REPHRASE_DESIGN.md §6.1): the narrow interface between the verified commentary and an
 * on-device language model that may only reword it. Everything above it (cache, checker, jobs) is in the
 * app layer; the one real implementation is the llama.cpp backend in `:rephrase`.
 */
enum class RephraseSurface { CARD, NARRATION }

data class RephraseRequest(
    val surface: RephraseSurface,
    /** The verified original: one card text or one narration beat. */
    val text: String,
    /** [ClaimChecker.facts] of [text], passed so the prompt and the check agree. */
    val facts: ClaimChecker.Facts = ClaimChecker.facts(text, surface),
    /** Narration: the SAN of the moves the beat speaks, from the segment (informational). */
    val sanHints: List<String> = emptyList(),
)

sealed interface RephraseResult {
    /** The model's text, already accepted by [ClaimChecker]. */
    data class Accepted(val text: String) : RephraseResult

    /** The model returned the original: keep it. */
    data object Unchanged : RephraseResult

    /** The model's text failed the checker; the caller keeps the original. */
    data class Rejected(val candidate: String, val reason: ClaimChecker.Reason, val detail: String = "") : RephraseResult

    /** No model, out of memory, cancelled, timed out: the caller keeps the original. */
    data class Unavailable(val cause: String) : RephraseResult
}

interface Rephraser {
    /** "qwen2.5-1.5b-instruct-q4_k_m@p1": the model id and the prompt version, part of every cache key. */
    val id: String

    suspend fun rephrase(request: RephraseRequest): RephraseResult
}

/**
 * The step every backend shares: clean the raw model output, then hold it to the original with
 * [ClaimChecker]. A backend that produces raw text calls this; nothing it returns bypasses the checker.
 */
object RephraseVerdicts {
    fun judge(request: RephraseRequest, rawOutput: String): RephraseResult {
        val candidate = RephrasePrompt.cleanOutput(rawOutput, request.surface)
        return when (val v = ClaimChecker.check(request.text, candidate, request.surface)) {
            ClaimChecker.Verdict.Accepted -> RephraseResult.Accepted(ClaimChecker.normalize(candidate))
            ClaimChecker.Verdict.Unchanged -> RephraseResult.Unchanged
            is ClaimChecker.Verdict.Rejected -> RephraseResult.Rejected(candidate, v.reason, v.detail)
        }
    }
}
