package jo.codeide.feature.editor

import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.InstantaneTas
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de configuration du tooling (v3) : réglages persistés
 * à l'INSTANT (le DataStore est la seule vérité, le rendu s'y raccroche),
 * état vivant de l'orchestrateur (connexion + tas) fusionné en UN flux.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigToolingViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depotReglages = FakeSettingsRepository()

    private val tooling = FauxToolingEditor()

    private fun viewModel(): ConfigToolingViewModel = ConfigToolingViewModel(
        observerReglages = ObserveSettingsUseCase(depotReglages),
        majReglages = UpdateSettingsUseCase(depotReglages),
        tooling = tooling,
        journal = FakeAppLogger(),
    )

    @Test
    fun `les reglages observes reflètent le depot`() = runTest {
        depotReglages.updateSettings { it.copy(toolingHorsLigne = true) }
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(true, viewModel.reglages.value.toolingHorsLigne)
        assertEquals("", viewModel.reglages.value.toolingArguments)
    }

    // v0.46.0 : le test `definirAfficherTaches persiste a l instant` est
    // SUPPRIMÉ — le réglage a disparu de la page avec les rangées
    // structurées de la console (ADR 0078).

    @Test
    fun `definirHorsLigne et definirArguments persistent - la saisie est rognée`() = runTest {
        val viewModel = viewModel()

        viewModel.definirHorsLigne(true)
        viewModel.definirArguments("  --stacktrace   --info  ")
        advanceUntilIdle()

        assertEquals(true, depotReglages.reglages.toolingHorsLigne)
        assertEquals(
            "les blancs de tête et de queue partent au persisté",
            "--stacktrace   --info",
            depotReglages.reglages.toolingArguments,
        )
    }

    @Test
    fun `l etat vivant combine connexion et tas de l orchestrateur`() = runTest {
        val viewModel = viewModel()

        tooling.connexionInterne.value = EtatConnexion.CONNECTEE
        advanceUntilIdle()
        assertEquals(EtatConnexion.CONNECTEE, viewModel.etatVivant.value.connexion)

        // Le tas n'apparaît qu'à sa première mesure (0/0 = muet).
        assertTrue(viewModel.etatVivant.value.tas.moMax == 0L)

        tooling.connexionInterne.value = EtatConnexion.DECONNECTEE
        advanceUntilIdle()
        assertEquals(EtatConnexion.DECONNECTEE, viewModel.etatVivant.value.connexion)
    }

    @Test
    fun `un echec d ecriture est journalise sans exception - le rendu reste sur le persiste`() = runTest {
        depotReglages.writeError = java.io.IOException("disque plein")
        val viewModel = viewModel()

        viewModel.definirHorsLigne(true)
        advanceUntilIdle()

        // L'échec ne casse rien : le réglage courant reste celui du dépôt.
        assertEquals(AppSettings().toolingHorsLigne, depotReglages.reglages.toolingHorsLigne)
    }

    // ------------------------------------------------------------------
    // Phase 1 (ADR 0152) : instrumentation + banc « socket seul ».
    // ------------------------------------------------------------------

    @Test
    fun `Phase 1 - definirInstrumentationTransport et definirBancSocketSeul persistent a l instant`() = runTest {
        val viewModel = viewModel()

        viewModel.definirInstrumentationTransport(true)
        viewModel.definirBancSocketSeul(true)
        advanceUntilIdle()

        assertEquals(true, depotReglages.reglages.instrumentationTransport)
        assertEquals(true, depotReglages.reglages.bancSocketSeul)
    }

    @Test
    fun `Phase 1 - les parametres du banc persistent et se bornent`() = runTest {
        val viewModel = viewModel()

        viewModel.definirBancLignes(10_000)
        viewModel.definirBancCadenceMs(50)
        viewModel.definirBancLongueurLigne(200)
        advanceUntilIdle()

        assertEquals(10_000, depotReglages.reglages.bancLignes)
        assertEquals(50, depotReglages.reglages.bancCadenceMs)
        assertEquals(200, depotReglages.reglages.bancLongueurLigne)

        // Bornage : une valeur aberrante ne peut pas armer un déluge de
        // 2^31 lignes ni une cadence négative.
        viewModel.definirBancLignes(999_999_999)
        viewModel.definirBancCadenceMs(-5)
        advanceUntilIdle()

        assertEquals(200_000, depotReglages.reglages.bancLignes)
        assertEquals(0, depotReglages.reglages.bancCadenceMs)
    }

    @Test
    fun `Phase 1 - le contenu du banc valide les quatre valeurs admises - repli plain`() = runTest {
        val viewModel = viewModel()

        viewModel.definirBancContenu("  ANSI ")
        advanceUntilIdle()
        assertEquals("ansi", depotReglages.reglages.bancContenu)

        viewModel.definirBancContenu("utf8")
        advanceUntilIdle()
        assertEquals("utf8", depotReglages.reglages.bancContenu)

        viewModel.definirBancContenu("multi")
        advanceUntilIdle()
        assertEquals("multi", depotReglages.reglages.bancContenu)

        // Valeur inconnue : repli honnête sur plain (le serveur valide
        // aussi de son côté).
        viewModel.definirBancContenu("n'importe-quoi")
        advanceUntilIdle()
        assertEquals("plain", depotReglages.reglages.bancContenu)
    }

    // ---- Phase 2 (INC-31/32/33) : arguments de sync, niveau de journal,
    // redémarrage à la demande.

    @Test
    fun `Phase 2 - definirArgumentsSync persiste rogne - vide = retour aux arguments generaux`() = runTest {
        val viewModel = viewModel()

        viewModel.definirArgumentsSync("  --info  ")
        advanceUntilIdle()
        assertEquals("--info", depotReglages.reglages.toolingArgumentsSync)

        viewModel.definirArgumentsSync("   ")
        advanceUntilIdle()
        assertEquals("", depotReglages.reglages.toolingArgumentsSync)
    }

    @Test
    fun `Phase 2 - definirNiveauJournal valide INFO WARN ERROR - repli INFO`() = runTest {
        val viewModel = viewModel()

        viewModel.definirNiveauJournal("WARN")
        advanceUntilIdle()
        assertEquals("WARN", depotReglages.reglages.niveauJournalServeur)

        viewModel.definirNiveauJournal("ERROR")
        advanceUntilIdle()
        assertEquals("ERROR", depotReglages.reglages.niveauJournalServeur)

        viewModel.definirNiveauJournal("n'importe-quoi")
        advanceUntilIdle()
        assertEquals("INFO", depotReglages.reglages.niveauJournalServeur)
    }

    @Test
    fun `Phase 2 - redemarrerServeur demande le redemarrage a l orchestrateur`() = runTest {
        val viewModel = viewModel()

        viewModel.redemarrerServeur()

        assertEquals(1, tooling.redemarragesDemandes)
    }
}
