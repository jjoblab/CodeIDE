package jo.codeide.core.domain

/**
 * Conversation IA persistée localement (mission IA, lot IA-6, ADR 0124
 * §5) : l'historique est LOCAL uniquement (Room), jamais journalisé, et
 * sert à rouvrir un échange dans le chat.
 *
 * @property identifiant identifiant stable (UUID) de la conversation.
 * @property titre titre auto (premier message tronqué) ou renommé.
 * @property modele modèle du fournisseur actif à la création.
 * @property creeA epoch ms de création.
 * @property modifieA epoch ms du dernier message.
 * @property jetonsEntree jetons d'entrée CUMULÉS rapportés par le
 *        fournisseur (rapport IA, Lot 5, point 4 — jamais estimés
 *        localement ; 0 tant qu'aucun flux n'a rapporté d'usage).
 * @property jetonsSortie jetons de sortie cumulés (même contrat).
 */
public data class ConversationIa(
    public val identifiant: String,
    public val titre: String,
    public val modele: String,
    public val creeA: Long,
    public val modifieA: Long,
    public val jetonsEntree: Long = 0,
    public val jetonsSortie: Long = 0,
)

/**
 * Message d'une conversation IA (lot IA-6) : texte finalisé seulement —
 * ni le streaming en cours, ni les réponses échouées ne sont persistés.
 *
 * @property identifiant identifiant stable (UUID) du message.
 * @property conversationId conversation porteuse.
 * @property utilisateur `true` = message de l'utilisateur, `false` =
 * réponse de l'assistant.
 * @property texte contenu complet (local uniquement — ADR 0124 §3).
 * @property creeA epoch ms.
 */
public data class MessageConversationIa(
    public val identifiant: String,
    public val conversationId: String,
    public val utilisateur: Boolean,
    public val texte: String,
    public val creeA: Long,
)
