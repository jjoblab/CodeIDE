package jo.codeide.core.ai

/**
 * Requête adressée à un fournisseur IA (ADR 0123 point 1) — indépendante
 * du format natif : les adaptateurs de `:ai:providers` la traduisent.
 *
 * Aucune donnée d'authentification ici : la clé transite par la source
 * injectée chez le fournisseur ([ApiKeySource]), jamais dans le modèle de
 * requête ni dans un état d'UI (ADR 0124).
 *
 * @property messages fil de conversation, dans l'ordre ; les messages de
 * rôle [AiRole.System] sont regroupés par les adaptateurs dans le champ
 * système du fournisseur quand il en exige un (Anthropic).
 * @property model identifiant du modèle demandé — choisi par
 * l'utilisateur ou issu de [AiProvider.listModels] ; le code ne contient
 * **aucun** identifiant par défaut (ADR 0123 point 6).
 * @property maxOutputTokens plafond de jetons de sortie, quand l'appelant
 * en fixe un (`null` → plafond par défaut du fournisseur, documenté chez
 * chaque adaptateur).
 * @property temperature température d'échantillonnage, si l'appelant la
 * fixe (`null` → valeur par défaut du fournisseur).
 * @property tools outils mis à disposition du modèle (ADR 0125) ;
 * vide = pas d'appel d'outils.
 *
 * @throws IllegalArgumentException si [model] est vide ou blanc — erreur
 * de programmation, pas une erreur attendue du domaine (`AppError`).
 */
public data class AiRequest(
    public val messages: List<AiMessage>,
    public val model: String,
    public val maxOutputTokens: Int? = null,
    public val temperature: Double? = null,
    public val tools: List<ToolSpec> = emptyList(),
) {
    init {
        require(model.isNotBlank()) { "l'identifiant de modèle est obligatoire (ADR 0123 point 6)" }
    }
}
