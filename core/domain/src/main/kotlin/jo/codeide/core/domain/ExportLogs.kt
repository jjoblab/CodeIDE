package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import java.io.IOException
import javax.inject.Inject

/**
 * Archive de journaux produite par un [LogExportWriter].
 *
 * @property fileName nom lisible de l'archive (ex. `codeide-logs-2026-09-22-1530.zip`).
 * @property location emplacement opaque du fichier produit (chemin interne,
 * non destiné à l'affichage) — l'appelant applicatif le convertit en URI
 * partageable via FileProvider.
 */
public data class ExportedLogs(
    public val fileName: String,
    public val location: String,
)

/**
 * Abstraction de l'écriture de l'archive d'export (implémentée dans
 * `core:logging`, liée par Hilt).
 *
 * Le cas d'usage [ExportLogsUseCase] orchestre ; l'implémentation produit
 * le fichier zip (journaux + informations d'appareil) dans le répertoire
 * d'export du cache et nettoie les anciens exports.
 *
 * Contrat : ne lève **jamais** d'exception (hormis [kotlinx.coroutines.CancellationException])
 * — toute défaillance est rendue sous forme d'[AppResult.Failure].
 */
public interface LogExportWriter {
    /**
     * Écrit l'archive des entrées fournies.
     *
     * @param entries entrées déjà expurgées, dans l'ordre chronologique.
     * @return le descriptif de l'archive produite, ou l'erreur typée.
     */
    public suspend fun write(entries: List<LogEntry>): AppResult<ExportedLogs>
}

/**
 * Cas d'usage « exporter les journaux » (section 5.7) : lit tout l'historique
 * persisté puis produit une archive zip dans `cache/exports/`.
 *
 * L'archive contient les journaux au format JSON Lines et un fichier
 * `device-info.txt` ; les résumés des rapports de plantage rejoindront
 * l'export à l'étape 3, sans changer ce contrat.
 *
 * Contexte d'exécution attendu : suspendu, hors thread principal (les I/O
 * sont déléguées au dispatcher d'I/O injecté de l'implémentation).
 */
public class ExportLogsUseCase
    @Inject
    constructor(
        private val repository: LogRepository,
        private val exportWriter: LogExportWriter,
    ) {
        /**
         * Produit l'archive complète des journaux.
         *
         * @return le descriptif de l'archive, ou une erreur typée (lecture
         * ou écriture impossibles).
         */
        public suspend operator fun invoke(): AppResult<ExportedLogs> =
            try {
                val entries = repository.readAll()
                exportWriter.write(entries)
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                throw annulation
            } catch (e: IOException) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
            }
    }
