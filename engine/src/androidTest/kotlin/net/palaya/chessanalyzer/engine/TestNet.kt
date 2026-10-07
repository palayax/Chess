package net.palaya.chessanalyzer.engine

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException

/**
 * The verified NNUE net for the engine tests, installed into the test package's filesDir through
 * [NetStore.installVerified], the same tail the app's download uses. Since D2b the net is not in any
 * APK; the test APK carries a seed copy as an asset (`nnue/<name>`), and if it cannot, [net] fails
 * loudly (nothing is skipped).
 *
 * The seed is wired in by `engine/build.gradle.kts` (D2d): `vendor/models/engine-assets` is this
 * module's androidTest asset directory, stored uncompressed, and `checkTestSeedAssets` stops the build if
 * `scripts/fetch_models.sh` was never run. It never reaches the app.
 *
 * `:engine`'s androidTest "target context" IS the test APK's context (this is a library).
 */
internal object TestNet {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** One store for the whole test run, so the file is hashed once, not once per test. */
    private val store: NetStore by lazy { NetStore(context.filesDir) }

    suspend fun net(): File {
        store.verifiedNetOrNull()?.let { return it }
        val part = store.partFileFor()
        part.parentFile?.mkdirs()
        val input = try {
            context.assets.open("nnue/${NetStore.NET_FILENAME}")
        } catch (e: FileNotFoundException) {
            throw AssertionError("the net is not seeded into the :engine test APK (run scripts/fetch_models.sh)", e)
        }
        input.use { i -> part.outputStream().use { i.copyTo(it) } }
        check(part.length() == NetStore.NET_SIZE_BYTES && NetStore.sha256Of(part) == NetStore.NET_SHA256) {
            "the seeded net does not match its pins"
        }
        return store.installVerified(part)
    }
}
