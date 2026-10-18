package jo.codeide.ai.providers

import jo.codeide.core.model.AppError
import java.io.IOException

/**
 * Mappage des réponses et incidents HTTP vers les erreurs modélisées
 * (ADR 0123 point 5) : la liste des raisons est **fermée** — tout code
 * non prévu reste un `AppError.Unknown` honnête, jamais une erreur
 * déformée.
 *
 * Aucun détail sensible ne circule : les [AppError.Ai.details] portent le
 * code HTTP ou la classe d'incident, jamais un en-tête d'authentification
 * ni un corps (ADR 0124).
 */
public object HttpErrorMapper {
    /**
     * Traduit un statut HTTP de réponse en erreur attendue.
     *
     * @param statusCode statut renvoyé par le fournisseur.
     * @param retryAfterSeconds valeur de l'en-tête `Retry-After` en
     * secondes si elle a pu être lue, sinon `null`.
     * @return erreur modélisée : 401/403 → authentification, 429 → quota
     * (avec `Retry-After`), 5xx → indisponibilité, reste → inconnu.
     */
    public fun fromStatus(
        statusCode: Int,
        retryAfterSeconds: Long? = null,
    ): AppError =
        when (statusCode) {
            HTTP_NON_AUTORISE, HTTP_INTERDIT -> {
                AppError.Ai(AppError.AiReason.Authentication, "HTTP $statusCode")
            }

            HTTP_TROP_DE_REQUETES -> {
                AppError.Ai(AppError.AiReason.QuotaExceeded, "HTTP $statusCode", retryAfterSeconds)
            }

            in PREMIER_SERVEUR..DERNIER_SERVEUR -> {
                AppError.Ai(AppError.AiReason.ServiceUnavailable, "HTTP $statusCode")
            }

            else -> {
                AppError.Unknown("HTTP $statusCode")
            }
        }

    /**
     * Traduit un incident réseau d'ouverture en erreur attendue (mêmes
     * règles que [jo.codeide.ai.providers.AiHttpTransport.open]).
     *
     * @param exception incident capturé.
     * @return erreur modélisée.
     */
    public fun fromIo(exception: IOException): AppError =
        when (exception) {
            is java.net.UnknownHostException -> {
                AppError.Ai(AppError.AiReason.Offline, exception.message ?: "DNS")
            }

            is java.net.ConnectException -> {
                AppError.Ai(AppError.AiReason.Offline, exception.message ?: "connexion refusée")
            }

            is java.net.SocketTimeoutException -> {
                AppError.Ai(AppError.AiReason.Timeout, exception.message ?: "délai dépassé")
            }

            else -> {
                AppError.Unknown(exception.javaClass.simpleName)
            }
        }

    /**
     * Erreur « réponse mal formée » : SSE ou JSON inattendu, flux tronqué,
     * caractère UTF-8 coupé non reconstituable.
     *
     * @param detail description technique (pour le journal uniquement).
     * @return erreur modélisée `MalformedResponse`.
     */
    public fun malformed(detail: String): AppError = AppError.Ai(AppError.AiReason.MalformedResponse, detail)

    /**
     * Lit l'en-tête `Retry-After` en secondes (forme entière uniquement).
     *
     * La forme « date HTTP » n'est pas interprétée (horloge locale non
     * fiable) — elle renvoie `null` et le backoff exponentiel prend le
     * relais ([BoundedBackoff]).
     *
     * @param retryAfter valeur brute de l'en-tête, `null` si absent.
     * @return délai en secondes, ou `null` si illisible.
     */
    public fun lireRetryAfter(retryAfter: String?): Long? = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    private const val HTTP_NON_AUTORISE = 401
    private const val HTTP_INTERDIT = 403
    private const val HTTP_TROP_DE_REQUETES = 429
    private const val PREMIER_SERVEUR = 500
    private const val DERNIER_SERVEUR = 599
}
