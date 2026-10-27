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
    /**
     * Jetons d'entrée cumulés de la conversation (rapport IA, Lot 5,
     * point 4 — rapportés par les deux fournisseurs, jamais estimés
     * localement). 0 tant qu'aucun flux n'a rapporté d'usage.
     */
    @ColumnInfo(name = "jetons_entree", defaultValue = "0") public val jetonsEntree: Long = 0,
    /** Jetons de sortie cumulés de la conversation (même contrat). */
    @ColumnInfo(name = "jetons_sortie", defaultValue = "0") public val jetonsSortie: Long = 0,
    /**
     * Discussion ÉPINGLÉE (rapport IA, lot 3 — d'après la maquette) :
     * `true` = maintenue en HAUT de l'historique (tri `epingle DESC,
     * modifie_a DESC`) — l'épinglage est un choix utilisateur,
     * réversible d'un appui.
     */
    @ColumnInfo(name = "epingle", defaultValue = "0") public val epingle: Boolean = false,
)
