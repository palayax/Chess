package net.palaya.chessanalyzer.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A corrupt DataStore file must reset to defaults, not crash the app at launch.
 *
 * Both repositories hand [resetOnCorruption] to `preferencesDataStore(...)`; this builds a real
 * DataStore over a real file with that same handler, writes garbage over it and reads. The control
 * test proves the garbage really is unparseable (otherwise the passing tests below would prove
 * nothing), and the source-scan test makes sure no store is declared without the handler.
 */
class DataStoreCorruptionTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "dscorrupt-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun retire(scope: CoroutineScope) = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    private val depth = intPreferencesKey("depth")
    private val username = stringPreferencesKey("username")

    /** Bytes that are not a valid preferences protobuf: a varint that never terminates. */
    private fun corruptFile(name: String): File = File(dir, name).apply {
        writeBytes(ByteArray(64) { 0xFF.toByte() })
    }

    @Test
    fun controlGarbageReallyIsCorruptWithoutAHandler() {
        val file = corruptFile("control.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            runBlocking { store.data.first() }
            fail("expected the garbage file to be rejected; the test file is not actually corrupt")
        } catch (expected: CorruptionException) {
            // This is the launch crash the handler exists to prevent.
        }
    }

    @Test
    fun corruptFileResetsToDefaultsInsteadOfThrowing() {
        val file = corruptFile("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = resetOnCorruption("test"),
            scope = scope,
            produceFile = { file },
        )
        val prefs = runBlocking { store.data.first() }
        assertNull(prefs[depth])
        assertNull(prefs[username])
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun theStoreIsUsableAgainAfterTheReset() {
        val file = corruptFile("settings2.preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = resetOnCorruption("test"),
            scope = scope,
            produceFile = { file },
        )
        runBlocking {
            store.data.first() // triggers the reset
            store.edit { it[depth] = 18; it[username] = "dor" }
        }
        // DataStore allows one live instance per file, so retire the first before reopening.
        retire(scope)
        // A second store over the same file sees what was written: the replacement file is valid.
        val reopened = PreferenceDataStoreFactory.create(
            corruptionHandler = resetOnCorruption("test"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { file },
        )
        val prefs = runBlocking { reopened.data.first() }
        assertEquals(18, prefs[depth])
        assertEquals("dor", prefs[username])
    }

    @Test
    fun aHealthyFileIsNeverReset() {
        val file = File(dir, "healthy.preferences_pb")
        val first = PreferenceDataStoreFactory.create(
            corruptionHandler = resetOnCorruption("test"),
            scope = scope,
            produceFile = { file },
        )
        runBlocking { first.edit { it[depth] = 22 } }
        retire(scope)
        val second = PreferenceDataStoreFactory.create(
            corruptionHandler = resetOnCorruption("test"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { file },
        )
        assertEquals(22, runBlocking { second.data.first() }[depth])
    }

    @Test
    fun everyDataStoreDeclaredInTheAppUsesTheHandler() {
        val root = File("src/main/kotlin").takeIf { it.exists() } ?: File("app/src/main/kotlin")
        val declarations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DataStoreDefaults.kt" }
            .flatMap { file ->
                // The declaration plus the lines of its argument list, up to the closing paren.
                val text = file.readText()
                Regex("""preferencesDataStore\(([^)]*)\)""").findAll(text)
                    .map { file.name to it.groupValues[1] }
            }
            .toList()
        assertTrue("expected to find the app's DataStore declarations", declarations.size >= 2)
        declarations.forEach { (file, args) ->
            assertTrue("$file declares a DataStore without corruptionHandler", args.contains("corruptionHandler"))
            assertTrue("$file must use resetOnCorruption()", args.contains("resetOnCorruption("))
        }
        assertFalse(declarations.isEmpty())
    }
}
