package jo.codeide.feature.install

import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepId
import jo.codeide.core.testing.FakeEnvironmentSetupOrchestrator
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'écran d'installation E5 (ADR 0090) : la
 * traduction de l'état du parcours (compteur, sous-étape, vitesse et
 * temps restant **mesurés**), le relais des ordres vers l'orchestrateur
 * et le diagnostic copiable. La logique métier vit dans l'orchestrateur
 * (testée dans core:bootstrap) — ces tests éprouvent la projection.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstallationViewModelTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val orchestrateur = FakeEnvironmentSetupOrchestrator()

    @Test
    fun `aucune phase terminée sur un état initial`() {
        assertEquals(0, InstallationViewModel.phasesTerminees(EnvironmentSetupStateTest.etatInitial()))
    }

    @Test
    fun `terminée et dégradée comptent, échec et en cours non`() {
        val etat =
            EnvironmentSetupStateTest.etat(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Succeeded(verifiedAtMillis = 1L, versions = emptyMap()),
                        InstallPhase.PACKAGE_TOOLS to
                            PhaseState.Succeeded(verifiedAtMillis = 2L, versions = emptyMap()),
                        InstallPhase.JAVA to PhaseState.NotStarted,
                        InstallPhase.ANDROID_SDK to PhaseState.NotStarted,
                    ),
            )

        assertEquals(2, InstallationViewModel.phasesTerminees(etat))
    }

    @Test
    fun `sous-étape en octets - reçus et total en Mio`() {
        val etat =
            EnvironmentSetupStateTest.etat(
                phases =
                    mapOf(
                        InstallPhase.ANDROID_SDK to
                            PhaseState.Running(
                                step = StepId(InstallPhase.ANDROID_SDK, "build-tools"),
                                progress = Progress.Bytes(received = 61L * MIO, total = 127L * MIO),
                                startedAtMillis = 0L,
                            ),
                    ),
            )

        assertEquals("build-tools — 61 / 127 Mio", InstallationViewModel.sousEtapeCourante(etat))
    }

    @Test
    fun `sous-étape en éléments - done et total`() {
        val etat =
            EnvironmentSetupStateTest.etat(
                phases =
                    mapOf(
                        InstallPhase.PACKAGE_TOOLS to
                            PhaseState.Running(
                                step = StepId(InstallPhase.PACKAGE_TOOLS, "pkg-update"),
                                progress = Progress.Items(done = 2, total = 5),
                                startedAtMillis = 0L,
                            ),
                    ),
            )

        assertEquals("pkg-update — 2/5", InstallationViewModel.sousEtapeCourante(etat))
    }

    @Test
    fun `aucune sous-étape hors exécution`() {
        assertNull(InstallationViewModel.sousEtapeCourante(EnvironmentSetupStateTest.etatInitial()))
    }

    @Test
    fun `premier échantillon - aucune estimation (on ne devine jamais)`() {
        val courant =
            EchantillonTelechargement(
                instantMillis = 1_000L,
                recus = MIO,
                total = 10L * MIO,
            )

        assertNull(InstallationViewModel.estimer(precedent = null, courant = courant))
    }

    @Test
    fun `vitesse et temps restant mesurés sur deux échantillons`() {
        val precedent =
            EchantillonTelechargement(
                instantMillis = 0L,
                recus = MIO,
                total = 10L * MIO,
            )
        val courant =
            EchantillonTelechargement(
                instantMillis = 2_000L,
                recus = 3L * MIO,
                total = 10L * MIO,
            )

        val estimation = InstallationViewModel.estimer(precedent, courant)

        assertEquals(1L * MIO, estimation?.octetsParSeconde)
        assertEquals(7L, estimation?.secondesRestantes)
    }

    @Test
    fun `temps restant inconnu sans total annoncé`() {
        val precedent = EchantillonTelechargement(instantMillis = 0L, recus = MIO, total = null)
        val courant = EchantillonTelechargement(instantMillis = 1_000L, recus = 2L * MIO, total = null)

        val estimation = InstallationViewModel.estimer(precedent, courant)

        assertEquals(MIO, estimation?.octetsParSeconde)
        assertNull(estimation?.secondesRestantes)
    }

    @Test
    fun `progression non croissante - aucune estimation`() {
        val precedent = EchantillonTelechargement(instantMillis = 0L, recus = 2L * MIO, total = 10L * MIO)
        val courant = EchantillonTelechargement(instantMillis = 1_000L, recus = MIO, total = 10L * MIO)

        assertNull(InstallationViewModel.estimer(precedent, courant))
    }

    @Test
    fun `téléchargement terminé - zéro seconde restante`() {
        val precedent = EchantillonTelechargement(instantMillis = 0L, recus = MIO, total = 2L * MIO)
        val courant = EchantillonTelechargement(instantMillis = 1_000L, recus = 2L * MIO, total = 2L * MIO)

        assertEquals(0L, InstallationViewModel.estimer(precedent, courant)?.secondesRestantes)
    }

    @Test
    fun `le journal est replié puis déplié`() =
        runTest {
            val viewModel = InstallationViewModel(orchestrateur)
            advanceUntilIdle()

            assertFalse(viewModel.journalDeplie.value)
            viewModel.basculerJournal()
            assertTrue(viewModel.journalDeplie.value)
        }

    @Test
    fun `demarrer relaie run vers l orchestrateur`() =
        runTest {
            val viewModel = InstallationViewModel(orchestrateur)
            advanceUntilIdle()

            viewModel.demarrer()
            advanceUntilIdle()

            assertEquals(listOf<InstallPhase?>(null), orchestrateur.lancements)
        }

    @Test
    fun `accepterEtDemarrer enregistre l acceptation puis lance le parcours`() =
        runTest {
            val viewModel = InstallationViewModel(orchestrateur)
            advanceUntilIdle()

            viewModel.accepterEtDemarrer()
            advanceUntilIdle()

            // v0.60.1 (ADR 0092) : consentement ET lancement, dans cet ordre —
            // l'ancien bouton « Installer le SDK » ne faisait que démarrer.
            assertEquals(1, orchestrateur.acceptationsLicence.get())
            assertEquals(listOf<InstallPhase?>(null), orchestrateur.lancements)
        }

    @Test
    fun `diagnostic - journal intégral puis récapitulatif`() {
        orchestrateur.semerJournal(listOf("\$ java -version", "17.0.20"))
        val etat = EnvironmentSetupStateTest.etatInitial()

        val texte = InstallationViewModel.diagnostic(etat, orchestrateur.journal.value)

        assertTrue(texte.startsWith("\$ java -version\n17.0.20\n"))
        assertTrue(texte.contains("ANDROID_SDK : NOT_STARTED"))
    }

    private companion object {
        private const val MIO: Long = 1024L * 1024
    }
}

/** Fabriques d'états locales aux tests de l'écran d'installation. */
private object EnvironmentSetupStateTest {
    fun etatInitial() = etat(phases = emptyMap())

    fun etat(phases: Map<InstallPhase, PhaseState>) =
        jo.codeide.core.domain.EnvironmentSetupState(
            phases = phases,
            running = null,
            sdkLicenseAcceptedAtMillis = null,
        )
}
