package net.palaya.chessanalyzer.data.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading and ordering the version strings the three upstream projects use (A4). */
class UpstreamVersionsTest {

    @Test
    fun stockfishTagsAreReadAsNumbers() {
        assertEquals(listOf(19), UpstreamVersions.stockfish("sf_19"))
        assertEquals(listOf(17, 1), UpstreamVersions.stockfish("sf_17.1"))
        assertNull(UpstreamVersions.stockfish("stockfish-dev-20260101-abcdef"))
        assertNull(UpstreamVersions.stockfish("19"))
        assertNull(UpstreamVersions.stockfish("sf_"))
    }

    @Test
    fun sherpaTagsAreSemverWithOrWithoutTheV() {
        assertEquals(listOf(1, 13, 8), UpstreamVersions.semver("v1.13.8"))
        assertEquals(listOf(1, 13, 8), UpstreamVersions.semver("1.13.8"))
        // A pre-release is not a release.
        assertNull(UpstreamVersions.semver("v1.14.0-rc1"))
        assertNull(UpstreamVersions.semver("tts-models"))
    }

    @Test
    fun kokoroVersionsComeFromTheFileName() {
        assertEquals(listOf(0, 19), UpstreamVersions.kokoroInt8("kokoro-int8-en-v0_19.tar"))
        assertEquals(listOf(0, 19), UpstreamVersions.kokoroInt8("kokoro-int8-en-v0_19.tar.bz2"))
        assertEquals(listOf(1, 1), UpstreamVersions.kokoroInt8("kokoro-int8-multi-lang-v1_1.tar.bz2"))
        // The non-int8 builds are a different file; only the kind this app uses counts.
        assertNull(UpstreamVersions.kokoroInt8("kokoro-en-v0_19.tar.bz2"))
        assertNull(UpstreamVersions.kokoroInt8("vits-piper-en_US-amy-low.tar.bz2"))
    }

    @Test
    fun versionsOrderNumericallyNotAsText() {
        assertTrue(UpstreamVersions.compare(listOf(1, 13, 10), listOf(1, 13, 8)) > 0)
        assertTrue(UpstreamVersions.compare(listOf(1, 9), listOf(1, 13)) < 0)
        assertTrue(UpstreamVersions.compare(listOf(20), listOf(19)) > 0)
        // The shorter one is padded with zeros.
        assertEquals(0, UpstreamVersions.compare(listOf(19), listOf(19, 0)))
        assertTrue(UpstreamVersions.compare(listOf(17, 1), listOf(17)) > 0)
        // Kokoro v1.0 is newer than v0.19 although 19 > 1.
        assertTrue(UpstreamVersions.compare(listOf(1, 0), listOf(0, 19)) > 0)
    }

    @Test
    fun labelsAreWhatTheSheetShows() {
        assertEquals("20", UpstreamVersions.dotted(listOf(20)))
        assertEquals("1.14.0", UpstreamVersions.dotted(listOf(1, 14, 0)))
        assertEquals("v0.19", UpstreamVersions.kokoroLabel(listOf(0, 19)))
    }

    /**
     * What the app says it has must be what it is built from: the generated pins and the build's own sources.
     * (The Stockfish tag is read by the build from vendor/STOCKFISH_VERSION.txt, the sherpa-onnx version from the
     * version catalog; this reads the same files again, so the three can never drift apart.)
     */
    @Test
    fun theOurSideComesFromTheBuildsOwnSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "vendor/STOCKFISH_VERSION.txt").isFile }
        val tag = File(root, "vendor/STOCKFISH_VERSION.txt").readLines()
            .firstNotNullOf { Regex("""^\s*Tag:\s*(\S+)""").find(it)?.groupValues?.get(1) }
        assertTrue("the vendored Stockfish tag is one this check can compare: $tag", UpstreamVersions.stockfish(tag) != null)
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        val sherpa = Regex("""sherpaOnnx\s*=\s*"([^"]+)"""").find(catalog)!!.groupValues[1]
        assertTrue("the sherpa-onnx version is a release: $sherpa", UpstreamVersions.semver(sherpa) != null)
        assertTrue(UpstreamVersions.kokoroInt8(GeneratedModelPins.VOICE_TAR_NAME) != null)
        assertEquals(GeneratedModelPins.VOICE_TAR_NAME + ".bz2", GeneratedModelPins.VOICE_UPSTREAM_ARCHIVE_NAME)
    }
}
