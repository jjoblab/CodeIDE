package jo.codeide.feature.sorties

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.LogRepository
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.testing.InMemoryLogRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests de la source JOURNAL derrière le port du lecteur (lot L4) —
 * parité de la fusion de l'écran Journaux (ADR 0025) : l'historique est
 * lu UNE fois, les nouveautés se fusionnent SANS doublon, une lecture
 * en échec est honnête, le cliché suit la publication (un lecteur qui
 * s'attache ne voit jamais une ligne deux fois).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FluxJournalSortiesTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private class JournalSilencieux : AppLogger {
        override val sessionId: String = "test"

        override fun log(
            level: jo.codeide.core.model.LogLevel,
            tag: String,
            throwable: Throwable?,
            message: () -> String,
        ) = Unit
    }

    private fun entree(
        message: String,
        horodatageMs: Long,
        niveau: LogLevel = LogLevel.INFO,
    ) = LogEntry(
        timestampMillis = horodatageMs,
        sessionId = "session-1",
        level = niveau,
        tag = "Test",
        threadName = "main",
        message = message,
    )

    private fun collecteur(depot: LogRepository, dispatchers: DispatcherProvider) =
        CollecteurSortiesJournal(depot, JournalSilencieux(), dispatchers)

    @Test
    fun `le cliché charge l historique UNE fois et l adapte au modèle unique`() = runTest {
        val depot = InMemoryLogRepository()
        depot.add(entree("ancienne", 10L), entree("récente", 20L))
        val collecteur = collecteur(depot, TestDispatcherProvider(regleMain.dispatcher))
        advanceUntilIdle()
        val cliché = collecteur.cliche()
        assertEquals(2, cliché.size)
        assertEquals("ancienne", cliché[0].message)
        assertEquals(jo.codeide.core.domain.SourceSortie.JOURNAL, cliché[0].source)
        assertEquals("Test", cliché[0].etiquette)
        assertEquals("session-1", cliché[0].identiteSession)
        // Re-attachement : le MÊME cliché (jamais de relecture en plus).
        assertEquals(2, collecteur.cliche().size)
    }

    @Test
    fun `les nouveautés fusionnent sans doublon malgré la fenêtre réémise`() = runTest {
        val depot = InMemoryLogRepository()
        depot.add(entree("ancienne", 10L))
        val collecteur = collecteur(depot, TestDispatcherProvider(regleMain.dispatcher))
        advanceUntilIdle()
        val lotsRecus = ArrayList<List<jo.codeide.core.domain.LigneSortie>>()
        val travail = launch { collecteur.lots.collect { lotsRecus.add(it) } }
        advanceUntilIdle()
        // L'observateur réémet la fenêtre COMPLÈTE à chaque ajout — la
        // fusion ne doit publier QUE la nouveauté.
        depot.add(entree("nouvelle", 30L))
        advanceUntilIdle()
        travail.cancel()
        val publiées = lotsRecus.flatten()
        assertEquals(listOf("nouvelle"), publiées.map { it.message })
    }

    @Test
    fun `une lecture en échec est honnête et le lecteur reste utilisable`() = runTest {
        val depot = InMemoryLogRepository()
        depot.readError = IOException("disque plein")
        val collecteur = collecteur(depot, TestDispatcherProvider(regleMain.dispatcher))
        advanceUntilIdle()
        assertTrue(collecteur.lectureEnEchec.value)
        assertTrue(collecteur.cliche().isEmpty())
        // Les nouveautés continuent d'arriver (le tampon mémoire vit).
        val lotsRecus = ArrayList<List<jo.codeide.core.domain.LigneSortie>>()
        val travail = launch { collecteur.lots.collect { lotsRecus.add(it) } }
        depot.add(entree("vivante", 40L))
        advanceUntilIdle()
        travail.cancel()
        assertEquals(1, lotsRecus.flatten().size)
    }

    @Test
    fun `le flux porte la fenêtre du Journal et livre cliché puis lots`() = runTest {
        val depot = InMemoryLogRepository()
        depot.add(entree("historique", 5L))
        val flux = FluxSortiesJournal(collecteur(depot, TestDispatcherProvider(regleMain.dispatcher)))
        advanceUntilIdle()
        assertEquals(FENETRE_JOURNAL, flux.capaciteFenetre())
        assertEquals(0, flux.generation.value)
        // Le premier lot du flux est le CLICHÉ (l'existant), jamais une
        // émission intempestive — puis le flux reste ouvert.
        val premier = flux.lots().first()
        assertEquals(1, premier.size)
        assertEquals("historique", premier[0].message)
        assertTrue(flux.sequenceArmee.value == null)
        assertTrue(flux.etatExecution.value == null)
    }
}
