package jo.codeide.feature.ai

import androidx.annotation.StringRes
import jo.codeide.core.model.AppError

/**
 * Message localisé prêt à afficher (mission IA, lot IA-2, spec E5 :
 * « chaque erreur avec cause lisible ») — ressource + argument optionnel
 * de formatage. Aucun texte brut de fournisseur n'est affiché tel quel ;
 * les détails techniques restent pour les journaux (ADR 0124 §3).
 */
internal data class MessageIa(
    @param:StringRes public val ressource: Int,
    public val argument: String? = null,
)

/**
 * Mappage `AppError` → message. La liste des raisons est fermée : toute
 * nouvelle raison d'[AppError.AiReason] fait échouer la compilation ici —
 * traduction volontairement exhaustive.
 */
internal object PresentationErreursIa {
    /**
     * Message principal pour cette erreur.
     *
     * `@Suppress("CyclomaticComplexMethod")` justifiée : la fonction EST
     * la table de traduction — une branche par raison fermée de
     * AppError.AiReason ; la complexité vient du nombre de cas, pas
     * d'imbrication (règle 8).
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun message(erreur: AppError): MessageIa =
        when (erreur) {
            is AppError.Ai -> {
                when (erreur.reason) {
                    AppError.AiReason.Authentication -> {
                        MessageIa(R.string.ia_erreur_authentication)
                    }

                    AppError.AiReason.QuotaExceeded -> {
                        erreur.retryAfterSeconds
                            ?.takeIf { it > 0 }
                            ?.let { MessageIa(R.string.ia_erreur_quota, it.toString()) }
                            ?: MessageIa(R.string.ia_erreur_quota_simple)
                    }

                    AppError.AiReason.ServiceUnavailable -> {
                        MessageIa(R.string.ia_erreur_service)
                    }

                    AppError.AiReason.Offline -> {
                        MessageIa(R.string.ia_erreur_hors_ligne)
                    }

                    AppError.AiReason.Timeout -> {
                        MessageIa(R.string.ia_erreur_delai)
                    }

                    AppError.AiReason.MalformedResponse -> {
                        MessageIa(R.string.ia_erreur_illisible)
                    }

                    AppError.AiReason.ConsentRequired -> {
                        MessageIa(R.string.ia_erreur_consentement)
                    }

                    AppError.AiReason.Configuration -> {
                        MessageIa(R.string.ia_erreur_configuration)
                    }
                }
            }

            is AppError.Storage -> {
                when (erreur.reason) {
                    AppError.StorageReason.KeyUnavailable -> MessageIa(R.string.ia_erreur_cle_perdue)
                    else -> MessageIa(R.string.ia_erreur_stockage)
                }
            }

            else -> {
                MessageIa(R.string.ia_erreur_inconnue)
            }
        }
}
