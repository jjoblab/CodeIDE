package jo.codeide.core.ai

/**
 * Rôle d'un message dans une conversation IA (mission IA, ADR 0123) :
 * vocabulaire commun aux fournisseurs Anthropic (Messages) et « compatible
 * OpenAI » (Chat Completions) — l'UI et l'agent ne voient jamais le format
 * natif du fournisseur (ADR 0123 point 3).
 */
public enum class AiRole {
    /** Consigne système (comportement de l'assistant), hors fil de conversation. */
    System,

    /** Message de l'utilisateur. */
    User,

    /** Message de l'assistant, éventuellement porteur d'appels d'outils. */
    Assistant,

    /** Résultat d'un outil, renvoyé au modèle après exécution (IA-7). */
    Tool,
}
