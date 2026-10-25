package jo.codeide.core.database

import jo.codeide.core.domain.ConversationIa
import jo.codeide.core.domain.MessageConversationIa

/** Entité ↔ domaine — conversations IA (mission IA, lot IA-6 ;
 *  compteurs d'usage — rapport IA, Lot 5, point 4). */

public fun ConversationIaEntity.toModel(): ConversationIa = ConversationIa(
    identifiant = id,
    titre = titre,
    modele = modele,
    creeA = creeA,
    modifieA = modifieA,
    jetonsEntree = jetonsEntree,
    jetonsSortie = jetonsSortie,
)

public fun ConversationIa.toEntity(): ConversationIaEntity = ConversationIaEntity(
    id = identifiant,
    titre = titre,
    modele = modele,
    creeA = creeA,
    modifieA = modifieA,
    jetonsEntree = jetonsEntree,
    jetonsSortie = jetonsSortie,
)

public fun MessageIaEntity.toModel(): MessageConversationIa = MessageConversationIa(
    identifiant = id,
    conversationId = conversationId,
    utilisateur = utilisateur,
    texte = texte,
    creeA = creeA,
    raisonnement = raisonnement,
)

public fun MessageConversationIa.toEntity(): MessageIaEntity = MessageIaEntity(
    id = identifiant,
    conversationId = conversationId,
    utilisateur = utilisateur,
    texte = texte,
    creeA = creeA,
    raisonnement = raisonnement,
)
