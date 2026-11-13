package jo.codeide.core.domain

import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashReportSummary
import kotlinx.coroutines.flow.Flow

/**
 * Accès aux rapports de plantage (section 5.8 du prompt maître).
 *
 * L'implémentation vit dans `core:crash` (fichiers JSON dans
 * `filesDir/crashes/`, état « consulté » par fichier témoin) et est liée par
 * Hilt ; `feature:diagnostics` (étape 12) n'y accède que par cette
 * interface.
 *
 * Contexte d'exécution attendu : toutes les opérations sont suspendues ou
 * renvoient des flots froids — la collecte et les appels se font hors
 * thread principal (l'implémentation délègue au dispatcher d'I/O injecté).
 */
public interface CrashReportRepository {
    /**
     * Observe les résumés des rapports conservés, du plus récent au plus
     * ancien.
     *
     * @return flot froid réémis après chaque mutation (écriture, suppression,
     * changement d'état « consulté »).
     */
    public fun observeSummaries(): Flow<List<CrashReportSummary>>

    /**
     * Lit un rapport complet.
     *
     * @param id identifiant du rapport.
     * @return le rapport, ou `null` s'il n'existe plus (ou est corrompu).
     */
    public suspend fun get(id: String): CrashReport?

    /**
     * Marque un rapport comme consulté (fichier témoin).
     *
     * Appelé quand l'utilisateur ouvre le rapport **ou** ignore la boîte de
     * dialogue du démarrage — les deux actions valent consultation.
     *
     * @param id identifiant du rapport.
     * @return `true` si l'état a été posé.
     */
    public suspend fun markReviewed(id: String): Boolean

    /**
     * Supprime un rapport.
     *
     * @param id identifiant du rapport.
     * @return `true` si un fichier a été supprimé.
     */
    public suspend fun delete(id: String): Boolean

    /**
     * Supprime tous les rapports.
     *
     * @return le nombre de rapports supprimés.
     */
    public suspend fun deleteAll(): Int

    /**
     * Indique s'il existe au moins un rapport non consulté — pilote la
     * boîte de dialogue « Un problème est survenu lors de la dernière
     * session » à l'ouverture de `MainActivity`.
     *
     * @return `true` si un rapport n'a jamais été ouvert ni ignoré.
     */
    public suspend fun hasUnreviewed(): Boolean
}
