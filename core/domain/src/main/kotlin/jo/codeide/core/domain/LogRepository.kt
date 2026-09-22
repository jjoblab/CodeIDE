package jo.codeide.core.domain

import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.flow.Flow

/**
 * Accès en lecture aux journaux applicatifs (section 5.7 du prompt maître).
 *
 * L'implémentation vit dans `core:logging` et est liée par Hilt ; les
 * fonctionnalités (notamment `feature:diagnostics`, étape 12) n'y accèdent
 * que par cette interface.
 *
 * Contexte d'exécution attendu : [readAll], [diskUsage] et [clear] sont
 * suspendues et doivent être appelées hors thread principal (l'implémentation
 * délègue au dispatcher d'I/O injecté) ; [observeRecent] est un flot froid,
 * collectable depuis n'importe quel contexte.
 */
public interface LogRepository {
    /**
     * Observe les entrées récentes (tampon mémoire des *breadcrumbs*).
     *
     * La première émission contient l'état courant du tampon, puis chaque
     * nouvelle entrée le complète — la valeur émise est toujours la fenêtre
     * des [limit] entrées les plus récentes.
     *
     * @param limit taille de la fenêtre observée.
     * @return flot des dernières entrées, re-émis à chaque ajout.
     */
    public fun observeRecent(limit: Int = DEFAULT_OBSERVE_LIMIT): Flow<List<LogEntry>>

    /**
     * Lit **toutes** les entrées persistées (fichier courant + archives),
     * dans l'ordre chronologique. Les entrées encore en file d'attente
     * d'écriture asynchrone (au plus ~500 ms) peuvent ne pas y figurer.
     *
     * @return les entrées lues sur disque, éventuellement vide.
     */
    public suspend fun readAll(): List<LogEntry>

    /**
     * Mesure la place occupée par les fichiers de journal.
     *
     * @return l'occupation disque (octets, nombre de fichiers).
     */
    public suspend fun diskUsage(): LogDiskUsage

    /**
     * Efface tous les journaux (fichiers et tampon mémoire).
     */
    public suspend fun clear()

    public companion object {
        /** Taille de fenêtre d'observation par défaut (capacité du tampon). */
        public const val DEFAULT_OBSERVE_LIMIT: Int = 200
    }
}

/**
 * Occupation disque des fichiers de journal.
 *
 * @property bytes taille cumulée des fichiers, en octets.
 * @property fileCount nombre de fichiers (fichier courant + archives).
 */
public data class LogDiskUsage(
    public val bytes: Long,
    public val fileCount: Int,
)
