package jo.codeide.core.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.LogExportWriter
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de l'export de journaux (section 5.7) : archive
 * `codeide-logs-<date>.zip` dans `cacheDir/exports/`, contenant les
 * journaux (JSON Lines) et `device-info.txt`.
 *
 * Les entrées sont expurgées **à l'écriture** — l'export n'a donc plus
 * qu'à empaqueter. Les résumés de rapports de plantage rejoindront
 * l'archive à l'étape 3, sans changer l'interface.
 *
 * Nettoyage automatique : seules les [LoggingLimits.MAX_EXPORTS_KEPT]
 * archives les plus récentes sont conservées.
 */
@Singleton
internal class LogExportWriterImpl
    @Inject
    constructor(
        // Cible d'annotation explicite : le qualificatif Hilt s'adresse au
        // paramètre du constructeur (Kotlin 2.2 sans ambiguïté de site).
        @param:ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
        private val timeProvider: TimeProvider,
        private val buildInfo: BuildInfo,
        private val deviceSummary: DeviceSummary,
        private val json: Json,
    ) : LogExportWriter {
        override suspend fun write(entries: List<LogEntry>): AppResult<ExportedLogs> =
            withContext(dispatchers.io) {
                try {
                    val dossier =
                        File(context.cacheDir, LoggingLimits.EXPORTS_DIR_NAME).apply {
                            mkdirs()
                        }

                    val nomArchive = nomArchive(timeProvider.nowMillis())
                    val destination = File(dossier, nomArchive)
                    FileOutputStream(destination).use { flux ->
                        ZipOutputStream(flux).use { archive ->
                            archive.putNextEntry(ZipEntry(LoggingLimits.ZIP_ENTRY_LOGS))
                            entries.forEach { entree ->
                                archive.write(
                                    (json.encodeToString(LogEntry.serializer(), entree) + "\n")
                                        .toByteArray(Charsets.UTF_8),
                                )
                            }
                            archive.closeEntry()

                            archive.putNextEntry(ZipEntry(LoggingLimits.ZIP_ENTRY_DEVICE_INFO))
                            archive.write(
                                infosAppareil(entries, timeProvider.nowMillis()).toByteArray(Charsets.UTF_8),
                            )
                            archive.closeEntry()
                        }
                    }
                    // Nettoyage APRÈS écriture : le plafond compte l'archive
                    // qui vient d'être produite.
                    supprimerAnciensExports(dossier)
                    AppResult.Success(ExportedLogs(fileName = nomArchive, location = destination.absolutePath))
                } catch (e: IOException) {
                    AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
                }
            }

        /** Nom de l'archive : `codeide-logs-yyyy-MM-dd-HHmmss.zip` (UTC). */
        private fun nomArchive(nowMillis: Long): String {
            val horodatage =
                SimpleDateFormat(LoggingLimits.EXPORT_TIMESTAMP_FORMAT, Locale.US)
                    .apply {
                        // UTC : le nom ne doit pas dépendre du fuseau de l'appareil.
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date(nowMillis))
            return "${LoggingLimits.EXPORT_FILE_PREFIX}$horodatage.zip"
        }

        /**
         * Contenu texte de `device-info.txt` : version, build, appareil,
         * sessions distinctes et volume — rien de personnel.
         */
        private fun infosAppareil(
            entries: List<LogEntry>,
            nowMillis: Long,
        ): String =
            buildString {
                appendLine("CodeIDE ${buildInfo.versionName} (${buildInfo.versionCode}, ${buildInfo.buildType})")
                appendLine(deviceSummary.summary())
                appendLine("Généré le : $nowMillis")
                appendLine("Entrées exportées : ${entries.size}")
                appendLine("Sessions distinctes : ${entries.map { it.sessionId }.distinct().size}")
                appendLine("Niveau minimal du journal : ${entries.minOfOrNull { it.level } ?: "aucune entrée"}")
            }

        /** Ne conserve que les plus récentes archives d'export. */
        private fun supprimerAnciensExports(dossier: File) {
            dossier
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith(LoggingLimits.EXPORT_FILE_PREFIX) }
                .sortedByDescending { it.lastModified() }
                .drop(LoggingLimits.MAX_EXPORTS_KEPT)
                .forEach { it.delete() }
        }
    }
