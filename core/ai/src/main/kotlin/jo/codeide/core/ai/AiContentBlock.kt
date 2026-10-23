package jo.codeide.core.ai

/**
 * Contenu d'un message IA : un message est une liste de blocs (mission IA,
 * plan IA-1 — « `AiMessage` (rôles, blocs texte/outil) »).
 *
 * Les trois blocs couvrent strictement le besoin de la mission : texte,
 * appel d'outil émis par le modèle, résultat d'outil renvoyé au modèle.
 * Toute extension (images, documents) passera par un amendement d'ADR.
 */
public sealed interface AiContentBlock {
    /** Bloc de texte brut (question, réponse, extrait de contexte). */
    public data class Text(public val text: String) : AiContentBlock

    /**
     * Appel d'outil demandé par le modèle (ADR 0125) : identifiant d'appel,
     * nom de l'outil et arguments sérialisés en JSON.
     *
     * @property toolCallId identifiant d'appel attribué par le modèle.
     * @property name nom de l'outil (correspond à [ToolSpec.name]).
     * @property argumentsJson arguments de l'appel, objet JSON sérialisé.
     */
    public data class ToolUse(
        public val toolCallId: String,
        public val name: String,
        public val argumentsJson: String,
    ) : AiContentBlock

    /**
     * Résultat d'outil renvoyé au modèle (rôle [AiRole.Tool]).
     *
     * @property toolCallId identifiant d'appel auquel ce résultat répond.
     * @property content contenu du résultat (texte ; les sorties binaires
     * sont hors périmètre — ADR 0125).
     */
    public data class ToolResult(public val toolCallId: String, public val content: String) : AiContentBlock
}
