package jo.codeide.core.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Tests de l'export zip (critère d'acceptation de l'étape 2 : export zip
 * valide) — contenu, validité, nettoyage des anciens exports, échec propre
 * quand le répertoire d'export est inutilisable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LogExportWriterImplTest {
    private companion object {
        const val HORODATAGE_EXPORT = 1_767_225_600_000L // 2026-01-01T00:00:00Z
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun entree(message: String): LogEntry =
        LogEntry(
            timestampMillis = 0L,
            sessionId = "session-a",
            level = LogLevel.INFO,
            tag = "Test",
            threadName = "main",
            message = message,
        )

    private fun ecrivain(): LogExportWriterImpl {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        return LogExportWriterImpl(
            context = contexte,
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
            timeProvider = TimeProvider { HORODATAGE_EXPORT },
            buildInfo = BuildInfo("0.3.0", 300, "debug"),
            deviceSummary = DeviceSummary { "Fabricant Modèle (Android 16, API 36) — arm64-v8a, fr-FR" },
            json = json,
        )
    }

    @Test
    fun `produit une archive valide avec journaux et infos d'appareil`() =
        runTest(UnconfinedTestDispatcher()) {
            val resultat = ecrivain().write(listOf(entree("première"), entree("deuxième")))

            assertTrue(resultat is AppResult.Success)
            val exporte = (resultat as AppResult.Success).value
            assertEquals("codeide-logs-2026-01-01-000000.zip", exporte.fileName)

            ZipFile(File(exporte.location)).use { archive ->
                val noms = archive.entries().toList().map { it.name }
                assertEquals(
                    setOf(LoggingLimits.ZIP_ENTRY_LOGS, LoggingLimits.ZIP_ENTRY_DEVICE_INFO),
                    noms.toSet(),
                )

                val lignes =
                    archive
                        .getInputStream(archive.getEntry(LoggingLimits.ZIP_ENTRY_LOGS))
                        .bufferedReader()
                        .readLines()
                        .filter { it.isNotBlank() }
                assertEquals(2, lignes.size)
                assertEquals(
                    listOf("première", "deuxième"),
                    lignes.map { json.decodeFromString(LogEntry.serializer(), it).message },
                )

                val infos =
                    archive
                        .getInputStream(archive.getEntry(LoggingLimits.ZIP_ENTRY_DEVICE_INFO))
                        .bufferedReader()
                        .readText()
                assertTrue(infos.contains("0.3.0"))
                assertTrue(infos.contains("debug"))
                assertTrue(infos.contains("Fabricant Modèle"))
            }
        }

    @Test
    fun `échoue proprement quand le répertoire d'export est inutilisable`() =
        runTest(UnconfinedTestDispatcher()) {
            val contexte = ApplicationProvider.getApplicationContext<Context>()
            // Un fichier porte le nom du répertoire attendu : l'ouverture du
            // zip lève une IOException — traduite en erreur typée.
            File(contexte.cacheDir, LoggingLimits.EXPORTS_DIR_NAME).apply {
                parentFile?.mkdirs()
                writeText("je ne suis pas un répertoire")
            }

            val resultat = ecrivain().write(listOf(entree("x")))

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error as AppError.Storage
            assertEquals(AppError.StorageReason.Io, erreur.reason)
        }

    @Test
    fun `ne conserve que les cinq exports les plus récents`() =
        runTest(UnconfinedTestDispatcher()) {
            val contexte = ApplicationProvider.getApplicationContext<Context>()
            val dossier = File(contexte.cacheDir, LoggingLimits.EXPORTS_DIR_NAME).apply { mkdirs() }
            repeat(6) { index ->
                File(dossier, "${LoggingLimits.EXPORT_FILE_PREFIX}ancien-$index.zip").apply {
                    writeText("ancien $index")
                    setLastModified(1_000_000L + index)
                }
            }

            val resultat = ecrivain().write(listOf(entree("export frais")))

            assertTrue(resultat is AppResult.Success)
            val restants = dossier.listFiles().orEmpty().map { it.name }
            assertEquals(LoggingLimits.MAX_EXPORTS_KEPT, restants.size)
            assertTrue(restants.contains("codeide-logs-2026-01-01-000000.zip"))
            // L'ancien export le plus frais survit, les autres ont été supprimés.
            assertTrue(restants.contains("${LoggingLimits.EXPORT_FILE_PREFIX}ancien-5.zip"))
        }
}
