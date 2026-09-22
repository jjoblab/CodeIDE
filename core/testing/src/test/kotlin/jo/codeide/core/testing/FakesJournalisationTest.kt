package jo.codeide.core.testing

import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests des doubles de test de journalisation : leurs comportements propres
 * (évaluation immédiate, fenêtre d'observation, robinets d'erreur) sont la
 * base de confiance des tests des cas d'usage.
 */
class FakesJournalisationTest {
    private fun entree(
        niveau: LogLevel,
        message: String,
        horodatage: Long,
    ): LogEntry =
        LogEntry(
            timestampMillis = horodatage,
            sessionId = "s",
            level = niveau,
            tag = "Test",
            threadName = "main",
            message = message,
        )

    @Test
    fun `FakeAppLogger enregistre les entrées dans l'ordre, message évalué`() {
        val fake = FakeAppLogger()
        var evaluations = 0

        fake.i("App") {
            evaluations++
            "premier"
        }
        fake.w("Storage", IllegalStateException("perm")) {
            evaluations++
            "deuxième"
        }

        assertEquals(2, evaluations)
        assertEquals(2, fake.entries.size)
        assertEquals(LogLevel.INFO, fake.entries[0].level)
        assertEquals("premier", fake.entries[0].message)
        assertEquals("deuxième", fake.entries[1].message)
        assertEquals("perm", (fake.entries[1].throwable as IllegalStateException).message)
    }

    @Test
    fun `InMemoryLogRepository observe la fenêtre des dernières entrées`() =
        runTest {
            val depot = InMemoryLogRepository()
            repeat(30) { index ->
                depot.add(entree(LogLevel.INFO, "message $index", horodatage = index.toLong()))
            }

            val fenetre = depot.observeRecent(limit = 10).first()

            assertEquals(10, fenetre.size)
            assertEquals("message 20", fenetre.first().message)
            assertEquals("message 29", fenetre.last().message)
        }

    @Test
    fun `InMemoryLogRepository lit tout, s'efface et simule les erreurs`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.add(
                entree(LogLevel.DEBUG, "a", horodatage = 1),
                entree(LogLevel.ERROR, "b", horodatage = 2),
            )
            assertEquals(2, depot.readAll().size)

            depot.clearError = IOException("disque plein")
            assertTrue(depot.clearError != null)
            var levee: IOException? = null
            try {
                depot.clear()
            } catch (e: IOException) {
                levee = e
            }
            assertEquals("disque plein", levee?.message)

            depot.clearError = null
            depot.clear()
            assertTrue(depot.readAll().isEmpty())
        }
}
