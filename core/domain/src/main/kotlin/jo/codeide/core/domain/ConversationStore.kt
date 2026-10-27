package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Rétention de l'historique des conversations IA (mission IA, lot IA-6,
 * ADR 0124 §5) : configurable (7 j / 30 j / illimitée), purge à
 * l'ouverture du tiroir IA.
 *
 * @property jours nombre de jours de conservation (`0` = illimitée).
 *
 * Les valeurs SONT les constantes sémantiques de l'énumération (7 / 30 /
 * illimitée) — la règle MagicNumber ne s'applique pas à un domaine fermé.
 */
@Suppress("MagicNumber")
public enum class RetentionIa(public val jours: Int) {
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
// Exemption detekt ciblée (règle 16) : TooManyFunctions — un PORT
// d'accès a une méthode par opération du contrat (liste, messages,
// ajout, usage, lecture, renommage, suppression, épinglage,
// restauration, purge) ; le seuil franchit d'UNE unité avec
// l'épinglage et la restauration (rapport IA, lot 3), aucun
// regroupement honnête ne réduirait la surface sans casser les
// implémentations.
@Suppress("TooManyFunctions")
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
    public suspend fun ajouterMessage(conversationId: String, modele: String, message: MessageConversationIa)

    /**
     * Incrémente les compteurs d'usage d'une conversation (rapport IA,
     * Lot 5, point 4) : cumul atomique côté stockage, AUCUN effet sur
     * une conversation inconnue (le chat ne compte que ce qui est
     * persisté — un flux interrompu avant le premier message persisté
     * ne s'accumule nulle part).
     *
     * @param jetonsEntree jetons d'entrée rapportés par le flux terminé.
     * @param jetonsSortie jetons de sortie rapportés par le flux terminé.
     */
    public suspend fun ajouterUsage(conversationId: String, jetonsEntree: Long, jetonsSortie: Long)

    /**
     * Conversation par identifiant (compteurs d'usage à la réouverture
     * du chat — Lot 5, point 4) ; `null` si inconnue.
     */
    public suspend fun conversationParId(conversationId: String): ConversationIa?

    /** Renomme une conversation ; renvoie le nombre de lignes modifiées. */
    public suspend fun renommer(conversationId: String, titre: String): Int

    /**
     * Supprime une conversation ET ses messages (cascade) ; renvoie le
     * nombre de conversations supprimées (0 = inconnue).
     */
    public suspend fun supprimer(conversationId: String): Int

    /** Supprime TOUTES les conversations et leurs messages. */
    public suspend fun toutSupprimer()

    /**
     * Épingle ou détache une discussion (rapport IA, lot 3 — d'après
     * la maquette) : une discussion épinglée reste en TÊTE de la
     * liste quel que soit l'âge de son dernier message ; choix
     * réversible d'un appui, aucun effet sur une conversation
     * inconnue.
     */
    public suspend fun epingler(conversationId: String, epingle: Boolean)

    /**
     * RESTAURE une conversation supprimée et ses messages (rapport
     * IA, lot 3 — « Annuler » de la suppression) : réinsertion
     * complète (méta-données, épinglage et messages), appelée depuis
     * l'instantané capturé AVANT la suppression — la discussion
     * revient à l'identique, au-dessus des listes filtrées.
     */
    public suspend fun restaurer(conversation: ConversationIa, messages: List<MessageConversationIa>)

    /**
     * Purge les conversations dont `modifieA` est plus vieux que la
     * rétention donnée (illimitée = aucun effet). Appelée à l'ouverture
     * du tiroir IA — le réglage est lu par l'appelant.
     */
    public suspend fun purgerPerimees(retention: RetentionIa)
}
