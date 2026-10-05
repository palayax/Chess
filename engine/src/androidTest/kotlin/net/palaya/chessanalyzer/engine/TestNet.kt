package net.palaya.chessanalyzer.engine

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The verified NNUE net for the engine tests, taken from the bundled assets exactly as the app
 * does it (via [BundledNetProvider]). There is nothing to push to the device and nothing to skip:
 * if the library's own test APK cannot carry the asset, [net] fails loudly.
 *
 * `:engine`'s androidTest "target context" IS the test APK's context (this is a library), so its
 * assets are the ones `:engine` merges from `vendor/models/engine-assets` for the test APK.
 */
internal object TestNet {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** One provider for the whole test run, so the file is hashed once, not once per test. */
    private val provider: BundledNetProvider by lazy { BundledNetProvider(context.filesDir, context.assets) }

    suspend fun net(): File = provider.ensureNet()
}
