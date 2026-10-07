package net.palaya.chessanalyzer.data.models

import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import net.palaya.chessanalyzer.engine.NetNotInstalledException
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Check for updates" and the install that follows, end to end on the host (D2e): a real
 * [ModelDownloader] against [FaultHttpServer], manifests signed with a TEST key, real stores, the real
 * [ModelActivator] with a fake engine and voice trial. The trust and compatibility rules are checked
 * where they act: what is requested from the server, what is offered, what is ever downloaded.
 */
class UpdateCheckerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tag = "models-2026.11"
    private val keys = TestManifests.newKeyPair()
    private lateinit var server: FaultHttpServer
    private lateinit var files: File
    private var network = NetworkCost.UNMETERED

    private val oldNet = TestModelFiles.netBytes(size = 1_100_000, seed = 7)
    private val pins = TestModelFiles.pinsFor(oldNet)
    private val newNet = TestModelFiles.netBytes(size = 1_100_000, seed = 8)
    private val newSha = TestModelFiles.sha256(newNet)
    private val newName = "nn-${newSha.take(12)}.nnue"
    private val foreignNet = TestModelFiles.netBytes(size = 1_100_000, seed = 9, arch = 0xdeadbeefL)
    private val foreignSha = TestModelFiles.sha256(foreignNet)
    private val foreignName = "nn-${foreignSha.take(12)}.nnue"
    private val oldTar = TestModelFiles.voiceTar()
    private val newTar = TestModelFiles.voiceTar(payload = 220_000)
    private val newTarSha = TestModelFiles.sha256(newTar)
    private val tarName = "kokoro-int8-en-v0_19-r2.tar"

    private val loads = ArrayList<String>()
    private val engine = object : NetActivationGate {
        override suspend fun <T> exclusive(block: suspend () -> T): T = block()
        override suspend fun trialLocked(): String {
            val f = netStore().verifiedNetOrNull() ?: throw NetNotInstalledException()
            loads += f.name
            return "ok"
        }
    }

    private val small = SizeLimits(netMin = 1_000_000, netMax = 2_000_000, voiceMin = 1, voiceMax = 1_000_000)

    private fun netStore() = NetStore(files, pins)
    private fun voiceStore() = TestModelFiles.voiceStoreFor(files, oldTar)
    private fun facts() = AppFacts(
        versionCode = 1,
        netVersion = TestModelFiles.NET_VERSION,
        netArchHash = TestModelFiles.NET_ARCH,
        sherpaOnnxVersion = "1.13.8",
        voiceLayout = VoiceStore.LAYOUT,
        baseUrl = server.baseUrl,
        allowCleartextLoopback = true,
        installedNetSha256 = netStore().activeNetOrNull()?.let { netStore().activeIdentity().sha256 },
        installedVoiceSha256 = voiceStore().installedSha256(),
        limits = small,
    )

    private fun downloader() = ModelDownloader("test", allowCleartextLoopback = true, connectTimeoutMs = 2_000, readTimeoutMs = 2_000, sleep = {})

    private fun checker(publicKey: ByteArray = keys.public.encoded) = UpdateChecker(
        downloader = downloader(),
        manifestUrl = server.url("models/models.json"),
        publicKeyDer = publicKey,
        networkStatus = { network },
        facts = { facts() },
    )

    private fun installer() = ModelUpdateInstaller(
        downloader = downloader(),
        activator = ModelActivator(netStore(), voiceStore(), ActivationJournal(files), engine, { VoiceTrialResult(true, "ok") }, { false }, {}, {}),
        netStore = netStore(),
        voiceStore = voiceStore(),
        facts = { facts() },
        freeBytes = { Long.MAX_VALUE },
    )

    private fun publish(manifest: ByteArray, signature: ByteArray? = TestManifests.sign(manifest, keys.private)) {
        server.serve("models/models.json", manifest)
        if (signature != null) server.serve("models/models.json.sig", signature)
    }

    private fun netEntry(name: String = newName, sha: String = newSha, size: Int = newNet.size, arch: String = "a85b2205") =
        TestManifests.netEntry(server.baseUrl, tag, name, size.toLong(), sha, archHash = arch)

    private fun voiceEntry() = TestManifests.voiceEntry(server.baseUrl, tag, tarName, newTar.size.toLong(), newTarSha)

    private fun paths() = server.requests.map { it.path }

    @Before
    fun setUp() = runBlocking {
        files = tmp.newFolder("files")
        server = FaultHttpServer()
            .serve("$tag/$newName", newNet)
            .serve("$tag/$foreignName", foreignNet)
            .serve("$tag/$tarName", newTar)
        val s = netStore()
        s.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(oldNet) }
        s.installVerified(s.partFileFor())
        voiceStore().installFromStream({ oldTar.inputStream() }, TestModelFiles.sha256(oldTar), oldTar.size.toLong())
        Unit
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun aCheckMakesExactlyTwoRequestsAndOffersTheCompatibleFiles() = runBlocking {
        publish(TestManifests.manifest(netEntry(), voiceEntry()))
        val r = checker().check()
        assertEquals(listOf("/models/models.json", "/models/models.json.sig"), paths())
        r as UpdateCheckResult.Available
        assertEquals(listOf(ModelKind.NET, ModelKind.VOICE), r.offers.map { it.kind })
        assertTrue("no model file is requested by a check", paths().none { it.startsWith("/$tag/") })
    }

    @Test
    fun aWrongArchitectureNetIsNeitherOfferedNorDownloaded() = runBlocking {
        publish(TestManifests.manifest(netEntry(name = foreignName, sha = foreignSha, arch = "deadbeef"), voiceEntry()))
        val r = checker().check() as UpdateCheckResult.Available
        assertEquals(listOf(ModelKind.VOICE), r.offers.map { it.kind })
        val refused = r.notOffered.single()
        assertEquals(Incompatibility.NET_ARCH, (refused.verdict as CompatVerdict.Incompatible).reason)

        // Even handed straight to the installer, the entry is judged again and nothing is requested.
        val parsed = (ModelManifest.parse(TestManifests.manifest(netEntry(name = foreignName, sha = foreignSha, arch = "deadbeef"))) as ManifestParse.Valid).manifest
        val before = server.requests.size
        val outcome = installer().install(UpdateOffer(parsed.entries.single()))
        assertEquals(UpdateFailure.INCOMPATIBLE, (outcome as UpdateInstallOutcome.Failed).reason)
        assertEquals("no request for an incompatible file", before, server.requests.size)
        assertTrue(server.requestsFor("$tag/$foreignName").isEmpty())
        assertTrue(loads.isEmpty())
        assertEquals(pins.fileName, netStore().activeIdentity().fileName)
    }

    @Test
    fun aManifestThatLiesAboutTheArchitectureIsCaughtByTheHeaderBeforeTheEngine() = runBlocking {
        // Signed, says "a85b2205", but the file's header says otherwise: downloaded (hash matches the
        // manifest), then refused by the structural check; the engine never sees it.
        publish(TestManifests.manifest(netEntry(name = foreignName, sha = foreignSha, size = foreignNet.size)))
        val offer = (checker().check() as UpdateCheckResult.Available).offers.single()
        val outcome = installer().install(offer)
        assertEquals(UpdateFailure.INCOMPATIBLE, (outcome as UpdateInstallOutcome.Failed).reason)
        assertTrue("the engine never loaded it", loads.isEmpty())
        assertFalse(File(netStore().dir, foreignName).exists())
        assertFalse(netStore().partFileFor(foreignName).exists())
        assertEquals(pins.fileName, netStore().activeIdentity().fileName)
    }

    @Test
    fun aTamperedManifestIsRefusedAndNothingIsUsed() = runBlocking {
        val good = TestManifests.manifest(netEntry(), voiceEntry())
        val sig = TestManifests.sign(good, keys.private)
        publish(good.toString(Charsets.UTF_8).replace("\"minVersionCode\": 1", "\"minVersionCode\": 0").toByteArray(), sig)
        val r = checker().check()
        assertTrue("$r", r is UpdateCheckResult.SignatureInvalid)
        assertEquals(listOf("/models/models.json", "/models/models.json.sig"), paths())
    }

    @Test
    fun aManifestSignedByAnotherKeyIsRefused() = runBlocking {
        val m = TestManifests.manifest(voiceEntry())
        publish(m, TestManifests.sign(m, TestManifests.newKeyPair().private))
        assertTrue(checker().check() is UpdateCheckResult.SignatureInvalid)
        // And the real app key does not accept a test signature either.
        publish(m)
        assertTrue(checker(publicKey = ManifestSignature.appPublicKeyDer).check() is UpdateCheckResult.SignatureInvalid)
    }

    @Test
    fun anUnsignedManifestIsRefused() = runBlocking {
        publish(TestManifests.manifest(voiceEntry()), signature = null)
        assertTrue(checker().check() is UpdateCheckResult.SignatureInvalid)
    }

    @Test
    fun aSignedManifestOfAnotherSchemaIsRefused() = runBlocking {
        publish(TestManifests.manifest(voiceEntry(), schemaVersion = 2))
        assertTrue(checker().check() is UpdateCheckResult.ManifestInvalid)
    }

    @Test
    fun noNetworkMeansNoRequestAtAll() = runBlocking {
        network = NetworkCost.UNAVAILABLE
        publish(TestManifests.manifest(voiceEntry()))
        assertEquals(UpdateCheckResult.NoInternet, checker().check())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun serverFailuresAreReportedAsSuch() = runBlocking {
        assertTrue(checker().check() is UpdateCheckResult.NotFound)
        publish(TestManifests.manifest(voiceEntry()))
        server.fault("models/models.json", Fault.Status(500))
        assertTrue(checker().check() is UpdateCheckResult.ServerUnavailable)
        server.fault("models/models.json", Fault.Status(503))
        assertTrue(checker().check() is UpdateCheckResult.ServerUnavailable)
        server.close()
        assertTrue(checker().check() is UpdateCheckResult.ServerUnavailable)
    }

    @Test
    fun whatIsInstalledIsNotOffered() = runBlocking {
        publish(TestManifests.manifest(netEntry(name = pins.fileName, sha = pins.sha256, size = oldNet.size)))
        val r = checker().check() as UpdateCheckResult.UpToDate
        assertEquals(CompatVerdict.AlreadyInstalled, r.notOffered.single().verdict)
    }

    @Test
    fun anOfferedNetDownloadsVerifiesTriesAndActivates() = runBlocking {
        publish(TestManifests.manifest(netEntry()))
        val offer = (checker().check() as UpdateCheckResult.Available).offers.single()
        val phases = ArrayList<UpdatePhase>()
        val outcome = installer().install(offer) { if (phases.lastOrNull() != it.phase) phases += it.phase }
        assertEquals(UpdateInstallOutcome.Installed(ModelKind.NET), outcome)
        assertEquals(listOf(newName), loads)
        assertEquals(newName, netStore().activeIdentity().fileName)
        assertEquals(listOf(UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.TRYING), phases)
        assertEquals(1, server.requestsFor("$tag/$newName").size)
        // Checked again: nothing more to offer.
        assertTrue(checker().check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun aDamagedDownloadChangesNothing() = runBlocking {
        server.alwaysFault("$tag/$tarName", Fault.CorruptByteAt(50_000))
        publish(TestManifests.manifest(voiceEntry()))
        val offer = (checker().check() as UpdateCheckResult.Available).offers.single()
        val outcome = installer().install(offer)
        assertEquals(UpdateFailure.DAMAGED, (outcome as UpdateInstallOutcome.Failed).reason)
        assertEquals(TestModelFiles.sha256(oldTar), voiceStore().installedSha256())
        assertFalse(voiceStore().updatePartFile(newTarSha).exists())
    }

    @Test
    fun anOfferedVoiceIsInstalledAndThenUpToDate() = runBlocking {
        publish(TestManifests.manifest(voiceEntry()))
        val offer = (checker().check() as UpdateCheckResult.Available).offers.single()
        val outcome = installer().install(offer)
        assertEquals(UpdateInstallOutcome.Installed(ModelKind.VOICE), outcome)
        assertEquals(newTarSha, voiceStore().installedSha256())
        assertTrue(checker().check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun aGzippedVoiceIsOfferedInstalledAgainstItsTarAndThenUpToDate() = runBlocking {
        // D2f: publish_models.sh ships the voice as .tar.gz with tarSha256/tarSize. The download is
        // verified against the .tar.gz's hash, the unpack against the tar's, and the marker holds the tar's,
        // so the next check compares tars and finds it installed.
        val gz = TestModelFiles.gzip(newTar)
        val gzName = "kokoro-int8-en-v0_19-r2.tar.gz"
        server.serve("$tag/$gzName", gz)
        publish(
            TestManifests.manifest(
                TestManifests.voiceEntry(
                    server.baseUrl, tag, gzName, gz.size.toLong(), TestModelFiles.sha256(gz),
                    tarSha256 = newTarSha, tarSize = newTar.size.toLong(),
                ),
            ),
        )
        val offer = (checker().check() as UpdateCheckResult.Available).offers.single()
        assertTrue(offer.entry.isGzip)
        assertEquals(gz.size.toLong(), offer.entry.sizeBytes)
        val outcome = installer().install(offer)
        assertEquals(UpdateInstallOutcome.Installed(ModelKind.VOICE), outcome)
        assertEquals("the marker is the tar's hash", newTarSha, voiceStore().installedSha256())
        assertEquals(1, server.requestsFor("$tag/$gzName").size)
        assertTrue(checker().check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun aGzippedVoiceWhoseTarIsNotTheSignedOneIsRefusedAndTheOldVoiceKept() = runBlocking {
        // The .tar.gz matches its signed hash but inflates to another tar than tarSha256 says.
        val gz = TestModelFiles.gzip(TestModelFiles.voiceTar(payload = 230_000))
        val gzName = "kokoro-int8-en-v0_19-r3.tar.gz"
        server.serve("$tag/$gzName", gz)
        publish(
            TestManifests.manifest(
                TestManifests.voiceEntry(
                    server.baseUrl, tag, gzName, gz.size.toLong(), TestModelFiles.sha256(gz),
                    tarSha256 = newTarSha, tarSize = newTar.size.toLong(),
                ),
            ),
        )
        val offer = (checker().check() as UpdateCheckResult.Available).offers.single()
        val outcome = installer().install(offer)
        assertEquals(UpdateFailure.DAMAGED, (outcome as UpdateInstallOutcome.Failed).reason)
        assertEquals(TestModelFiles.sha256(oldTar), voiceStore().installedSha256())
        assertFalse(java.io.File(files, "tts_models/${VoiceStore.SCRATCH_DIR_NAME}").exists())
    }
}
