package jo.codeide.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Tests de [DispatcherProvider] et de son implémentation de référence.
 *
 * `main` est installé par un dispatcher de test avant chaque cas, comme le
 * fera [jo.codeide.core.testing.MainDispatcherRule] dans les autres modules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherProviderTest {
    private val dispatcherTest: TestDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before
    fun installerMain() {
        Dispatchers.setMain(dispatcherTest)
    }

    @After
    fun reinitialiserMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `l'implémentation de référence expose les dispatchers réels`() {
        val provider: DispatcherProvider = DefaultDispatcherProvider()

        assertSame(Dispatchers.IO, provider.io)
        assertSame(Dispatchers.Default, provider.default)
    }

    @Test
    fun `main est contrôlable en test via setMain`() {
        val provider: DispatcherProvider = DefaultDispatcherProvider()

        // Après setMain, main renvoie le dispatcher de test : c'est le
        // fondement de la déterminisme des tests de ViewModels.
        assertEquals(Dispatchers.Main, provider.main)
    }

    @Test
    fun `une implémentation de test peut remplacer les trois dispatchers`() {
        val provider =
            object : DispatcherProvider {
                override val io: CoroutineDispatcher = dispatcherTest
                override val default: CoroutineDispatcher = dispatcherTest
                override val main: CoroutineDispatcher = dispatcherTest
            }

        assertSame(dispatcherTest, provider.io)
        assertSame(dispatcherTest, provider.default)
        assertSame(dispatcherTest, provider.main)
    }
}
