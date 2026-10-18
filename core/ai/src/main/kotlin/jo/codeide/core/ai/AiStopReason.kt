package jo.codeide.core.ai

/**
 * Raison de fin d'un flux IA, **normalisée** entre fournisseurs (ADR 0123
 * point 3 : « l'UI et l'agent ne voient jamais le format du fournisseur ») :
 * `end_turn`/`stop` → [EndTurn], `max_tokens`/`length` → [MaxTokens],
 * `tool_use`/`tool_calls` → [ToolUse], tout le reste → [Other].
 *
 * [Other] est une [data class] (et non une entrée d'énumération) car elle
 * porte la valeur brute du fournisseur — utile au diagnostic sans
 * réinventer une énumération ouverte.
 *
 * @property raw valeur brute du fournisseur lorsqu'elle n'a pas de
 * traduction normalisée, sinon `null`.
 */
public sealed class AiStopReason(
    public val raw: String?,
) {
    /** Fin naturelle de la réponse. */
    public data object EndTurn : AiStopReason(null)

    /** Arrêt sur plafond de jetons de sortie. */
    public data object MaxTokens : AiStopReason(null)

    /** Arrêt pour demander un appel d'outil (l'agent reprend, ADR 0125). */
    public data object ToolUse : AiStopReason(null)

    /**
     * Autre raison (pause de séquence, filtre, valeur inconnue…).
     *
     * @property valeur valeur brute rapportée par le fournisseur.
     */
    public data class Other(
        public val valeur: String,
    ) : AiStopReason(valeur)

    public companion object {
        /**
         * Normalise la valeur brute d'un fournisseur.
         *
         * @param raw valeur rapportée par le fournisseur (`stop_reason`
         * Anthropic, `finish_reason` compatible OpenAI), éventuellement
         * `null` si absente.
         * @return raison normalisée ; [Other] porte la valeur brute quand
         * elle n'est pas reconnue.
         */
        public fun fromRaw(raw: String?): AiStopReason =
            when (raw) {
                null -> EndTurn
                "end_turn", "stop" -> EndTurn
                "max_tokens", "length" -> MaxTokens
                "tool_use", "tool_calls" -> ToolUse
                else -> Other(raw)
            }
    }
}
