package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CrashReport
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Abstraction de l'écriture de l'archive des rapports de plantage
 * (section 5.8 — implémentée dans `core:crash`, liée par Hilt).
 *
 * L'archive contient un fichier par rapport conservé (JSON complet + mise
 * en forme lisible) — exactement ce que l'écran dédié partage pour un
 * rapport isolé, regroupé pour la visionneuse de diagnostics (étape 12).
 *
 * Contrat : ne lève **jamais** d'exception (hormis
 * [kotlinx.coroutines.CancellationException]) — toute défaillance est
 * rendue sous forme d'[AppResult.Failure].
 */
public interface CrashReportsExportWriter {
    /**
     * Écrit l'archive de tous les rapports fournis dans le répertoire
     * d'export du cache (action « Exporter » / « Partager »).
     *
     * @param reports rapports complets à archiver.
     * @return le descriptif de l'archive produite, ou l'erreur typée.
     */
    public suspend fun write(reports: List<CrashReport>): AppResult<ExportedLogs>

    /**
     * Écrit l'archive des rapports **directement** à la destination SAF
     * choisie par l'utilisateur.
     *
     * @param reports rapports complets à archiver.
     * @param destinationUri URI de document (`application/zip`) ouverte en
     * écriture par le sélecteur système.
     * @return le succès, ou l'erreur typée rencontrée.
     */
    public suspend fun write(
        reports: List<CrashReport>,
        destinationUri: String,
    ): AppResult<Unit>
}

/**
 * Cas d'usage « exporter les rapports de plantage » (section 5.8) —
 * action « Exporter » de l'onglet Plantages de la visionneuse de
 * diagnostics (étape 12).
 *
 * Collecte les rapports complets (le résumé suffit à la liste, pas à
 * l'archive) puis délègue l'écriture au port [CrashReportsExportWriter].
 *
 * Contexte d'exécution attendu : suspendu, hors thread principal.
 */
public class ExportCrashReportsUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
        private val exportWriter: CrashReportsExportWriter,
    ) {
        /**
         * Produit l'archive de tous les rapports conservés, dans le cache.
         *
         * @return le descriptif de l'archive, ou une erreur typée. Une liste
         * vide de rapports produit une archive valide (l'appelant décide s'il
         * désactive l'action quand la liste est vide).
         */
        public suspend operator fun invoke(): AppResult<ExportedLogs> {
            val rapports = lireRapports()
            return exportWriter.write(rapports)
        }

        /**
         * Produit l'archive de tous les rapports conservés **directement** à
         * la destination choisie.
         *
         * @param destinationUri URI de document ouverte en écriture par le
         * sélecteur SAF.
         * @return le succès, ou une erreur typée.
         */
        public suspend operator fun invoke(destinationUri: String): AppResult<Unit> {
            val rapports = lireRapports()
            return exportWriter.write(rapports, destinationUri)
        }

        /** Lit les rapports complets à partir des résumés conservés. */
        private suspend fun lireRapports(): List<CrashReport> {
            val resumes = repository.observeSummaries().first()
            return resumes.mapNotNull { resume -> repository.get(resume.id) }
        }
    }
