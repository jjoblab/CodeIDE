package jo.codeide.core.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.model.EtatInstallationBootstrap
import jo.codeide.core.model.EtatInstallationBootstrap.NonDemarree
import jo.codeide.core.model.EtatInstallationBootstrap.Terminee
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * aux transitions de l'installateur SANS attendre le ballotage, suivi
 * périodique des outils posés hors installateur (terminal, tooling),
 * et `distinctUntilChanged` (un stimulus sans changement ne réémet pas).
 *
 * Horloge virtuelle : le ballotage de 2 s avance par `advanceTimeBy`,
 * jamais `advanceUntilIdle` (le ticker est infini par construction).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ObservateurOutilsTerminalTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val racine: File = contexte.filesDir

    /** Installateur piloté par le test — seules ses transitions comptent. */
    private class FauxInstallateur : BootstrapInstaller {
        internal val etatInterne = MutableStateFlow<EtatInstallationBootstrap>(NonDemarree)
        override val etat: StateFlow<EtatInstallationBootstrap> = etatInterne.asStateFlow()
        override val paquetsOutils: List<String> = emptyList()
        override val journal: StateFlow<List<String>> = MutableStateFlow(emptyList())

        override fun demarrer() = Unit

        override fun installerOutils() = Unit

        override fun annuler() = Unit

        override fun refreshTerminalScripts() = Unit
    }

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

    @Test
    fun `premiere emission - disque vide, tout absent, une seule emission`() =
        runTest {
            val installateur = FauxInstallateur()
            val observateur =
                ObservateurOutilsTerminal(
                    contexte,
                    installateur,
                    TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )
            val etats = mutableListOf<EtatOutilsTerminal>()
            val collecte =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observateur().toList(etats)
                }

            runCurrent()
            assertEquals(listOf(EtatOutilsTerminal()), etats)

            // Un ballotage SANS changement de disque ne réémet rien.
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf(EtatOutilsTerminal()), etats)
            collecte.cancel()
        }

    @Test
    fun `transition de l installateur - rescan immediate sans attendre le ballotage`() =
        runTest {
            // Le disque porte déjà le bootstrap (posé « pendant » que
            // personne ne collectait) : la transition de l'installateur
            // déclenche le re-scan qui le voit — SANS avancer de 2 s.
            deposerBinaire("usr", "bin", "sh")
            deposerFichier("usr", ".codeide-installation-terminee")
            val installateur = FauxInstallateur()
            val observateur =
                ObservateurOutilsTerminal(
                    contexte,
                    installateur,
                    TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )
            val etats = mutableListOf<EtatOutilsTerminal>()
            val collecte =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observateur().toList(etats)
                }

            runCurrent()
            assertEquals(EtatOutilsTerminal(), etats.single())

            installateur.etatInterne.value = Terminee(emptyList())
            runCurrent()

            assertEquals(
                "la fin d'installation est visible immédiatement",
                listOf(EtatOutilsTerminal(), EtatOutilsTerminal(bootstrapInstalle = true)),
                etats,
            )
            collecte.cancel()
        }

    @Test
    fun `ballotage - un outil pose hors installateur devient visible au tic suivant`() =
        runTest {
            // La distribution Gradle arrive par l'orchestrateur du tooling,
            // le SDK par la commande `android-sdk` : RIEN ne passe par
            // l'installateur — seul le ré-interrogation périodique du
            // disque les voit (v0.37.3).
            val installateur = FauxInstallateur()
            val observateur =
                ObservateurOutilsTerminal(
                    contexte,
                    installateur,
                    TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )
            val etats = mutableListOf<EtatOutilsTerminal>()
            val collecte =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observateur().toList(etats)
                }

            runCurrent()
            assertEquals(EtatOutilsTerminal(), etats.single())

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
                    EtatOutilsTerminal(),
                    EtatOutilsTerminal(gradleInstalle = true),
                ),
                etats,
            )
            collecte.cancel()
        }
}
