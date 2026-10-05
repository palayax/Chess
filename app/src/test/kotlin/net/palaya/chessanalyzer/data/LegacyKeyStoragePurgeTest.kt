package net.palaya.chessanalyzer.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stale-key cleanup for phones that ran a build with the removed Google Cloud voice. */
class LegacyKeyStoragePurgeTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun prefs(name: String) = File(tmp.root, name)

    @Test
    fun deletesThePlaintextFallbackAndTheEncryptedKeyFilesAndTheirBackups() {
        val doomed = listOf(
            "narration_key_fallback_unencrypted.xml",
            "narration_key_fallback_unencrypted.xml.bak",
            "narration_secrets.xml",
            "narration_secrets.xml.bak",
        ).map { prefs(it).apply { writeText("<map><string name=\"narration_api_key\">AIza-not-a-real-key</string></map>") } }

        assertTrue(LegacyKeyStoragePurge.deleteLegacyPrefsFiles(tmp.root))

        doomed.forEach { assertFalse("${it.name} must be gone", it.exists()) }
    }

    @Test
    fun leavesUnrelatedPrefsAlone() {
        val keep = listOf("engine_prefs.xml", "other.xml").map { prefs(it).apply { writeText("<map/>") } }
        prefs("narration_secrets.xml").writeText("<map/>")

        assertTrue(LegacyKeyStoragePurge.deleteLegacyPrefsFiles(tmp.root))

        keep.forEach { assertTrue("${it.name} must survive", it.exists()) }
    }

    @Test
    fun isIdempotentAndReportsNothingFoundOnACleanDirectory() {
        assertFalse(LegacyKeyStoragePurge.deleteLegacyPrefsFiles(tmp.root))
        prefs("narration_key_fallback_unencrypted.xml").writeText("<map/>")
        assertTrue(LegacyKeyStoragePurge.deleteLegacyPrefsFiles(tmp.root))
        assertFalse("second run finds nothing", LegacyKeyStoragePurge.deleteLegacyPrefsFiles(tmp.root))
    }

    @Test
    fun aMissingDirectoryIsNotAnError() {
        assertFalse(LegacyKeyStoragePurge.deleteLegacyPrefsFiles(File(tmp.root, "no_such_dir")))
    }

    @Test
    fun namesMatchTheFilesTheRemovedCodeWrote() {
        assertEquals(
            listOf("narration_key_fallback_unencrypted", "narration_secrets"),
            LegacyKeyStoragePurge.LEGACY_PREFS_NAMES,
        )
        assertEquals("_androidx_security_master_key_", LegacyKeyStoragePurge.LEGACY_MASTER_KEY_ALIAS)
    }
}
