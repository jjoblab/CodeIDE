package jo.codeide.core.crash

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.CrashReportsExportWriter
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CrashReport
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de l'archive des rapports de plantage (section 5.8,
 * consommée par la visionneuse de diagnostics — étape 12).
 *
 * Archive `codeide-crashes-<date>.zip` dans `cacheDir/exports/` — le même
 * répertoire d'export que les journaux (le FileProvider de l'application
 * n'expose que lui, section 5.7) — contenant, pour chaque rapport conservé,
 * le JSON complet et la mise en forme lisible, exactement comme le partage
 * d'un rapport isolé depuis l'écran dédié.
 */
@Singleton
internal class CrashReportsExportWriterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
        private val timeProvider: TimeProvider,
    ) : CrashReportsExportWriter {
        override suspend fun write(reports: List<CrashReport>): AppResult<ExportedLogs> =
            withContext(dispatchers.io) {
                try {
                    val dossier =
                        File(context.cacheDir, EXPORTS_DIR_NAME).apply {
                            mkdirs()
                        }
                    val nomArchive = nomArchive(timeProvider.nowMillis())
                    val destination = File(dossier, nomArchive)
                    FileOutputStream(destination).use { flux -> ecrireArchive(reports, flux) }
                    AppResult.Success(ExportedLogs(fileName = nomArchive, location = destination.absolutePath))
                } catch (e: IOException) {
                    AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
                }
            }

        override suspend fun write(
            reports: List<CrashReport>,
            destinationUri: String,
        ): AppResult<Unit> =
            withContext(dispatchers.io) {
                try {
                    val uri = destinationUri.toUri()
                    context.contentResolver.openOutputStream(uri, "w")?.use { flux ->
                        ecrireArchive(reports, flux)
                    } ?: return@withContext AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.NotWritable, "openOutputStream"),
                    )
                    AppResult.Success(Unit)
                } catch (e: IOException) {
                    AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
                } catch (e: SecurityException) {
                    // Destination SAF révoquée entre la sélection et l'écriture :
                    // échec typé, jamais une exception jusqu'à l'UI.
                    AppResult.Failure(AppError.Storage(AppError.StorageReason.PermissionLost, e.javaClass.simpleName))
                }
            }

        /**
         * Écrit l'archive vers le flux : par rapport, le JSON complet et la
         * mise en forme lisible ; puis un index textuel des rapports.
         */
        private fun ecrireArchive(
            reports: List<CrashReport>,
            flux: OutputStream,
        ) {
            ZipOutputStream(flux).use { archive ->
                reports.forEach { rapport ->
                    archive.putNextEntry(ZipEntry("rapport-${rapport.id}.json"))
                    archive.write(
                        CrashReportJson
                            .ecrire(rapport, CrashReportJson.Reduction.COMPLET)
                            .toString()
                            .toByteArray(Charsets.UTF_8),
                    )
                    archive.closeEntry()

                    archive.putNextEntry(ZipEntry("rapport-${rapport.id}.txt"))
                    archive.write(CrashReportFormatter.texte(rapport).toByteArray(Charsets.UTF_8))
                    archive.closeEntry()
                }
                archive.putNextEntry(ZipEntry("index.txt"))
                archive.write(index(reports).toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
        }

        /** Index textuel des rapports archivés (une ligne par rapport). */
        private fun index(reports: List<CrashReport>): String =
            buildString {
                appendLine("CodeIDE — ${reports.size} rapport(s) de plantage")
                appendLine("Généré le : ${timeProvider.nowMillis()}")
                reports.forEach { rapport ->
                    appendLine(
                        "${rapport.id} — ${rapport.type} — ${rapport.timestampMillis} — ${rapport.exception.className}",
                    )
                }
            }

        /** Nom de l'archive : `codeide-crashes-yyyy-MM-dd-HHmmss.zip` (UTC). */
        private fun nomArchive(nowMillis: Long): String {
            val horodatage =
                SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
                    .apply {
                        // UTC : le nom ne doit pas dépendre du fuseau de l'appareil.
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date(nowMillis))
            return "codeide-crashes-$horodatage.zip"
        }

        private companion object {
            /**
             * Répertoire d'export partagé avec les journaux (section 5.7) —
             * doit rester synchronisé avec le FileProvider de l'application
             * (`cache-path` « exports/ ») et `LoggingLimits.EXPORTS_DIR_NAME`.
             */
            const val EXPORTS_DIR_NAME = "exports"
        }
    }
