package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.testing.FakeCrashReportRepository
import jo.codeide.core.testing.FakeSettingsRepository
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

        var destinationDirecte: String? = null
            private set

        override suspend fun write(entries: List<LogEntry>): AppResult<ExportedLogs> {
            recues = entries
            return resultat
        }

        override suspend fun write(
            entries: List<LogEntry>,
            destinationUri: String,
        ): AppResult<Unit> {
            recues = entries
            destinationDirecte = destinationUri
            return if (resultat is AppResult.Success) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure((resultat as AppResult.Failure).error)
            }
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

/**
 * Tests des cas d'usage de journalisation ajoutés à l'étape 12 : lecture
 * complète (visionneuse), mesure d'occupation disque, réglage de la
 * verbosité (persistance puis application) et export direct à une
 * destination SAF.
 */
class DiagnosticLogUseCasesTest {
    private fun entree(horodatage: Long) =
        LogEntry(
            timestampMillis = horodatage,
            sessionId = "s",
            level = LogLevel.INFO,
            tag = "Test",
            threadName = "main",
            message = "message $horodatage",
        )

    @Test
    fun `lire tout l historique rend les entrees dans l ordre`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.add(entree(1), entree(2), entree(3))

            val lecture = ReadAllLogsUseCase(depot)()

            assertTrue(lecture is AppResult.Success)
            assertEquals(
                listOf("message 1", "message 2", "message 3"),
                (lecture as AppResult.Success).value.map { it.message },
            )
        }

    @Test
    fun `lire tout echoue proprement en erreur typée`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.readError = IOException("disque muet")

            val lecture = ReadAllLogsUseCase(depot)()

            assertTrue(lecture is AppResult.Failure)
        }

    @Test
    fun `mesurer l occupation rend le descriptif du depot`() =
        runTest {
            val depot = InMemoryLogRepository()
            val mesure = MeasureLogDiskUsageUseCase(depot)()
            assertTrue(mesure is AppResult.Success)
        }

    @Test
    fun `regler la verbosite persiste puis applique dans cet ordre`() =
        runTest {
            val parametres = FakeSettingsRepository()
            val appliquees = mutableListOf<jo.codeide.core.model.LogVerbosity>()
            val applier = LogVerbosityApplier { verbosite -> appliquees += verbosite }
            val cas = SetLogVerbosityUseCase(parametres, applier)

            val resultat = cas(jo.codeide.core.model.LogVerbosity.DETAILED)

            assertTrue(resultat is AppResult.Success)
            assertEquals(jo.codeide.core.model.LogVerbosity.DETAILED, parametres.reglages.logLevel)
            assertEquals(listOf(jo.codeide.core.model.LogVerbosity.DETAILED), appliquees)
        }

    @Test
    fun `un echec de persistance laisse le moteur intact`() =
        runTest {
            val parametres = FakeSettingsRepository()
            parametres.writeError = IOException("DataStore muet")
            val appliquees = mutableListOf<jo.codeide.core.model.LogVerbosity>()
            val cas = SetLogVerbosityUseCase(parametres, LogVerbosityApplier { verbosite -> appliquees += verbosite })

            val resultat = cas(jo.codeide.core.model.LogVerbosity.DETAILED)

            assertTrue(resultat is AppResult.Failure)
            assertTrue(appliquees.isEmpty())
        }

    @Test
    fun `exporter directement transmet les entrees et la destination`() =
        runTest {
            val depot = InMemoryLogRepository()
            depot.add(entree(1), entree(2))
            val ecrivain =
                object : LogExportWriter {
                    var recues: List<LogEntry>? = null
                    var destination: String? = null

                    override suspend fun write(entries: List<LogEntry>): AppResult<ExportedLogs> =
                        AppResult.Success(ExportedLogs("logs.zip", "/cache/exports/logs.zip"))

                    override suspend fun write(
                        entries: List<LogEntry>,
                        destinationUri: String,
                    ): AppResult<Unit> {
                        recues = entries
                        destination = destinationUri
                        return AppResult.Success(Unit)
                    }
                }

            val resultat = ExportLogsUseCase(depot, ecrivain)("content://destination/1")

            assertTrue(resultat is AppResult.Success)
            assertEquals(2, ecrivain.recues?.size)
            assertEquals("content://destination/1", ecrivain.destination)
        }
}

/**
 * Tests du cas d'usage d'export des rapports de plantage (étape 12) : les
 * rapports complets sont collectés depuis les résumés, puis remis à
 * l'écrivain — pour le cache comme pour une destination SAF directe.
 */
class CrashReportExportUseCaseTest {
    private fun rapport(
        id: String,
        horodatage: Long,
    ): jo.codeide.core.model.CrashReport =
        jo.codeide.core.model.CrashReport(
            id = id,
            type = jo.codeide.core.model.CrashType.EXCEPTION,
            timestampMillis = horodatage,
            sessionId = "s",
            application =
                jo.codeide.core.model
                    .CrashAppInfo("0.13.0", 1_300, "debug", "jo.codeide"),
            device =
                jo.codeide.core.model.DeviceInfo
                    .inconnu(),
            threadName = "main",
            exception =
                jo.codeide.core.model
                    .FlattenedException("IllegalStateException", "test", emptyList(), null),
            breadcrumbs = emptyList(),
            lastScreen = null,
            processUptimeMs = 0,
            isCrashLoop = false,
        )

    private class FauxEcrivain : CrashReportsExportWriter {
        var recus: List<jo.codeide.core.model.CrashReport>? = null
            private set

        var destination: String? = null
            private set

        override suspend fun write(reports: List<jo.codeide.core.model.CrashReport>): AppResult<ExportedLogs> {
            recus = reports
            return AppResult.Success(ExportedLogs("crashes.zip", "/cache/exports/crashes.zip"))
        }

        override suspend fun write(
            reports: List<jo.codeide.core.model.CrashReport>,
            destinationUri: String,
        ): AppResult<Unit> {
            recus = reports
            destination = destinationUri
            return AppResult.Success(Unit)
        }
    }

    @Test
    fun `l export collecte les rapports complets depuis les resumes`() =
        runTest {
            val depot = FakeCrashReportRepository()
            depot.peupler(rapport("un", 1_000L), rapport("deux", 2_000L))
            val ecrivain = FauxEcrivain()

            val resultat = ExportCrashReportsUseCase(depot, ecrivain)()

            assertTrue(resultat is AppResult.Success)
            // Du plus récent au plus ancien : l'ordre du dépôt fait foi.
            assertEquals(listOf("deux", "un"), ecrivain.recus?.map { it.id })
            assertEquals("crashes.zip", (resultat as AppResult.Success).value.fileName)
        }

    @Test
    fun `l export direct transmet la destination choisie`() =
        runTest {
            val depot = FakeCrashReportRepository()
            depot.peupler(rapport("un", 1_000L))
            val ecrivain = FauxEcrivain()

            val resultat = ExportCrashReportsUseCase(depot, ecrivain)("content://destination/2")

            assertTrue(resultat is AppResult.Success)
            assertEquals("content://destination/2", ecrivain.destination)
            assertEquals(1, ecrivain.recus?.size)
        }
}
