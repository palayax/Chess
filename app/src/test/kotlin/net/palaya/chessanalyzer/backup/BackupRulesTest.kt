package net.palaya.chessanalyzer.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.palaya.chessanalyzer.data.PendingAnalysisStore
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore
import net.palaya.chessanalyzer.video.NarrationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Host-side guard for the Auto Backup rules. Parses the real XML files (no device needed), so a
 * renamed directory or net can't silently fall out of the exclusion list and push the app past
 * Auto Backup's 25 MB quota.
 */
class BackupRulesTest {

    private data class Rule(val domain: String, val path: String)

    private fun resFile(name: String): File {
        // Gradle runs unit tests with the module directory as the working directory.
        val f = File("src/main/res/xml/$name")
        return if (f.exists()) f else File("app/src/main/res/xml/$name")
    }

    private fun excludesUnder(file: String, parentTag: String?): List<Rule> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resFile(file))
        val scope: Element = if (parentTag == null) doc.documentElement
        else doc.getElementsByTagName(parentTag).item(0) as Element
        val nodes = scope.getElementsByTagName("exclude")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            Rule(e.getAttribute("domain"), e.getAttribute("path"))
        }
    }

    private val mustExclude = listOf(
        Rule("file", VoiceStore.ROOT_DIR_NAME + "/"),
        // D2b: the downloaded net and its .part live in nets/; models/ holds D2e's activation journal.
        Rule("file", NetStore.DIR_NAME + "/"),
        Rule("file", "models/"),
        Rule("file", NarrationStore.DIR_NAME + "/"),
        Rule("file", "eval_cache/"),
        // Legacy literals (bundled builds kept the net in the filesDir root): kept for one release.
        Rule("file", NetStore.NET_FILENAME),
        Rule("file", NetStore.NET_FILENAME + NetStore.PART_SUFFIX),
        Rule("file", "datastore/chess_analyzer_narration_settings.preferences_pb"),
        Rule("sharedpref", "narration_key_fallback_unencrypted.xml"),
        Rule("sharedpref", "narration_secrets.xml"),
        // F1: the diagnostic log and the pending analysis request are device-specific.
        Rule("file", DiagnosticLog.DIR_NAME + "/"),
        Rule("file", PendingAnalysisStore.FILE_NAME),
        Rule("file", PendingAnalysisStore.FILE_NAME + ".tmp"),
        // D2c: a game shared before setup, waiting for the net (same reason: it would start by itself).
        Rule("file", PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME),
        Rule("file", PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME + ".tmp"),
    )

    @Test
    fun legacyBackupRulesExcludeEverythingLargeOrRegenerable() {
        val rules = excludesUnder("backup_rules.xml", null)
        for (r in mustExclude) assertTrue("backup_rules.xml must exclude $r", r in rules)
    }

    @Test
    fun cloudBackupRulesExcludeEverythingLargeOrRegenerable() {
        val rules = excludesUnder("data_extraction_rules.xml", "cloud-backup")
        for (r in mustExclude) assertTrue("cloud-backup must exclude $r", r in rules)
    }

    @Test
    fun deviceTransferRulesExcludeEverythingLargeOrRegenerable() {
        val rules = excludesUnder("data_extraction_rules.xml", "device-transfer")
        for (r in mustExclude) assertTrue("device-transfer must exclude $r", r in rules)
    }

    /**
     * The voice store stages an extraction in a scratch directory next to the installed model, and
     * downloads the tar into a part file in the same directory. All must sit under a directory the
     * rules exclude, or a killed first run could leave ~158 MB for Auto Backup to try to upload.
     */
    @Test
    fun theVoiceStoresScratchModelAndPartFilesAreCoveredByTheTtsRule() {
        assertEquals("tts_models", VoiceStore.ROOT_DIR_NAME)
        assertTrue(VoiceStore.SCRATCH_DIR_NAME.isNotBlank())
        assertTrue(VoiceStore.MODEL_DIR_NAME.isNotBlank())
        assertFalse("the part file must be a plain name inside tts_models/", VoiceStore.PART_FILE_NAME.contains('/'))
        for (rules in listOf(
            excludesUnder("backup_rules.xml", null),
            excludesUnder("data_extraction_rules.xml", "cloud-backup"),
            excludesUnder("data_extraction_rules.xml", "device-transfer"),
        )) {
            // Directory rules are prefixes: "tts_models/" covers everything beneath it.
            assertTrue(Rule("file", VoiceStore.ROOT_DIR_NAME + "/") in rules)
        }
    }

    @Test
    fun theNetAndItsPartFileLiveUnderTheExcludedNetsDirectory() {
        assertEquals("nets", NetStore.DIR_NAME)
        assertEquals(".part", NetStore.PART_SUFFIX)
        val store = NetStore(File("files"))
        assertEquals(File(File("files"), "nets"), store.netFile.parentFile)
        assertEquals(File(File("files"), "nets"), store.partFileFor().parentFile)
        assertFalse(
            "the old downloader's suffix must not linger in the rules",
            excludesUnder("backup_rules.xml", null).any { it.path.endsWith(".download") },
        )
    }

    @Test
    fun theUsersGamesAreStillBackedUp() {
        for (rules in listOf(
            excludesUnder("backup_rules.xml", null),
            excludesUnder("data_extraction_rules.xml", "cloud-backup"),
            excludesUnder("data_extraction_rules.xml", "device-transfer"),
        )) {
            assertFalse(rules.any { it.path.startsWith("games") })
            assertFalse("the whole files/ root must not be excluded", rules.any { it.path.isEmpty() || it.path == "." || it.path == "/" })
        }
    }

    @Test
    fun cloudBackupAndDeviceTransferListTheSamePaths() {
        assertEquals(
            excludesUnder("data_extraction_rules.xml", "cloud-backup").toSet(),
            excludesUnder("data_extraction_rules.xml", "device-transfer").toSet(),
        )
    }
}
