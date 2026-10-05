package net.palaya.chessanalyzer.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import net.palaya.chessanalyzer.engine.BundledNetProvider
import net.palaya.chessanalyzer.video.BundledVoiceInstaller
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
        Rule("file", BundledVoiceInstaller.ROOT_DIR_NAME + "/"),
        Rule("file", NarrationStore.DIR_NAME + "/"),
        Rule("file", "eval_cache/"),
        Rule("file", BundledNetProvider.NET_FILENAME),
        Rule("file", BundledNetProvider.NET_FILENAME + BundledNetProvider.PART_SUFFIX),
        Rule("file", "datastore/chess_analyzer_narration_settings.preferences_pb"),
        Rule("sharedpref", "narration_key_fallback_unencrypted.xml"),
        Rule("sharedpref", "narration_secrets.xml"),
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
     * The voice installer stages an extraction in a scratch directory next to the installed model.
     * Both must sit under a directory the rules exclude, or a killed first run could leave a
     * ~158 MB half-extracted directory for Auto Backup to try to upload.
     */
    @Test
    fun theVoiceInstallersScratchAndModelDirectoriesAreCoveredByTheTtsRule() {
        assertEquals("tts_models", BundledVoiceInstaller.ROOT_DIR_NAME)
        assertTrue(BundledVoiceInstaller.SCRATCH_DIR_NAME.isNotBlank())
        assertTrue(BundledVoiceInstaller.MODEL_DIR_NAME.isNotBlank())
        for (rules in listOf(
            excludesUnder("backup_rules.xml", null),
            excludesUnder("data_extraction_rules.xml", "cloud-backup"),
            excludesUnder("data_extraction_rules.xml", "device-transfer"),
        )) {
            // Directory rules are prefixes: "tts_models/" covers everything beneath it.
            assertTrue(Rule("file", BundledVoiceInstaller.ROOT_DIR_NAME + "/") in rules)
        }
    }

    @Test
    fun theNetsScratchFileSuffixIsExcludedByName() {
        assertEquals(".part", BundledNetProvider.PART_SUFFIX)
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
