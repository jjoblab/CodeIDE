package jo.codeide.core.testing

import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des fakes de plantage : le dépôt en mémoire doit se comporter comme
 * l'implémentation réelle (tri, état consulté, réémission), sinon les tests
 * des cas d'usage perdraient leur sens.
 */
class FakesPlantagesTest {
    private fun rapport(
        id: String,
        horodatage: Long,
    ): CrashReport =
        CrashReport(
            id = id,
            type = CrashType.ANR,
            timestampMillis = horodatage,
            sessionId = "",
            application = CrashAppInfo("0.4.0", 400, "debug", "jo.codeide"),
            device = DeviceInfo.inconnu(),
            threadName = "main",
            exception = FlattenedException("android.app.ApplicationExitInfo", "bloqué", emptyList(), null),
            breadcrumbs = emptyList(),
            lastScreen = null,
            processUptimeMs = 0,
            isCrashLoop = false,
        )

    @Test
    fun `le dépôt en mémoire trie, marque et réémet correctement`() =
        runTest {
            val depot = FakeCrashReportRepository()
            depot.peupler(rapport("un", 1_000L), rapport("deux", 2_000L))

            // Tri du plus récent au plus ancien, non consultés par défaut.
            assertEquals(listOf("deux", "un"), depot.observeSummaries().first().map { it.id })
            assertTrue(depot.hasUnreviewed())

            // La consultation se voit dans les résumés et dans l'inspection.
            assertTrue(depot.markReviewed("deux"))
            assertTrue(depot.estConsulte("deux"))
            assertTrue(
                depot
                    .observeSummaries()
                    .first()
                    .first { it.id == "deux" }
                    .isReviewed,
            )
            assertFalse(
                depot
                    .observeSummaries()
                    .first()
                    .first { it.id == "un" }
                    .isReviewed,
            )

            // Un rapport inconnu ne peut pas être marqué ni supprimé.
            assertFalse(depot.markReviewed("fantôme"))
            assertFalse(depot.delete("fantôme"))
        }

    @Test
    fun `le dépôt en mémoire supprime individuellement puis totalement`() =
        runTest {
            val depot = FakeCrashReportRepository()
            depot.peupler(rapport("un", 1_000L), rapport("deux", 2_000L))

            assertTrue(depot.delete("un"))
            assertEquals(1, depot.deleteAll())
            assertTrue(depot.observeSummaries().first().isEmpty())
            assertFalse(depot.hasUnreviewed())
            assertEquals(0, depot.deleteAll())
        }

    @Test
    fun `le port de sorties compte les appels et rend la valeur choisie`() =
        runTest {
            val port = FakePendingExitInfoRecorder()

            assertEquals(0, port.recordPending())
            port.aRetourner = 3

            assertEquals(3, port.recordPending())
            assertEquals(2, port.appels)
        }
}
