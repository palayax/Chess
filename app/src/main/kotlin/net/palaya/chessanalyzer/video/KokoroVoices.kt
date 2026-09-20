package net.palaya.chessanalyzer.video

/**
 * The eleven English speakers baked into `kokoro-int8-en-v0_19`'s `voices.bin`, in the order
 * sherpa-onnx addresses them (`sid` 0..10).
 *
 * The ids are the contract — [NeuralTtsProvider] passes one straight to
 * `OfflineTts.generate(sid = ...)`. The *names* are documentation, taken from upstream's model
 * page for this exact archive (https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html);
 * nothing in the archive itself labels the vectors, so the names cannot be verified on-device.
 * What *is* verified on-device is the count: [NeuralTtsProvider.prepare] logs
 * `OfflineTts.numSpeakers()` and refuses to load a speaker id outside it.
 *
 * `af` (id 0) is an averaged blend of two other voices rather than a speaker in its own right,
 * which is the main reason "just use 0" is the wrong default — see
 * [net.palaya.chessanalyzer.ui.model.KOKORO_DEFAULT_SPEAKER_ID].
 */
object KokoroVoices {
    /** Index = sid. */
    val NAMES: List<String> = listOf(
        "af",
        "af_bella",
        "af_nicole",
        "af_sarah",
        "af_sky",
        "am_adam",
        "am_michael",
        "bf_emma",
        "bf_isabella",
        "bm_george",
        "bm_lewis",
    )

    /** How many speakers this archive is expected to expose — asserted against the loaded model. */
    const val EXPECTED_SPEAKER_COUNT: Int = 11

    /** Documentation label for [sid], or a plain id string if it is outside the known range. */
    fun nameFor(sid: Int): String = NAMES.getOrNull(sid) ?: "sid$sid"
}
