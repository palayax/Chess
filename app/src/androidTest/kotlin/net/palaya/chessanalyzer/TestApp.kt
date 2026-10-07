package net.palaya.chessanalyzer

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.data.models.GeneratedModelPins
import net.palaya.chessanalyzer.engine.NetStore

/**
 * Shared access to the app's own process-lifetime services, exactly as the app wires them.
 *
 * Since D2b the models are not in the app APK: a user's first launch downloads them. A test that needs
 * the net or the voice gets them through [ensureSetUp], which installs them from the TEST APK's assets
 * through the same store tails the download uses ([NetStore.installVerified],
 * [net.palaya.chessanalyzer.video.VoiceStore.installFromStream]), and fails loudly if it cannot.
 *
 * The seed assets (`nnue/<net>`, `tts/<voice tar>`) come from vendor/models/, wired in as this test
 * APK's androidTest assets by `app/build.gradle.kts` (D2d; stored uncompressed, ~257 MB). They are never
 * in the app APK, and must not be put back there to make a test pass.
 *
 * Tests that open the UI at the nav host: a fresh process opens on Setup while the net is missing
 * (D2c). A test that expects Home calls [ensureSetUp] first; `SetupGateInstrumentedTest` tests the gate.
 */
object TestApp {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The test APK's own context: its assets carry the seed copies of the two model files (D2d). */
    val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    val app: ChessAnalyzerApplication get() = context.applicationContext as ChessAnalyzerApplication

    /** An [AnalysisService] wired to the app's single engine and repository. */
    fun analysisService(): AnalysisService =
        AnalysisService(context, app.engineController, app.gameRepository)

    /** The seed asset paths inside the test APK. */
    val netSeedPath: String get() = "nnue/${NetStore.NET_FILENAME}"
    /**
     * The voice seed: the exact `.tar.gz` setup downloads (D2f), stored as `<name>.seed` because AAPT gunzips an
     * asset whose name ends in ".gz" (app/build.gradle.kts, prepareTestSeedAssets).
     */
    val voiceSeedPath: String get() = "tts/${GeneratedModelPins.VOICE_FILE_NAME}.seed"

    fun openSeed(path: String): InputStream = try {
        testContext.assets.open(path)
    } catch (e: FileNotFoundException) {
        throw AssertionError("model file '$path' is not seeded into the test APK (run scripts/fetch_models.sh; app/build.gradle.kts wires vendor/models/ in as androidTest assets)", e)
    }

    /** Installs the net and the voice from the test APK if they are not installed yet. */
    suspend fun ensureSetUp() {
        val netStore = app.netStore
        // A test that activated an update's net and did not put the compiled one back (D2e) must not
        // leave the rest of the suite on it.
        if (netStore.hasUpdateRecord() && netStore.verifiedNetOrNull() == null) netStore.setActiveIdentity(netStore.compiledIdentity())
        if (netStore.verifiedNetOrNull() == null) {
            val part = netStore.partFileFor()
            part.parentFile?.mkdirs()
            openSeed(netSeedPath).use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.length() == NetStore.NET_SIZE_BYTES && NetStore.sha256Of(part) == NetStore.NET_SHA256) {
                "the seeded net does not match its pins"
            }
            netStore.installVerified(part)
        }
        val voiceStore = app.voiceStore
        if (!voiceStore.isInstalled()) {
            voiceStore.installFromStream(
                open = { openSeed(voiceSeedPath) },
                expectedSha256 = GeneratedModelPins.VOICE_SHA256,
                expectedSizeBytes = GeneratedModelPins.VOICE_SIZE_BYTES,
            )
        }
        // The real stores, not app.modelSetup: a setup test may have pointed that at a scratch directory.
        check(netStore.verifiedNetOrNull() != null && voiceStore.isInstalled()) { "setup is not complete after seeding" }
    }

    /** The installed Kokoro model directory, installing it from the test APK first if needed. */
    suspend fun installedVoiceDir(): File {
        ensureSetUp()
        check(app.voiceStore.isInstalled()) { "the voice is not installed after setup" }
        return app.voiceStore.modelDir
    }
}
