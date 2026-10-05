package net.palaya.chessanalyzer.desktop.work

import java.security.MessageDigest

/**
 * The producer's pipeline stages in execution order (docs/PC_PRODUCER_DESIGN.md §3).
 *
 * @param phase the build phase (§14) that implements the stage; used for the
 *   "not implemented yet" message until then.
 * @param configVersion bumped whenever the stage's code changes the meaning of its output, so
 *   cached artifacts from older code are recomputed (it is part of the input fingerprint).
 */
enum class Stage(val cliName: String, val phase: String, val implemented: Boolean, val configVersion: Int) {
    // ANALYZE v2: per-eval `capped` flag in analysis.json; threads/hash joined the fingerprint.
    ANALYZE("analyze", "P0", implemented = true, configVersion = 2),
    STORYBOARD("storyboard", "P1", implemented = true, configVersion = 1),
    SCRIPT("script", "P1", implemented = true, configVersion = 1),
    AUDIO("audio", "P1", implemented = true, configVersion = 1),
    RENDER("render", "P1", implemented = true, configVersion = 1),
    MIX("mix", "P1", implemented = true, configVersion = 1);

    fun notImplementedMessage(): String = "stage $name: not implemented yet (phase $phase)"

    companion object {
        fun parse(name: String): Stage? =
            entries.firstOrNull { it.cliName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }

        /**
         * The input fingerprint of a stage: sha256 over its name, config version and every input
         * part (upstream artifact hashes plus the option values the stage depends on). Parts are
         * length-prefixed so ("ab","c") and ("a","bc") cannot collide.
         */
        fun fingerprint(stage: Stage, vararg parts: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            fun feed(s: String) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                md.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
                md.update(bytes)
            }
            feed(stage.name)
            feed(stage.configVersion.toString())
            parts.forEach(::feed)
            return md.digest().toHex()
        }
    }
}

/** Why a stage will or will not run; printed in the run log. */
data class StageDecision(val run: Boolean, val reason: String)

/**
 * The §3 rule: run when the output is missing, when the recorded fingerprint differs from the
 * current one, or when `--force` / `--from` says so.
 */
fun decideStage(
    stage: Stage,
    outputExists: Boolean,
    recorded: StageRecord?,
    currentFingerprint: String,
    force: Boolean,
    from: Stage?,
): StageDecision = when {
    force -> StageDecision(true, "--force")
    from != null && stage.ordinal >= from.ordinal -> StageDecision(true, "--from ${from.cliName}")
    !outputExists -> StageDecision(true, "no cached output")
    recorded == null -> StageDecision(true, "no stages.json record")
    recorded.inputFingerprint != currentFingerprint -> StageDecision(true, "inputs changed")
    else -> StageDecision(false, "cached (fingerprint ${currentFingerprint.take(12)})")
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
