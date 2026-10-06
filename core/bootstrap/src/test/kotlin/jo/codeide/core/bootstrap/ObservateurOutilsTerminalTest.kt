package jo.codeide.core.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.testing.FakeEnvironmentSetupOrchestrator
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests de [ObservateurOutilsTerminal] (v0.37.3 — retour d'appareil
 * réel : « la plupart des points de l'UI pour le tooling ne se mettent
 * pas à jour ») : première émission = diagnostic du disque, réémission
 * aux transitions du parcours d'installation SANS attendre le
 * ballotage (E6 : le stimulus est l'état de l'orchestrateur du
 * parcours, l'ancien `BootstrapInstaller` a été retiré), suivi
 * périodique des outils posés hors parcours (terminal, tooling), et
 * `distinctUntilChanged` (un stimulus sans changement ne réémet pas).
 *
 * Horloge virtuelle : le ballotage de 2 s avance par `advanceTimeBy`,
 * jamais `advanceUntilIdle` (le ticker est infini par construction).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ObservateurOutilsTerminalTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val racine: File = contexte.filesDir

    /** Parcours piloté par le test — seules ses transitions comptent. */
    private val parcours = FakeEnvironmentSetupOrchestrator()

    private fun deposerBinaire(vararg chemin: String) {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("#!/system/bin/sh\n")
        fichier.setExecutable(true)
    }

    private fun deposerFichier(vararg chemin: String) {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("contenu\n")
    }

    private fun observateur(repartiteur: DispatcherProvider) =
        ObservateurOutilsTerminal(
            contexte,
            parcours,
            repartiteur,
        )

    @Test
    fun `premiere emission - disque vide, tout absent, une seule emission`() =
        runTest {
            val etats = mutableListOf<EtatOutilsTerminal>()
            val collecte =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observateur(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))().toList(etats)
                }

            runCurrent()
            // v0.39.1 : la première émission porte `initialise = true` —
            // le disque a été lu au moins une fois. Le reste reste `false`
            // (aucun outil détecté sur disque vide).
            assertEquals(listOf(EtatOutilsTerminal(initialise = true)), etats)

            // Un ballotage SANS changement de disque ne réémet rien.
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf(EtatOutilsTerminal(initialise = true)), etats)
            collecte.cancel()
        }

    @Test
    fun `transition du parcours - rescan immediate sans attendre le ballotage`() =
        runTest {
            val etats = mutableListOf<EtatOutilsTerminal>()
            val collecte =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observateur(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))().toList(etats)
                }

            runCurrent()
            assertEquals(EtatOutilsTerminal(initialise = true), etats.single())

            // Le disque porte désormais le bootstrap (posé « pendant » que
            // personne ne collectait le ballotage) : la transition du
            // parcours (phase BOOTSTRAP vérifiée) déclenche le re-scan
            // qui le voit — SANS avancer de 2 s.
            deposerBinaire("usr", "bin", "sh")
            deposerFichier("usr", ".codeide-installation-terminee")
            parcours.semerEtat(
                EnvironmentSetupState(
                    phases =
                        mapOf(
                            InstallPhase.BOOTSTRAP to
                                PhaseState.Succeeded(verifiedAtMillis = 1_000L, versions = emptyMap()),
                        ),
                    running = null,
                    sdkLicenseAcceptedAtMillis = null,
                ),
            )
            runCurrent()

            assertEquals(
                "la fin d'installation est visible immédiatement",
                listOf(
                    EtatOutilsTerminal(initialise = true),
                    EtatOutilsTerminal(bootstrapInstalle = true, initialise = true),
                ),
                etats,
            )
            collecte.cancel()
        }

    @Test
    fun `ballotage - un outil pose hors parcours devient visible au tic suivant`() =
        runTest {
            // La distribution Gradle arrive par l'orchestrateur du tooling :
            // RIEN ne passe par le parcours — seule la ré-interrogation
            // périodique du disque la voit (v0.37.3).
            val etats = mutableListOf<EtatOutilsTerminal>()
            val collecte =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observateur(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))().toList(etats)
                }

            runCurrent()
            assertEquals(EtatOutilsTerminal(initialise = true), etats.single())

            // Le tooling télécharge SA distribution dans le HOME du shell.
            deposerFichier(
                "home",
                ".gradle",
                "wrapper",
                "dists",
                "gradle-9.7.1",
                "abc123",
                "gradle-9.7.1",
                "lib",
                "gradle-launcher-9.7.1.jar",
            )
            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(
                listOf(
                    EtatOutilsTerminal(initialise = true),
                    EtatOutilsTerminal(gradleInstalle = true, initialise = true),
                ),
                etats,
            )
            collecte.cancel()
        }
}
