package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.NarrationSettingsRepository
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the rule that keeps a ~98.5 MB Kokoro download off a user's cellular bill.
 *
 * The decision itself ([decideAutoVoice]) is pure, so it is exercised directly at every
 * combination of [NetworkCost] and installed-tier state rather than by trying to make an emulator
 * report a metered network — which it will not do. That is the point of [NetworkCostProbe] being
 * an interface: the branch that must never fire in production is the only one worth testing, and
 * it is unreachable without injection.
 *
 * [ConnectivityNetworkCostProbe] itself is checked separately and only for what can honestly be
 * asserted here: that it answers at all, and that its answer is one of the three states. Which
 * state a given emulator reports is not something this test can control, so it does not pretend to.
 */
@RunWith(AndroidJUnit4::class)
class AutoVoicePolicyInstrumentedTest {

    private val none = emptySet<NeuralVoiceTier>()
    private val piperOnly = setOf(NeuralVoiceTier.PIPER)
    private val kokoroOnly = setOf(NeuralVoiceTier.KOKORO)
    private val both = setOf(NeuralVoiceTier.PIPER, NeuralVoiceTier.KOKORO)

    @Test
    fun meteredNetworkNeverAutoDownloadsKokoro() {
        // The single assertion this whole file exists for: across every installed-tier state, a
        // metered connection must never start the ~98.5 MB fetch.
        for (installed in listOf(none, piperOnly, kokoroOnly, both)) {
            val decision = decideAutoVoice(NetworkCost.METERED, installed)
            assertNotEquals(
                "metered network must never auto-download Kokoro (~98.5 MB of billable data); " +
                    "installed=$installed gave: $decision",
                NeuralVoiceTier.KOKORO,
                decision.download,
            )
        }
    }

    @Test
    fun meteredWithNothingInstalledFallsBackToPiper() {
        val decision = decideAutoVoice(NetworkCost.METERED, none)
        assertEquals(
            "with no model at all, ~20 MB of Piper is the sanctioned middle ground on metered",
            NeuralVoiceTier.PIPER,
            decision.download,
        )
        assertNull("nothing is installed, so there is nothing to promote to yet", decision.promoteTo)
    }

    @Test
    fun meteredWithPiperInstalledDownloadsNothingAndUsesPiper() {
        val decision = decideAutoVoice(NetworkCost.METERED, piperOnly)
        assertNull("a usable model is already on disk — spend no billable bytes at all", decision.download)
        assertEquals(NeuralVoiceTier.PIPER, decision.promoteTo)
    }

    @Test
    fun unmeteredDownloadsKokoro() {
        val decision = decideAutoVoice(NetworkCost.UNMETERED, none)
        assertEquals(NeuralVoiceTier.KOKORO, decision.download)
        assertNull(decision.promoteTo)
    }

    @Test
    fun unmeteredWithPiperInstalledNarratesWithPiperWhileKokoroDownloads() {
        val decision = decideAutoVoice(NetworkCost.UNMETERED, piperOnly)
        assertEquals("upgrade to Kokoro in the background", NeuralVoiceTier.KOKORO, decision.download)
        assertEquals(
            "narration must not sit on the robotic device voice for the whole 98 MB download " +
                "when a perfectly good neural model is already on disk",
            NeuralVoiceTier.PIPER,
            decision.promoteTo,
        )
    }

    @Test
    fun kokoroAlreadyInstalledDownloadsNothingOnAnyNetwork() {
        for (cost in NetworkCost.entries) {
            val decision = decideAutoVoice(cost, kokoroOnly)
            assertNull("Kokoro is installed; there is nothing to fetch on $cost", decision.download)
            assertEquals(NeuralVoiceTier.KOKORO, decision.promoteTo)
        }
    }

    @Test
    fun noNetworkDownloadsNothingAndPromotesNothing() {
        val decision = decideAutoVoice(NetworkCost.UNAVAILABLE, none)
        assertNull(decision.download)
        assertNull(decision.promoteTo)
    }

    @Test
    fun noNetworkStillPromotesAnAlreadyInstalledModel() {
        val decision = decideAutoVoice(NetworkCost.UNAVAILABLE, piperOnly)
        assertNull("offline — nothing can be downloaded", decision.download)
        assertEquals(
            "being offline is no reason to narrate with the device voice when Piper is on disk",
            NeuralVoiceTier.PIPER,
            decision.promoteTo,
        )
    }

    @Test
    fun everyDecisionCarriesAReason() {
        for (cost in NetworkCost.entries) {
            for (installed in listOf(none, piperOnly, kokoroOnly, both)) {
                val decision = decideAutoVoice(cost, installed)
                assertTrue(
                    "every automatic decision must be able to say why (it is logged): $cost/$installed",
                    decision.reason.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun realProbeReturnsAUsableAnswerOnThisDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cost = ConnectivityNetworkCostProbe(context).current()
        assertTrue(
            "ConnectivityNetworkCostProbe must return one of the three states, got $cost",
            cost in NetworkCost.entries,
        )
    }

    /**
     * The gate decides *what* to download; this guards *whether* the automatic path may run at
     * all. An automatic switch must never latch
     * [net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings.providerExplicitlyChosen] —
     * otherwise the app records its own decision as the user's and permanently locks itself out
     * of ever upgrading them to a better model.
     *
     * Establishes the un-chosen state via `clearProviderChoiceForTesting()` rather than assuming
     * it: every instrumented test in this run shares one real DataStore, and another test's
     * cleanup calls `setProvider()`, which latches the flag.
     */
    @Test
    fun automaticProviderSwitchIsNeverRecordedAsTheUsersOwnChoice(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NarrationSettingsRepository(context)
        repo.clearProviderChoiceForTesting()
        assertFalse(
            "precondition: the stored state must be 'user has not chosen'",
            repo.current().providerExplicitlyChosen,
        )

        try {
            repo.setProviderAutomatically(NarrationProviderChoice.NEURAL)
            repo.setNeuralTier(NeuralVoiceTier.KOKORO)

            val after = repo.current()
            assertEquals(NarrationProviderChoice.NEURAL, after.provider)
            assertEquals(NeuralVoiceTier.KOKORO, after.neuralTier)
            assertFalse(
                "the automatic promotion to Kokoro must leave providerExplicitlyChosen false",
                after.providerExplicitlyChosen,
            )
        } finally {
            // Clean up through the *automatic* setter, not setProvider(): the explicit one latches
            // providerExplicitlyChosen, and a test's own cleanup silently flipping that flag for
            // every later test in the run is precisely the shared-DataStore pollution CLAUDE.md
            // warns about.
            repo.setProviderAutomatically(NarrationProviderChoice.DEVICE)
            repo.clearProviderChoiceForTesting()
        }
    }

    /**
     * Sizes quoted in Settings have to be the real ones. These are measured from the pinned,
     * SHA-256-verified archives (download = the archive, installed = the extracted tree) and this
     * test is what makes them stay honest if a pin is ever bumped without re-measuring.
     */
    @Test
    fun quotedModelSizesMatchThePinnedArchives() {
        val kokoro = VoiceModelProvisioner.KOKORO_SPEC
        val piper = VoiceModelProvisioner.PIPER_SPEC

        assertEquals(103_248_205L, kokoro.downloadSizeBytes)
        assertEquals(157_947_103L, kokoro.installedSizeBytes)
        assertEquals(21_090_429L, piper.downloadSizeBytes)
        assertEquals(37_347_875L, piper.installedSizeBytes)

        // If a pushed archive is present, verify the download size against the real file rather
        // than only against another constant in the same file.
        val stagedKokoro = File(NeuralTtsProviderInstrumentedTest.KOKORO_ARCHIVE_ON_DEVICE)
        assertTrue(
            "Expected the Kokoro archive staged at ${stagedKokoro.path} (see " +
                "NeuralTtsProviderInstrumentedTest) — failing loudly rather than skipping",
            stagedKokoro.isFile,
        )
        assertEquals(
            "the pinned downloadSizeBytes must match the actual archive",
            kokoro.downloadSizeBytes,
            stagedKokoro.length(),
        )
    }
}
