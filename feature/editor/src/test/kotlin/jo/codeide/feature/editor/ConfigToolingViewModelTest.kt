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

    private fun viewModel(): ConfigToolingViewModel =
        ConfigToolingViewModel(
            observerReglages = ObserveSettingsUseCase(depotReglages),
            majReglages = UpdateSettingsUseCase(depotReglages),
            tooling = tooling,
            journal = FakeAppLogger(),
        )

    @Test
    fun `les reglages observes reflètent le depot`() =
        runTest {
            depotReglages.updateSettings { it.copy(toolingAfficherTaches = false, toolingHorsLigne = true) }
            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(false, viewModel.reglages.value.toolingAfficherTaches)
            assertEquals(true, viewModel.reglages.value.toolingHorsLigne)
            assertEquals("", viewModel.reglages.value.toolingArguments)
        }

    @Test
    fun `definirAfficherTaches persiste a l instant`() =
        runTest {
            val viewModel = viewModel()

            viewModel.definirAfficherTaches(false)
            advanceUntilIdle()

            assertEquals(false, depotReglages.reglages.toolingAfficherTaches)
            assertEquals(
                "le flux observé confirme le geste (rendu idempotent)",
                false,
                viewModel.reglages.value.toolingAfficherTaches,
            )
        }

    @Test
    fun `definirHorsLigne et definirArguments persistent - la saisie est rognée`() =
        runTest {
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
    fun `l etat vivant combine connexion et tas de l orchestrateur`() =
        runTest {
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
    fun `un echec d ecriture est journalise sans exception - le rendu reste sur le persiste`() =
        runTest {
            depotReglages.writeError = java.io.IOException("disque plein")
            val viewModel = viewModel()

            viewModel.definirAfficherTaches(true)
            advanceUntilIdle()

            // L'échec ne casse rien : le réglage courant reste celui du dépôt.
            assertEquals(AppSettings().toolingAfficherTaches, depotReglages.reglages.toolingAfficherTaches)
        }
}
