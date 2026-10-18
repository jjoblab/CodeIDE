package jo.codeide.core.ai

import jo.codeide.core.model.AppError

/**
 * Événement normalisé d'un flux IA (ADR 0123 points 1 et 3) : émis par
 * [AiProvider.stream], consommé par le chat (IA-3) et l'agent (IA-7).
 * L'UI et l'agent ne voient **jamais** le format natif du fournisseur.
 *
 * Ordre attendu d'un flux nominal : zéro ou plusieurs [TextDelta] /
 * [ToolCallStarted] / [ToolCallArguments] / [ToolCallCompleted] /
 * [Usage], puis exactement un [Done] ; en cas d'échec, un unique
 * [Failure] termine le flux — jamais de reprise silencieuse d'un flux
 * commencé (ADR 0123 point 5).
 */
public sealed interface AiEvent {
    /** Fragment de texte à afficher au fil de l'eau (concaténation = réponse). */
    public data class TextDelta(
        public val text: String,
    ) : AiEvent

    /**
     * Le modèle commence un appel d'outil.
     *
     * @property toolCallId identifiant d'appel attribué par le modèle.
     * @property name nom de l'outil appelé (correspond à [ToolSpec.name]).
     */
    public data class ToolCallStarted(
        public val toolCallId: String,
        public val name: String,
    ) : AiEvent

    /**
     * Fragment d'arguments JSON d'un appel d'outil en cours — les
     * fragments doivent être **concaténés** dans l'ordre d'arrivée pour
     * reconstituer l'objet JSON (les fournisseurs fragmentent librement,
     * y compris en plein milieu d'un échappement).
     *
     * @property toolCallId identifiant d'appel concerné.
     * @property argumentsJsonFragment fragment de texte JSON à concaténer.
     */
    public data class ToolCallArguments(
        public val toolCallId: String,
        public val argumentsJsonFragment: String,
    ) : AiEvent

    /**
     * Appel d'outil terminé : la concaténation des fragments est complète.
     *
     * @property toolCallId identifiant d'appel concerné.
     * @property argumentsJson arguments complets, objet JSON sérialisé.
     */
    public data class ToolCallCompleted(
        public val toolCallId: String,
        public val argumentsJson: String,
    ) : AiEvent

    /**
     * Compteurs de jetons rapportés par le fournisseur, quand il les
     * fournit (`null` quand le champ est absent — les serveurs locaux
     * compatibles OpenAI ne le rapportent pas toujours).
     *
     * @property inputTokens jetons de la requête.
     * @property outputTokens jetons générés à l'instant du rapport.
     */
    public data class Usage(
        public val inputTokens: Long?,
        public val outputTokens: Long?,
    ) : AiEvent

    /** Le flux s'est terminé normalement. */
    public data class Done(
        public val stopReason: AiStopReason,
    ) : AiEvent

    /**
     * Le flux a échoué de manière modélisée — événement terminal, aucune
     * reprise silencieuse après un jeton reçu (ADR 0123 point 5).
     */
    public data class Failure(
        public val error: AppError,
    ) : AiEvent
}
