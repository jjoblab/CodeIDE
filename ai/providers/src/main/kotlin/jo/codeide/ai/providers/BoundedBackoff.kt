package jo.codeide.ai.providers

/**
 * Backoff borné (ADR 0123 point 5) : reprise **uniquement** pour 429/5xx
 * et **uniquement avant le premier jeton reçu** — jamais de reprise
 * silencieuse d'un flux commencé.
 *
 * Stratégie du délai :
 * 1. si le fournisseur a annoncé `Retry-After` (entier), ce délai prime,
 *    borné à [maxDelayMs] ;
 * 2. sinon délai exponentiel `baseDelayMs × 2^(tentative-1)`, borné à
 * [maxDelayMs] (l'indice de tentative part à 1).
 *
 * Les valeurs par défaut sont des garde-fous de politesse réseau, pas des
 * constantes de protocole : les tests injectent des délais courts.
 *
 * @param maxAttempts nombre **total** de tentatives (première requête
 * incluse) ; 1 = aucune reprise.
 * @param baseDelayMs délai de base de l'exponentiel, en millisecondes.
 * @param maxDelayMs plafond du délai entre tentatives, en millisecondes
 * (s'applique aussi à un `Retry-After` exagéré).
 */
public class BoundedBackoff(
    public val maxAttempts: Int = 3,
    public val baseDelayMs: Long = 500L,
    public val maxDelayMs: Long = PLAFOND_DEFAUT_MS,
) {
    init {
        require(maxAttempts >= 1) { "au moins une tentative" }
        require(baseDelayMs > 0) { "délai de base strictement positif" }
        require(maxDelayMs >= baseDelayMs) { "plafond ≥ délai de base" }
    }

    /**
     * Indique si une reprise est autorisée pour ce statut.
     *
     * @param statusCode statut HTTP renvoyé.
     * @return `true` pour 429 et la plage 5xx (ADR 0123 point 5), `false`
     * sinon.
     */
    public fun retryable(statusCode: Int): Boolean = statusCode == HTTP_TROP_DE_REQUETES || statusCode in PLAGE_SERVEUR

    /**
     * Décision complète de reprise : statut reprise-able, tentatives non
     * épuisées et **aucun jeton encore reçu**.
     *
     * @param statusCode statut HTTP renvoyé.
     * @param attempt indice de la tentative qui vient d'échouer (à partir
     * de 1).
     * @param firstTokenReceived `true` si un jeton du flux a déjà été
     * émis au collecteur.
     * @return `true` si une nouvelle tentative doit être lancée.
     */
    public fun shouldRetry(
        statusCode: Int,
        attempt: Int,
        firstTokenReceived: Boolean,
    ): Boolean = retryable(statusCode) && !firstTokenReceived && attempt < maxAttempts

    /**
     * Calcule le délai d'attente avant la prochaine tentative.
     *
     * @param attempt indice de la tentative qui vient d'échouer (à partir
     * de 1).
     * @param retryAfterSeconds délai annoncé par `Retry-After`, si lisible.
     * @return délai d'attente en millisecondes, toujours dans
     * `[baseDelayMs, maxDelayMs]` (un `Retry-After` à 0 reste au minimum
     * [baseDelayMs] — politesse minimale).
     */
    public fun delayMs(
        attempt: Int,
        retryAfterSeconds: Long?,
    ): Long {
        if (retryAfterSeconds != null) {
            return (retryAfterSeconds * MS_PAR_SECONDE).coerceIn(baseDelayMs, maxDelayMs)
        }
        var delai = baseDelayMs
        repeat((attempt - 1).coerceAtMost(MAX_DOUBLAGES)) {
            delai = (delai * 2).coerceAtMost(maxDelayMs)
        }
        return delai.coerceIn(baseDelayMs, maxDelayMs)
    }

    private companion object {
        /** Statut « trop de requêtes ». */
        private const val HTTP_TROP_DE_REQUETES = 429

        /** Plage des erreurs de serveur. */
        private val PLAGE_SERVEUR = 500..599

        /** Plafond de délai par défaut, en millisecondes. */
        private const val PLAFOND_DEFAUT_MS = 8_000L

        /** Conversion secondes → millisecondes. */
        private const val MS_PAR_SECONDE = 1_000L

        /** Nombre maximal de doublages de l'exponentiel (garde anti-débordement). */
        private const val MAX_DOUBLAGES = 32
    }
}
