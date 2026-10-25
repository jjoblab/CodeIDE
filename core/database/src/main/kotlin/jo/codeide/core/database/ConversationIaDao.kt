package jo.codeide.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO de l'historique IA (mission IA, lot IA-6) : conversations triées
 * par dernier message, recherche par TITRE seulement (jamais le contenu
 * — sobre et prévisible, spec E6), insertion transactionnelle
 * (conversation absente créée à la volée), cascade pour les messages.
 */
@Suppress("TooManyFunctions") // un DAO Room est un contrat d'accès : une requête = une méthode dédiée.
@Dao
public interface ConversationIaDao {
    /** Conversations du plus récent au plus ancien. */
    @Query("SELECT * FROM ai_conversations ORDER BY modifie_a DESC")
    public fun observeToutes(): Flow<List<ConversationIaEntity>>

    /** Recherche par titre (insensible à la casse), même tri. */
    @Query(
        "SELECT * FROM ai_conversations WHERE titre LIKE '%' || :recherche || '%' " +
            "COLLATE NOCASE ORDER BY modifie_a DESC",
    )
    public fun observeRecherche(recherche: String): Flow<List<ConversationIaEntity>>

    /** Conversation par identifiant (titre auto à la création). */
    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    public suspend fun parId(id: String): ConversationIaEntity?

    /** Messages d'une conversation, dans l'ordre chronologique. */
    @Query("SELECT * FROM ai_messages WHERE conversation_id = :conversationId ORDER BY cree_a ASC, rowid ASC")
    public suspend fun messages(conversationId: String): List<MessageIaEntity>

    /** Nombre de messages d'une conversation (badge). */
    @Query("SELECT COUNT(*) FROM ai_messages WHERE conversation_id = :conversationId")
    public suspend fun nombreMessages(conversationId: String): Int

    /** Crée la conversation si absente (premier message — INSERT ABORT). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insererConversation(conversation: ConversationIaEntity)

    /** Ajoute un message (INSERT ABORT — identifiants UUID uniques). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insererMessage(message: MessageIaEntity)

    /** Renomme ; renvoie le nombre de lignes (convention du dépôt). */
    @Query("UPDATE ai_conversations SET titre = :titre WHERE id = :id")
    public suspend fun renommer(id: String, titre: String): Int

    /** Supprime une conversation — les messages partent en CASCADE. */
    @Query("DELETE FROM ai_conversations WHERE id = :id")
    public suspend fun supprimer(id: String): Int

    /** Tout effacer (conversations ET messages). */
    @Query("DELETE FROM ai_conversations")
    public suspend fun toutSupprimer()

    /** Purge de rétention : conversations dont le dernier message
     *  précède la borne (epoch ms) — CASCADE sur les messages. */
    @Query("DELETE FROM ai_conversations WHERE modifie_a < :borneEpochMs")
    public suspend fun supprimerAvant(borneEpochMs: Long): Int

    /**
     * Ajout TRANSACTIONNEL : crée la conversation si absente puis insère
     * le message et met à jour `modifie_a` — atomique, jamais de
     * message orphelin ni de conversation fantôme.
     */
    @Transaction
    public suspend fun ajouterMessage(conversation: ConversationIaEntity, message: MessageIaEntity) {
        val existante = parId(conversation.id)
        if (existante == null) {
            insererConversation(conversation)
        } else {
            // Le titre auto n'écrase jamais un titre renommé : seule la
            // date de modification avance.
            insererOuMajDate(conversation.id, conversation.modifieA)
        }
        insererMessage(message)
    }

    /** Avance `modifie_a` (SQL direct — pas de lecture-modification-écriture). */
    @Query("UPDATE ai_conversations SET modifie_a = :modifieA WHERE id = :id")
    public suspend fun insererOuMajDate(id: String, modifieA: Long)

    /**
     * Incrémente les compteurs d'usage de la conversation (rapport IA,
     * Lot 5, point 4) — cumul atomique en SQL, jamais de
     * lecture-modification-écriture ; conversation inconnue = aucun
     * effet (le chat ne compte que ce qui est persisté).
     */
    @Query(
        "UPDATE ai_conversations SET jetons_entree = jetons_entree + :jetonsEntree, " +
            "jetons_sortie = jetons_sortie + :jetonsSortie WHERE id = :id",
    )
    public suspend fun ajouterUsage(id: String, jetonsEntree: Long, jetonsSortie: Long)
}
