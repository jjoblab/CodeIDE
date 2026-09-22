package jo.codeide.feature.home

import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'accueil (étape 5) : le bandeau « Configurer le
 * dossier de travail » n'apparaît que si l'assistant **est terminé** et
 * **sans dossier** — pendant l'assistant il serait redondant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    @Test
    fun `pas de bandeau tant que l'assistant n'est pas termine`() =
        runTest(regleMain.dispatcher) {
            val depot = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = false))
            val viewModel = HomeViewModel(ObserveSettingsUseCase(depot))

            advanceUntilIdle()

            assertFalse(viewModel.etat.value.montrerBandeau)
            assertNull(viewModel.etat.value.libelleDossier)
        }

    @Test
    fun `pas de bandeau quand le dossier de travail est configure`() =
        runTest(regleMain.dispatcher) {
            val depot =
                FakeSettingsRepository(
                    initial =
                        AppSettings(
                            isSetupCompleted = true,
                            workspace = StorageLocation("g", "d", "CodeIDE"),
                        ),
                )
            val viewModel = HomeViewModel(ObserveSettingsUseCase(depot))

            advanceUntilIdle()

            assertFalse(viewModel.etat.value.montrerBandeau)
            assertEquals("CodeIDE", viewModel.etat.value.libelleDossier)
        }

    @Test
    fun `bandeau quand l'assistant est termine sans dossier`() =
        runTest(regleMain.dispatcher) {
            val depot = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
            val viewModel = HomeViewModel(ObserveSettingsUseCase(depot))

            advanceUntilIdle()

            assertTrue(viewModel.etat.value.montrerBandeau)
            assertNull(viewModel.etat.value.libelleDossier)
        }

    @Test
    fun `le bandeau suit les parametres en direct`() =
        runTest(regleMain.dispatcher) {
            val depot = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
            val viewModel = HomeViewModel(ObserveSettingsUseCase(depot))
            advanceUntilIdle()

            depot.updateSettings {
                it.copy(workspace = StorageLocation("g", "d", "Projets"))
            }
            advanceUntilIdle()

            assertFalse(viewModel.etat.value.montrerBandeau)
        }
}
