package jo.codeide.core.ai

/**
 * Modèle disponible chez un fournisseur, tel que rapporté par
 * [AiProvider.listModels] (ADR 0123 point 6 : « les identifiants de
 * modèles ne sont pas figés » — la liste vient du fournisseur, l'utilisateur
 * peut aussi saisir un identifiant libre ; le code ne contient **aucun**
 * nom de modèle par défaut).
 *
 * @property id identifiant à passer à [AiRequest.model] (ex. identifiant
 * public du fournisseur — jamais codé en dur dans le dépôt).
 * @property displayName libellé affichable si le fournisseur en fournit un.
 * @property contextWindowTokens taille de fenêtre de contexte annoncée,
 * si connue (borne d'entrée de [TokenBudget]).
 */
public data class AiModelInfo(
    public val id: String,
    public val displayName: String? = null,
    public val contextWindowTokens: Long? = null,
)
