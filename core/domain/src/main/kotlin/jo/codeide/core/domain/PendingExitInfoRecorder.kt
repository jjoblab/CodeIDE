package jo.codeide.core.domain

/**
 * Port « détection au démarrage » des plantages non capturés (section 5.8).
 *
 * Android conserve les causes de mort d'un processus dans
 * `ApplicationExitInfo` (API 30+) : l'implémentation (`core:crash`) y lit
 * les **ANR** et **plantages natifs** de la session précédente, les
 * convertit en rapports et mémorise le dernier horodatage traité pour ne
 * jamais créer de doublon. La lecture est hors thread principal.
 */
public fun interface PendingExitInfoRecorder {
    /**
     * Enregistre les sorties de processus non encore traitées.
     *
     * @return le nombre de rapports créés (zéro si rien de nouveau, API
     * antérieure à 30, ou service inaccessible).
     */
    public suspend fun recordPending(): Int
}
