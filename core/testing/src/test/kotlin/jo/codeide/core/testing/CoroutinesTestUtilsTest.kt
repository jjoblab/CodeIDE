package jo.codeide.core.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests des utilitaires de test eux-mêmes : la règle installe bien un
 * `Dispatchers.Main` pilotable, et le provider de test expose une seule et
 * même horloge pour les trois rôles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTestUtilsTest {
    /** Sujet du test : la règle utilisée comme le ferait un ViewModelTest. */
    @get:Rule
    public val regleMain: MainDispatcherRule = MainDispatcherRule()

    @Test
    public fun `main est installé et pilotable par la règle`() {
        var fait = false
        val demarreur = CoroutineScope(Dispatchers.Main)

        val lancement = demarreur.launch { fait = true }

        // StandardTestDispatcher : rien ne démarre sans avancée explicite.
        assertFalse(fait)
        regleMain.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(fait)
        assertTrue(lancement.isCompleted)

        demarreur.cancel()
    }

    @Test
    public fun `le provider de test expose le même dispatcher partout`() {
        val provider = TestDispatcherProvider(regleMain.dispatcher)

        assertSame(regleMain.dispatcher, provider.io)
        assertSame(regleMain.dispatcher, provider.default)
        assertSame(regleMain.dispatcher, provider.main)
    }

    @Test
    public fun `le code sous test ne s'exécute qu'après l'avancée du test`() {
        val provider = TestDispatcherProvider(regleMain.dispatcher)
        val ordre = mutableListOf<String>()

        // Simulation d'un use case recevant le provider injecté.
        CoroutineScope(provider.io).launch { ordre.add("coroutine") }
        ordre.add("avant")

        assertEquals(listOf("avant"), ordre)

        regleMain.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("avant", "coroutine"), ordre)
    }
}
