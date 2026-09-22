package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.testing.InMemoryLogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests de [LogConfig] : les bornes du prompt (section 5.7) et le rejet
 * des valeurs impossibles.
 */
class LogConfigTest {
    @Test
    fun `les valeurs par défaut reprennent les bornes du prompt`() {
        val config = LogConfig()

        assertEquals(LogLevel.INFO, config.minLevel)
        assertEquals(1_048_576L, config.maxFileSizeBytes)
        assertEquals(5, config.maxArchiveFiles)
        assertEquals(7, config.retentionDays)
        assertTrue(config.fileLoggingEnabled)
    }

    @Test
    fun `debugDefault laisse tout passer, releaseDefault filtre à INFO`() {
        assertEquals(LogLevel.DEBUG, LogConfig.debugDefault().minLevel)
        assertEquals(LogLevel.INFO, LogConfig.releaseDefault().minLevel)
    }

    @Test
    fun `les valeurs impossibles sont rejetées`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LogConfig(maxFileSizeBytes = 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LogConfig(maxArchiveFiles = -1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LogConfig(retentionDays = 0)
        }
    }
}

/**
 * Tests des cas d'usage de journalisation : observation, export (succès et
 * échec de lecture), effacement (succès et échec).
 */
class LogUseCasesTest {
    private fun entree(horodatage: Long) =
        LogEntry(
            timestampMillis = horodatage,
            sessionId = "s",
            level = LogLevel.INFO,
            tag = "Test",
            threadName = "main",
            message = "message $horodatage",
        )

    /** Écrivain d'export qui enregistre l'appel et rend un résultat fixé. */
    private class FakeExportWriter(
        private val resultat: AppResult<ExportedLogs>,
    ) : LogExportWriter {
        var recues: List<LogEntry>? = null

        override suspend fun write(entries: List<LogEntry>): AppResult<ExportedLogs> {
            recues = entries
            return resultat
        }
    }

    @Test
    fun `observer les journaux renvoie la fenêtre du dépôt`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.add(entree(1), entree(2), entree(3))
            val cas = ObserveLogsUseCase(depot)

            val fenetre = cas(limit = 2).first()

            assertEquals(2, fenetre.size)
            assertEquals("message 2", fenetre.first().message)
        }

    @Test
    fun `exporter réussit et transmet toutes les entrées à l'écrivain`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.add(entree(1), entree(2))
            val ecrivain = FakeExportWriter(AppResult.Success(ExportedLogs("logs.zip", "/cache/exports/logs.zip")))
            val cas = ExportLogsUseCase(depot, ecrivain)

            val resultat = cas()

            assertEquals(
                AppResult.Success(ExportedLogs("logs.zip", "/cache/exports/logs.zip")),
                resultat,
            )
            assertEquals(2, ecrivain.recues?.size)
        }

    @Test
    fun `exporter traduit un échec de lecture en erreur typée`() =
        runTest {
            val depot = InMemoryLogRepository().apply { readError = IOException("illisible") }
            val cas = ExportLogsUseCase(depot, FakeExportWriter(AppResult.Success(ExportedLogs("x", "x"))))

            val resultat = cas()

            assertTrue(resultat is AppResult.Failure)
            resultat as AppResult.Failure
            val erreur = resultat.error as jo.codeide.core.model.AppError.Storage
            assertEquals(jo.codeide.core.model.AppError.StorageReason.Io, erreur.reason)
        }

    @Test
    fun `effacer réussit quand le dépôt s'efface`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.add(entree(1))
            val cas = ClearLogsUseCase(depot)

            assertEquals(AppResult.Success(Unit), cas())
            assertTrue(depot.readAll().isEmpty())
        }

    @Test
    fun `effacer traduit un échec d'entrée-sortie en erreur typée`() =
        runTest {
            val depot = InMemoryLogRepository().apply { clearError = IOException("verrou") }
            val cas = ClearLogsUseCase(depot)

            val resultat = cas()

            assertTrue(resultat is AppResult.Failure)
        }
}
