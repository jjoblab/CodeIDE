package jo.codeide.feature.terminal

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.TaillePoliceTerminal
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeTerminalSessionRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Tests du ViewModel de l'écran du terminal (critère d'acceptation T5,
 * prompt Terminal-1, section 10 : fakes du domaine, **aucune session
 * Termux réelle**).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val registre = FakeTerminalSessionRepository()
    private val depot = FakeSettingsRepository()
    private val journal = FakeAppLogger()

    private object EnvironnementFaux : ProcessEnvironmentProvider {
        override fun baseEnvironment(): Map<String, String> = mapOf("HOME" to "/home/faux")
    }

    private fun viewModel(
        repertoireSuggere: String? = null,
        depotReglages: FakeSettingsRepository = depot,
    ): TerminalViewModel =
        TerminalViewModel(
            registre = registre,
            environnement = EnvironnementFaux,
            observeReglages = ObserveSettingsUseCase(depotReglages),
            savedState =
                SavedStateHandle(
                    if (repertoireSuggere == null) {
                        emptyMap()
                    } else {
                        mapOf(ClesTerminal.EXTRA_REPERTOIRE to repertoireSuggere)
                    },
                ),
            journal = journal,
        )

    /** Collecte l'état en arrière-plan (WhileSubscribed exige un abonné). */
    private fun TestScope.collecterEtat(viewModel: TerminalViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
            viewModel.uiState.toList(ArrayList())
        }

    /** Session synthétique pilotée par le test. */
    private fun session(
        id: String,
        vivante: Boolean = true,
        apercu: String = "user@codeide:~$ ",
    ): TerminalSessionSummary =
        TerminalSessionSummary(
            id = id,
            label = "Session $id",
            workingDirectoryPath = "/projets/$id",
            isAlive = vivante,
            lastOutputPreview = apercu,
            createdAt = Instant.EPOCH,
        )

    @Test
    fun `une nouvelle session part du repertoire suggere par l entree`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel(repertoireSuggere = "/projets/mon-projet")

            viewModel.onAction(ActionTerminal.NouvelleSession)
            advanceUntilIdle()

            assertEquals(File("/projets/mon-projet"), registre.creations.single().first)
        }

    @Test
    fun `sans suggestion la session part du HOME canonique`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()

            viewModel.onAction(ActionTerminal.NouvelleSession)
            advanceUntilIdle()

            assertEquals(File("/home/faux"), registre.creations.single().first)
        }

    @Test
    fun `ouvrir une session la selectionne dans le registre global`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()

            viewModel.onAction(ActionTerminal.OuvrirSession("id-1"))
            advanceUntilIdle()

            assertEquals(listOf("id-1"), registre.activations)
        }

    @Test
    fun `fermer une session au prompt ferme directement sans confirmation`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()
            val collecte = collecterEtat(viewModel)
            registre.simulerSessions(listOf(session("id-1", vivante = true, apercu = "user@codeide:~$ ")))
            advanceUntilIdle()

            viewModel.onAction(ActionTerminal.FermerSession("id-1"))
            advanceUntilIdle()

            assertEquals(listOf("id-1"), registre.fermetures)
            collecte.cancel()
        }

    @Test
    fun `fermer une session occupee demande confirmation puis ferme`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()
            val collecte = collecterEtat(viewModel)
            registre.simulerSessions(listOf(session("id-1", vivante = true, apercu = "compilation en cours")))
            advanceUntilIdle()
            val effets = mutableListOf<EffetTerminal>()
            val travail =
                backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                    viewModel.effets.toList(effets)
                }

            viewModel.onAction(ActionTerminal.FermerSession("id-1"))
            advanceUntilIdle()
            assertTrue(effets.single() is EffetTerminal.DemanderConfirmationFermeture)
            assertTrue("rien n'est fermé sans accord", registre.fermetures.isEmpty())

            viewModel.onAction(ActionTerminal.ConfirmerFermeture("id-1"))
            advanceUntilIdle()

            assertEquals(listOf("id-1"), registre.fermetures)
            travail.cancel()
            collecte.cancel()
        }

    @Test
    fun `fermer une session terminee ne demande jamais confirmation`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()
            val collecte = collecterEtat(viewModel)
            registre.simulerSessions(listOf(session("id-1", vivante = false, apercu = "exit")))
            advanceUntilIdle()

            viewModel.onAction(ActionTerminal.FermerSession("id-1"))
            advanceUntilIdle()

            assertEquals(listOf("id-1"), registre.fermetures)
            collecte.cancel()
        }

    @Test
    fun `renommer transmet le libelle nettoye`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()

            viewModel.onAction(ActionTerminal.RenommerSession("id-1", "  build  "))
            advanceUntilIdle()

            assertEquals(listOf("id-1" to "build"), registre.renommages)
        }

    @Test
    fun `renommer en vide est ignore`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()

            viewModel.onAction(ActionTerminal.RenommerSession("id-1", "   "))
            advanceUntilIdle()

            assertTrue(registre.renommages.isEmpty())
        }

    @Test
    fun `dupliquer recree une session avec le meme repertoire de travail`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = viewModel()
            val collecte = collecterEtat(viewModel)
            registre.simulerSessions(listOf(session("id-1")))
            advanceUntilIdle()

            viewModel.onAction(ActionTerminal.DupliquerSession("id-1"))
            advanceUntilIdle()

            assertEquals(File("/projets/id-1"), registre.creations.single().first)
            collecte.cancel()
        }

    @Test
    fun `l etat expose la taille de police des reglages`() =
        runTest(regleMain.dispatcher.scheduler) {
            val depotGrande = FakeSettingsRepository(AppSettings(taillePoliceTerminal = TaillePoliceTerminal.GRANDE))
            val viewModel = viewModel(depotReglages = depotGrande)
            val collecte = collecterEtat(viewModel)
            advanceUntilIdle()

            assertEquals(TaillePoliceTerminal.GRANDE, viewModel.uiState.value.taillePolice)
            collecte.cancel()
        }

    @Test
    fun `l heuristique de commande en cours distingue prompt et sortie`() {
        assertTrue(commandeSembleEnCours(session("a", vivante = true, apercu = "compilation")))
        assertFalse(commandeSembleEnCours(session("a", vivante = true, apercu = "user:~$ ")))
        assertFalse(commandeSembleEnCours(session("a", vivante = false, apercu = "compilation")))
    }
}
