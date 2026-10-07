package net.palaya.chessanalyzer.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every rule of design §3.3 as built (D2e), with the app's real pins. */
class ModelCompatibilityTest {

    private val base = "https://github.com/palayax/palaya-chess/releases/download/"
    private val netSha = "1a298aa575a0" + "c".repeat(52)
    private val newNetSha = "abcdef012345" + "d".repeat(52)
    private val voiceSha = "e".repeat(64)

    private val facts = AppFacts(
        versionCode = 3,
        netVersion = 0x6a448afaL,
        netArchHash = 0xa85b2205L,
        sherpaOnnxVersion = "1.13.8",
        voiceLayout = "kokoro-v0_19",
        baseUrl = base,
        allowCleartextLoopback = false,
        installedNetSha256 = netSha,
        installedVoiceSha256 = "f".repeat(64),
    )

    private fun net(
        sha: String = newNetSha,
        name: String = "nn-${sha.take(12)}.nnue",
        size: Long = 98_511_183,
        version: Long = 0x6a448afaL,
        arch: Long = 0xa85b2205L,
        url: String = "${base}models-2026.11/$name",
        min: Int = 1,
        max: Int? = null,
        compat: ModelCompat = ModelCompat.StockfishNnue(version, arch, "sf_19"),
    ) = ManifestEntry(ModelKind.NET, "Chess engine data", name.removeSuffix(".nnue"), name, url, size, sha, min, max, compat, null)

    private fun voice(
        sha: String = voiceSha,
        name: String = "kokoro-int8-en-v0_19-r2.tar",
        size: Long = 158_279_680,
        layout: String = "kokoro-v0_19",
        runtime: ModelRuntime? = ModelRuntime("sherpa-onnx", "1.13.8", "1.13.8"),
        url: String = "${base}models-2026.11/$name",
        min: Int = 1,
        tarSha: String? = null,
        tarSize: Long? = null,
    ) = ManifestEntry(ModelKind.VOICE, "Narration voice", "v0_19-r2", name, url, size, sha, min, null, ModelCompat.SherpaKokoro(layout), runtime, tarSha, tarSize)

    private fun reason(e: ManifestEntry, f: AppFacts = facts): Incompatibility? =
        (ModelCompatibility.evaluate(e, f) as? CompatVerdict.Incompatible)?.reason

    @Test
    fun compatibleEntriesAreOffered() {
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(net(), facts))
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(voice(), facts))
    }

    @Test
    fun theInstalledFilesAreNotOfferedAgain() {
        assertEquals(CompatVerdict.AlreadyInstalled, ModelCompatibility.evaluate(net(sha = netSha), facts))
        assertEquals(CompatVerdict.AlreadyInstalled, ModelCompatibility.evaluate(voice(sha = "f".repeat(64)), facts))
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(net(sha = netSha), facts.copy(installedNetSha256 = null)))
    }

    @Test
    fun versionCodeBoundsAreInclusive() {
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(net(min = 3), facts))
        assertEquals(Incompatibility.APP_TOO_OLD, reason(net(min = 4)))
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(net(max = 3), facts))
        assertEquals(Incompatibility.APP_TOO_NEW, reason(net(max = 2)))
        assertEquals(Incompatibility.APP_TOO_OLD, reason(voice(min = 99)))
    }

    @Test
    fun aWrongArchitectureOrVersionNetIsNeverOffered() {
        assertEquals(Incompatibility.NET_ARCH, reason(net(arch = 0xdeadbeefL)))
        assertEquals(Incompatibility.NET_ARCH, reason(net(arch = 0xa85b2204L)))
        assertEquals(Incompatibility.NET_VERSION, reason(net(version = 0x7af32f20L)))
        assertEquals(Incompatibility.WRONG_COMPAT_KIND, reason(net(compat = ModelCompat.Other("lc0"))))
        assertEquals(Incompatibility.WRONG_COMPAT_KIND, reason(net(compat = ModelCompat.SherpaKokoro("kokoro-v0_19"))))
    }

    @Test
    fun aNetsNameMustEncodeItsHashAndMatchItsUrl() {
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(net(name = "nn-000000000000.nnue")))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(net(name = "network.nnue")))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(net(url = "${base}models-2026.11/other.nnue")))
    }

    @Test
    fun sizesOutsideThePlausibleRangeAreRefused() {
        assertEquals(Incompatibility.SIZE_OUT_OF_RANGE, reason(net(size = 49_999_999)))
        assertEquals(Incompatibility.SIZE_OUT_OF_RANGE, reason(net(size = 400_000_001)))
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(net(size = 50_000_000), facts))
        assertEquals(Incompatibility.SIZE_OUT_OF_RANGE, reason(voice(size = 19_999_999)))
        assertEquals(Incompatibility.SIZE_OUT_OF_RANGE, reason(voice(size = 600_000_001)))
    }

    @Test
    fun urlsMustBeHttpsAndUnderTheAppsBaseUrl() {
        assertEquals(Incompatibility.INSECURE_URL, reason(net(url = "http://github.com/palayax/palaya-chess/releases/download/x/nn-abcdef012345.nnue")))
        assertEquals(Incompatibility.INSECURE_URL, reason(net(url = "ftp://x/nn-abcdef012345.nnue")))
        assertEquals(Incompatibility.FOREIGN_URL, reason(net(url = "https://evil.example/releases/download/x/nn-abcdef012345.nnue")))
        assertEquals(Incompatibility.FOREIGN_URL, reason(net(url = "${base}x/../../nn-abcdef012345.nnue")))
        assertEquals(Incompatibility.FOREIGN_URL, reason(net(url = "${base}x/nn-abcdef012345.nnue?redirect=1")))
        // Debug builds: http to the loopback test hosts, under the (debug) base URL.
        val debug = facts.copy(baseUrl = "http://10.0.2.2:8787/", allowCleartextLoopback = true)
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(net(url = "http://10.0.2.2:8787/t/nn-abcdef012345.nnue"), debug))
        assertEquals(Incompatibility.INSECURE_URL, reason(net(url = "http://10.0.2.3:8787/t/nn-abcdef012345.nnue"), debug.copy(baseUrl = "http://10.0.2.3:8787/")))
        assertEquals(Incompatibility.INSECURE_URL, reason(net(url = "http://10.0.2.2:8787/t/nn-abcdef012345.nnue"), debug.copy(allowCleartextLoopback = false)))
    }

    @Test
    fun aVoiceNeedsThisLayoutAndAMatchingRuntime() {
        assertEquals(Incompatibility.VOICE_LAYOUT, reason(voice(layout = "kokoro-multi-lang-v1_0")))
        assertEquals(Incompatibility.VOICE_RUNTIME, reason(voice(runtime = ModelRuntime("sherpa-onnx", "1.14.0", "1.15.0"))))
        assertEquals(Incompatibility.VOICE_RUNTIME, reason(voice(runtime = ModelRuntime("sherpa-onnx", "1.12.0", "1.13.7"))))
        assertEquals(Incompatibility.VOICE_RUNTIME, reason(voice(runtime = ModelRuntime("piper", "1.13.8", "1.13.8"))))
        assertEquals(Incompatibility.VOICE_RUNTIME, reason(voice(runtime = null)))
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(voice(runtime = ModelRuntime("sherpa-onnx", "1.13.0", "1.13.10")), facts))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(voice(name = "kokoro.tar.bz2")))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(voice(name = "../kokoro.tar", url = "${base}models-2026.11/kokoro.tar")))
        assertEquals(Incompatibility.FOREIGN_URL, reason(voice(name = "../kokoro.tar")))
    }

    @Test
    fun aGzippedVoiceMustCarryItsTarPinsAndIsJudgedInstalledByTheTar() {
        // D2f: publish_models.sh ships the voice as .tar.gz with tarSha256/tarSize.
        val gzName = "kokoro-int8-en-v0_19-r2.tar.gz"
        val gz = voice(name = gzName, size = 102_553_000, tarSha = "a".repeat(64), tarSize = 158_279_680)
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(gz, facts))
        assertTrue(gz.isGzip)
        assertEquals("a".repeat(64), gz.unpackedSha256)
        assertEquals(158_279_680L, gz.unpackedSizeBytes)
        // "Already installed" compares the TAR with the marker, never the .tar.gz's own hash.
        assertEquals(CompatVerdict.AlreadyInstalled, ModelCompatibility.evaluate(gz.copy(tarSha256 = "f".repeat(64)), facts))
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(gz.copy(sha256 = "f".repeat(64)), facts))
        // Without its tar pins a .tar.gz cannot be checked or recognised: not offered.
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(gz.copy(tarSha256 = null)))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(gz.copy(tarSizeBytes = null)))
        // A plain tar is its own tar: tar fields, when present, must agree with the file's.
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(voice(tarSha = voiceSha, tarSize = 158_279_680), facts))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(voice(tarSha = "a".repeat(64))))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(voice(tarSize = 1)))
        // Both sizes are bounded: a small .tar.gz claiming a huge tar is refused.
        assertEquals(Incompatibility.SIZE_OUT_OF_RANGE, reason(gz.copy(tarSizeBytes = 700L * 1024 * 1024)))
        assertEquals(Incompatibility.SIZE_OUT_OF_RANGE, reason(gz.copy(sizeBytes = 1_000)))
        assertEquals(Incompatibility.BAD_FILE_NAME, reason(voice(name = "kokoro.tgz")))
    }

    @Test
    fun dottedVersionsCompareAsNumbers() {
        assertTrue(ModelCompatibility.versionInRange("1.13.8", "1.13.8", "1.13.8"))
        assertTrue(ModelCompatibility.versionInRange("1.13.10", "1.13.8", "1.14"))
        assertFalse(ModelCompatibility.versionInRange("1.13.10", "1.13.8", "1.13.9"))
        assertTrue(ModelCompatibility.versionInRange("1.13", "1.13.0", "1.13.0"))
        assertFalse(ModelCompatibility.versionInRange("1.13.8-rc1", "1.0", "2.0"))
        assertFalse(ModelCompatibility.versionInRange("1.13.8", "abc", "2.0"))
        assertEquals(1, ModelCompatibility.compareVersions("1.10", "1.9"))
    }
}
