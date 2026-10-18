package jo.codeide.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Conversation IA de l'historique (mission IA, lot IA-6, ADR 0124 §5) :
 * méta-données seules — le CONTENU vit dans [MessageIaEntity] et ne
 * quitte JAMAIS l'appareil (aucun journal, sauvegardes exclues).
 */
@Entity(
    tableName = "ai_conversations",
    indices = [Index(value = ["modifie_a"])],
)
public data class ConversationIaEntity(
    /** Identifiant stable (UUID) — généré côté chat. */
    @PrimaryKey public val id: String,
    /** Titre auto (premier message tronqué) ou renommé. */
    public val titre: String,
    /** Modèle du fournisseur actif à la création. */
    public val modele: String,
    /** Création (epoch ms). */
    @ColumnInfo(name = "cree_a") public val creeA: Long,
    /** Dernier message (epoch ms) — tri de la liste + purge rétention. */
    @ColumnInfo(name = "modifie_a") public val modifieA: Long,
)
