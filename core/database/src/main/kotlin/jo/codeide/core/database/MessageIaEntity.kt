package jo.codeide.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Message d'une conversation IA (mission IA, lot IA-6) : texte FINALISÉ
 * seulement (ni streaming, ni échec). La clé étrangère en CASCADE
 * réalise le critère « suppression d'une conversation = suppression de
 * ses messages ».
 */
@Entity(
    tableName = "ai_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationIaEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id"])],
)
public data class MessageIaEntity(
    /** Identifiant stable (UUID) — généré côté chat. */
    @PrimaryKey public val id: String,
    /** Conversation porteuse (cascade à la suppression). */
    @ColumnInfo(name = "conversation_id") public val conversationId: String,
    /** `true` = message utilisateur, `false` = réponse assistant. */
    public val utilisateur: Boolean,
    /** Contenu complet (LOCAL uniquement — ADR 0124 §3). */
    public val texte: String,
    /** Création (epoch ms). */
    @ColumnInfo(name = "cree_a") public val creeA: Long,
)
