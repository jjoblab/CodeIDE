package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.model.AppError
import jo.codeide.core.model.CrashReportSummary

/**
 * État observable de l'onglet Plantages (étape 12).
 *
 * @property chargement première lecture des résumés en cours.
 * @property rapports résumés du plus récent au plus ancien.
 * @property erreur dernière erreur d'opération typée, ou `null`.
 */
data class EtatPlantages(
    val chargement: Boolean = true,
    val rapports: List<CrashReportSummary> = emptyList(),
    val erreur: AppError? = null,
)

/** Actions intentionnelles de l'onglet Plantages. */
sealed interface ActionPlantages {
    /** Supprime un rapport (après confirmation côté UI). */
    data class Supprimer(
        val id: String,
    ) : ActionPlantages

    /** Supprime tous les rapports (après confirmation côté UI). */
    data object ToutSupprimer : ActionPlantages

    /** Produit l'archive de tous les rapports pour le partage. */
    data object Exporter : ActionPlantages
}

/** Effets ponctuels de l'onglet Plantages. */
sealed interface EffetPlantages {
    /** L'archive de partage est prête — la feuille de partage doit s'ouvrir. */
    data class ArchivePrete(
        val archive: ExportedLogs,
    ) : EffetPlantages
}
