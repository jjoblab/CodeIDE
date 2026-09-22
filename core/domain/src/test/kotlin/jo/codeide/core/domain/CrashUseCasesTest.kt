package jo.codeide.core.domain

import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.testing.FakeCrashReportRepository
import jo.codeide.core.testing.FakePendingExitInfoRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des cas d'usage des rapports de plantage (section 5.8) : lecture,
 * consultation, suppression et détection au démarrage — sur le fake du
 * dépôt, les fakes n'ayant pas à être testés ici.
 */
class CrashUseCasesTest {
    private fun rapport(
        id: String,
        horodatage: Long,
    ): CrashReport =
        CrashReport(
            id = id,
            type = CrashType.EXCEPTION,
            timestampMillis = horodatage,
            sessionId = "session",
            application = CrashAppInfo("0.4.0", 400, "debug", "jo.codeide"),
            device = DeviceInfo.inconnu(),
            threadName = "main",
            exception = FlattenedException("java.lang.RuntimeException", "boum $id", emptyList(), null),
            breadcrumbs = emptyList(),
            lastScreen = null,
            processUptimeMs = 0,
            isCrashLoop = false,
        )

    /** Peuple le dépôt et marque éventuellement des rapports consultés. */
    private fun depotePeuple(): FakeCrashReportRepository {
        val depot = FakeCrashReportRepository()
        depot.peupler(
            rapport("ancien", horodatage = 1_000L),
            rapport("recent", horodatage = 2_000L),
        )
        return depot
    }

    @Test
    fun `observer renvoie les résumés du plus récent au plus ancien`() =
        runTest {
            val depot = depotePeuple()

            val resumes = ObserveCrashReportsUseCase(depot).invoke().first()

            assertEquals(listOf("recent", "ancien"), resumes.map { it.id })
            assertEquals(CrashType.EXCEPTION, resumes.first().type)
            assertEquals("java.lang.RuntimeException", resumes.first().exceptionClassName)
        }

    @Test
    fun `lire un rapport existant puis un rapport absent`() =
        runTest {
            val depot = depotePeuple()
            val lire = GetCrashReportUseCase(depot)

            assertEquals("recent", lire.invoke("recent")?.id)
            assertNull(lire.invoke("introuvable"))
        }

    @Test
    fun `le dernier non consulté suit la consultation`() =
        runTest {
            val depot = depotePeuple()
            val dernierNonConsulte = GetLatestUnreviewedCrashReportUseCase(depot)
            val marquer = MarkCrashReportReviewedUseCase(depot)

            assertEquals("recent", dernierNonConsulte.invoke()?.id)
            assertTrue(marquer.invoke("recent"))
            // Le plus ancien devient le dernier non consulté.
            assertEquals("ancien", dernierNonConsulte.invoke()?.id)
            assertTrue(marquer.invoke("ancien"))
            assertNull(dernierNonConsulte.invoke())
        }

    @Test
    fun `supprimer un rapport puis tous les rapports`() =
        runTest {
            val depot = depotePeuple()
            val supprimer = DeleteCrashReportUseCase(depot)
            val toutSupprimer = DeleteAllCrashReportsUseCase(depot)

            assertTrue(supprimer.invoke("ancien"))
            assertFalse(supprimer.invoke("ancien"))
            assertEquals(1, toutSupprimer.invoke())
            assertTrue(ObserveCrashReportsUseCase(depot).invoke().first().isEmpty())
        }

    @Test
    fun `la présence d'un non consulté suit les mutations`() =
        runTest {
            val depot = depotePeuple()
            val presence = HasUnreviewedCrashReportsUseCase(depot)
            val marquer = MarkCrashReportReviewedUseCase(depot)

            assertTrue(presence.invoke())
            marquer.invoke("recent")
            assertTrue(presence.invoke())
            marquer.invoke("ancien")
            assertFalse(presence.invoke())
        }

    @Test
    fun `l'enregistrement des sorties délègue au port`() =
        runTest {
            val port = FakePendingExitInfoRecorder().apply { aRetourner = 2 }
            val enregistrer = RecordPendingExitInfosUseCase(port)

            assertEquals(2, enregistrer.invoke())
            assertEquals(1, port.appels)
        }
}
