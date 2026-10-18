package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Rétention de l'historique des conversations IA (mission IA, lot IA-6,
 * ADR 0124 §5) : configurable (7 j / 30 j / illimitée), purge à
 * l'ouverture du tiroir IA.
 *
 * @property jours nombre de jours de conservation (`0` = illimitée).
 */
// Les valeurs SONT les constantes sémantiques de l'énumération (7 / 30 /
// illimitée) — la règle MagicNumber ne s'applique pas à un domaine fermé.
@Suppress("MagicNumber")
public enum class RetentionIa(
    public val jours: Int,
) {
    /** Sept jours de conservation. */
    JOURS_7(7),

    /** Trente jours de conservation. */
    JOURS_30(30),

    /** Conservation illimitée (aucune purge automatique). */
    ILLIMITEE(0),
}

/**
 * Port de l'historique des conversations IA (mission IA, lot IA-6,
 * ADR 0124 §5) : persisté localement (Room v2 — `ai_conversations`,
 * `ai_messages`), LOCAL uniquement, contenu JAMAIS journalisé. Les
 * textes ne transitent par aucun journal : seules les méta-données
 * (titres, comptes) sont observables hors écran.
 *
 * Suppression d'une conversation = suppression de ses messages
 * (cascade). La purge de rétention est lue À CHAQUE appel
 * ([purgerPerimees] reçoit le réglage courant).
 */
public interface ConversationStore {
    /**
     * Conversations triées du plus récent au plus ancien.
     *
     * @param recherche sous-chaîne insensible à la casse sur le titre
     * (vide ou `null` = toutes) — la recherche ne porte JAMAIS sur le
     * contenu des messages (prévisible et sobre, spec E6).
     */
    public fun conversations(recherche: String = ""): Flow<List<ConversationIa>>

    /** Conversations d'un identifiant donné (rouvrir dans le chat). */
    public suspend fun messages(conversationId: String): List<MessageConversationIa>

    /** Nombre de messages d'une conversation (badge de liste). */
    public suspend fun nombreMessages(conversationId: String): Int

    /**
     * Crée une conversation (si absente) puis ajoute le message. Le
     * titre auto (premier message utilisateur tronqué) est posé à la
     * création et reste éditable ([renommer]).
     *
     * @return l'identifiant de la conversation porteuse.
     */
    public suspend fun ajouterMessage(
        conversationId: String,
        modele: String,
        message: MessageConversationIa,
    )

    /** Renomme une conversation ; renvoie le nombre de lignes modifiées. */
    public suspend fun renommer(
        conversationId: String,
        titre: String,
    ): Int

    /**
     * Supprime une conversation ET ses messages (cascade) ; renvoie le
     * nombre de conversations supprimées (0 = inconnue).
     */
    public suspend fun supprimer(conversationId: String): Int

    /** Supprime TOUTES les conversations et leurs messages. */
    public suspend fun toutSupprimer()

    /**
     * Purge les conversations dont `modifieA` est plus vieux que la
     * rétention donnée (illimitée = aucun effet). Appelée à l'ouverture
     * du tiroir IA — le réglage est lu par l'appelant.
     */
    public suspend fun purgerPerimees(retention: RetentionIa)
}
