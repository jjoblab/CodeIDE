package jo.codeide.feature.editor

import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests des signaux système consolidés (Phase 1, ADR 0152 — fait F6 : le
 * journal de terrain ne portait qu'un signal nu de pression mémoire) :
 * chaque trim significatif part au journal AVEC son contexte mémoire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SondesSystemeTest {
    @Test
    fun `un signal de trim part au journal avec son niveau et son contexte`() = runTest {
        val journal = FakeAppLogger()
        val sondes =
            SondesSysteme(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = journal,
                dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            )

        sondes.signalerTrimMemory(niveau = 15)
        advanceUntilIdle()

        val entree = journal.entries.single()
        assertEquals("PressionMemoire", entree.tag)
        assertTrue("le niveau est porté : ${entree.message}", entree.message.contains("niveau 15"))
        // La partie système est lue de /proc/meminfo (Linux réel) ou
        // absente proprement — les DEUX sont honnêtes, seul le niveau est
        // garanti partout.
        assertTrue(
            "l'état mémoire (app ou système) accompagne le signal : ${entree.message}",
            entree.message.contains("availMem") || entree.message.contains("système"),
        )
    }

    @Test
    fun `deux signaux consecutifs partent tous les deux - aucun n est avale`() = runTest {
        val journal = FakeAppLogger()
        val sondes =
            SondesSysteme(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = journal,
                dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            )

        sondes.signalerTrimMemory(niveau = 15)
        sondes.signalerTrimMemory(niveau = 20)
        advanceUntilIdle()

        assertEquals(2, journal.entries.size)
        assertTrue(journal.entries[1].message.contains("niveau 20"))
    }
}
