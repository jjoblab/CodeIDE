package jo.codeide.core.ai

import jo.codeide.core.domain.AiSettings

/**
 * Fabrique du fournisseur concret **pour les réglages courants** (mission
 * IA, lot IA-2) : le domaine ne connaît ni HTTP ni les adaptateurs —
 * l'implémentation réelle (adaptateurs Anthropic / compatible OpenAI,
 * source de clé vers le coffre) est liée côté application (`:app`, seul
 * point de composition).
 *
 * @return le fournisseur prêt à l'emploi, ou `null` si l'identifiant de
 * fournisseur est inconnu ou son URL de base invalide — le routeur
 * [FournisseurIaActif] traduit ce cas en `AppError.Ai(Configuration)`.
 */
public fun interface FabriqueFournisseurIa {
    /** Crée le fournisseur correspondant aux réglages, ou `null`. */
    public suspend fun creer(reglages: AiSettings): AiProvider?
}
