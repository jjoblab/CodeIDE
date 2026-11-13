package jo.codeide.core.ai

/**
 * Message d'une conversation IA : un [role] et une liste de [blocks]
 * (mission IA, plan IA-1). Structure indépendante des fournisseurs —
 * les adaptateurs de `:ai:providers` traduisent vers le format natif
 * (Anthropic Messages, Chat Completions) sans que l'UI ni l'agent
 * n'aient à le connaître (ADR 0123 point 3).
 *
 * @property role rôle du message dans le fil.
 * @property blocks contenu, dans l'ordre d'émission.
 */
public data class AiMessage(public val role: AiRole, public val blocks: List<AiContentBlock>) {
    /**
     * Construit un message composé d'un unique bloc texte — le cas
     * dominant du chat (IA-3) et des actions d'éditeur (IA-5).
     *
     * @param role rôle du message.
     * @param text texte du bloc unique.
     */
    public constructor(role: AiRole, text: String) : this(role, listOf(AiContentBlock.Text(text)))

    public companion object {
        /** Consigne système en un bloc texte. */
        public fun system(text: String): AiMessage = AiMessage(AiRole.System, text)

        /** Message utilisateur en un bloc texte. */
        public fun user(text: String): AiMessage = AiMessage(AiRole.User, text)

        /** Réponse d'assistant en un bloc texte. */
        public fun assistant(text: String): AiMessage = AiMessage(AiRole.Assistant, text)

        /**
         * Résultat d'outil (rôle [AiRole.Tool]) en un bloc
         * [AiContentBlock.ToolResult].
         *
         * @param toolCallId identifiant d'appel auquel ce résultat répond.
         * @param content contenu textuel du résultat.
         */
        public fun toolResult(toolCallId: String, content: String): AiMessage = AiMessage(
            AiRole.Tool,
            listOf(AiContentBlock.ToolResult(toolCallId, content)),
        )
    }
}
