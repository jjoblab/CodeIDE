package jo.codeide.feature.editor

import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Tests d'[OptionsTooling] (Phase 2) : les arguments RÉSERVÉS à la sync
 * (INC-31) suivent la même règle que les arguments généraux — vide =
 * comportement d'avant (le serveur retombe sur les généraux), non-vide =
 * ils REMPLACENT les généraux en ré-incluant le mode hors ligne.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptionsToolingTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeSettingsRepository()

    private fun creerOptions(): OptionsTooling = OptionsTooling(
        observerReglages = ObserveSettingsUseCase(depot),
        dispatchers = TestDispatcherProvider(regleMain.dispatcher),
    )

    @Test
    fun `sans arguments sync la liste est vide - le serveur retombe sur les generaux`() = runTest {
        depot.updateSettings {
            it.copy(toolingHorsLigne = true, toolingArguments = "--stacktrace")
        }
        val options = creerOptions()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), options.argumentsSync())
        // Les arguments généraux restent la voie du build (hors ligne + libres).
        assertEquals(listOf("--offline", "--stacktrace"), options.argumentsBuild())
    }

    @Test
    fun `avec arguments sync ils remplacent les generaux et reaffichent le hors ligne`() = runTest {
        depot.updateSettings {
            it.copy(toolingHorsLigne = true, toolingArguments = "--stacktrace", toolingArgumentsSync = "--info")
        }
        val options = creerOptions()
        advanceUntilIdle()

        assertEquals(listOf("--offline", "--info"), options.argumentsSync())
    }

    @Test
    fun `arguments sync sans hors ligne - pas de --offline force`() = runTest {
        depot.updateSettings {
            it.copy(toolingHorsLigne = false, toolingArgumentsSync = "  --info   --scan  ")
        }
        val options = creerOptions()
        advanceUntilIdle()

        assertEquals(listOf("--info", "--scan"), options.argumentsSync())
    }
}
