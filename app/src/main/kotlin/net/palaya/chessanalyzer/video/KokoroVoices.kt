package net.palaya.chessanalyzer.video

/**
 * The eleven English speakers baked into `kokoro-int8-en-v0_19`'s `voices.bin` (5,755,904 B =
 * 11 x 523,264 B, one 511 x 256 float32 style table each), in the order sherpa-onnx addresses them (`sid` 0..10). V1 offers all of them in
 * Settings, Video, "Narrator voice"; nothing extra is downloaded.
 *
 * The ids are the contract: [NeuralTtsProvider] passes one straight to `OfflineTts.generate(sid = ...)`.
 * The upstream names (`af_bella`...) come from sherpa-onnx's page for this exact archive
 * (https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html; the archive's own README
 * only links to hexgrad/Kokoro-82M). The prefix is upstream's: `a`/`b` = American/British English,
 * `f`/`m` = female/male voice. Nothing in the archive labels the vectors, so the names cannot be
 * verified on-device; what IS verified on-device is the count ([EXPECTED_SPEAKER_COUNT], asserted
 * against `OfflineTts.numSpeakers()`), and the V1 host measurement shows the eleven sound different
 * (pitch and spectral centroid per speaker, RUN_LOG V1).
 *
 * `af` (id 0) is an averaged blend of two others (upstream: Bella and Sarah) rather than a speaker of its
 * own; it is offered last among the American voices as "Blend".
 */
object KokoroVoices {

    enum class Accent { AMERICAN, BRITISH }
    enum class Gender { FEMALE, MALE }

    /**
     * One speaker. [displayName] is the name the picker shows (null = the blend, labelled by a string
     * resource). [measuredWpm] is the rate this speaker was measured at in `docs/voice_samples/`
     * (`measurements.txt`, the 36-word chess paragraph at `length_scale` 1.20), inverted from
     * `estimateSpeechMs` the same way as `NeuralVoiceTier.measuredWpm`: the script's timeline and its
     * length budget are estimated before any audio exists, so the estimate must describe the voice
     * that will speak (Nicole speaks the paragraph in 17.9 s, Sky in 13.1 s).
     */
    data class Speaker(
        val sid: Int,
        val upstreamName: String,
        val displayName: String?,
        val accent: Accent,
        val gender: Gender,
        val measuredWpm: Int,
    )

    /** Index = sid. */
    val SPEAKERS: List<Speaker> = listOf(
        Speaker(0, "af", null, Accent.AMERICAN, Gender.FEMALE, 166),
        Speaker(1, "af_bella", "Bella", Accent.AMERICAN, Gender.FEMALE, 169),
        Speaker(2, "af_nicole", "Nicole", Accent.AMERICAN, Gender.FEMALE, 127),
        Speaker(3, "af_sarah", "Sarah", Accent.AMERICAN, Gender.FEMALE, 164),
        Speaker(4, "af_sky", "Sky", Accent.AMERICAN, Gender.FEMALE, 177),
        Speaker(5, "am_adam", "Adam", Accent.AMERICAN, Gender.MALE, 173),
        Speaker(6, "am_michael", "Michael", Accent.AMERICAN, Gender.MALE, 153),
        Speaker(7, "bf_emma", "Emma", Accent.BRITISH, Gender.FEMALE, 170),
        Speaker(8, "bf_isabella", "Isabella", Accent.BRITISH, Gender.FEMALE, 167),
        Speaker(9, "bm_george", "George", Accent.BRITISH, Gender.MALE, 149),
        Speaker(10, "bm_lewis", "Lewis", Accent.BRITISH, Gender.MALE, 158),
    )

    /** Upstream names, index = sid (kept for the logs and the sample sweep). */
    val NAMES: List<String> = SPEAKERS.map { it.upstreamName }

    /**
     * The picker's order: American female, American male, British female, British male, and within a
     * group by upstream's published grade (hexgrad/Kokoro-82M VOICES.md: Bella A-, Nicole B-, Sarah C+,
     * Sky C-; Michael C+, Adam F+; Emma B-, Isabella C; George C, Lewis D+), the blend last of its group.
     */
    val PICKER_ORDER: List<Int> = listOf(1, 2, 3, 4, 0, 6, 5, 7, 8, 9, 10)

    /** How many speakers this archive is expected to expose — asserted against the loaded model. */
    const val EXPECTED_SPEAKER_COUNT: Int = 11

    fun isKnown(sid: Int): Boolean = sid in SPEAKERS.indices

    fun speaker(sid: Int): Speaker? = SPEAKERS.getOrNull(sid)

    /** Documentation label for [sid], or a plain id string if it is outside the known range. */
    fun nameFor(sid: Int): String = NAMES.getOrNull(sid) ?: "sid$sid"
}
