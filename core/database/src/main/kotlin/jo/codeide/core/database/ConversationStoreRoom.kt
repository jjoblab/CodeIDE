package jo.codeide.core.database

import jo.codeide.core.domain.ConversationIa
import jo.codeide.core.domain.ConversationStore
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.RetentionIa
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation Room du port [ConversationStore] (mission IA, lot IA-6,
 * ADR 0124 §5) : LOCAL uniquement, contenu JAMAIS journalisé (aucun
 * appel d'[AppLogger] ici — les textes ne sortent jamais du stockage) ;
 * suppression en CASCADE ; purge de rétention lue à chaque appel.
 */
@Singleton
public class ConversationStoreRoom
@Inject
constructor(private val base: CodeIdeDatabase) : ConversationStore {
    private val dao: ConversationIaDao get() = base.conversationIaDao()

    override fun conversations(recherche: String): Flow<List<ConversationIa>> =
        (if (recherche.isBlank()) dao.observeToutes() else dao.observeRecherche(recherche.trim()))
            .map { conversations -> conversations.map { conversation -> conversation.toModel() } }

    override suspend fun messages(conversationId: String): List<MessageConversationIa> =
        dao.messages(conversationId).map { message -> message.toModel() }

    override suspend fun nombreMessages(conversationId: String): Int = dao.nombreMessages(conversationId)

    override suspend fun ajouterMessage(conversationId: String, modele: String, message: MessageConversationIa) {
        val conversation =
            ConversationIaEntity(
                id = conversationId,
                // Titre auto : premier message tronqué (spec E6) —
                // remplacé par un renommage utilisateur s'il existe.
                titre = titreAuto(message.texte),
                modele = modele,
                creeA = message.creeA,
                modifieA = message.creeA,
            )
        dao.ajouterMessage(conversation, message.toEntity())
    }

    override suspend fun renommer(conversationId: String, titre: String): Int =
        dao.renommer(conversationId, titre.trim())

    override suspend fun supprimer(conversationId: String): Int = dao.supprimer(conversationId)

    override suspend fun toutSupprimer() {
        dao.toutSupprimer()
    }

    override suspend fun purgerPerimees(retention: RetentionIa) {
        if (retention.jours <= 0) {
            return
        }
        val borne = System.currentTimeMillis() - retention.jours * JOUR_MS
        dao.supprimerAvant(borne)
    }

    /** Titre auto (spec E6) : première ligne, tronquée proprement. */
    private fun titreAuto(texte: String): String {
        val premiere = texte.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return if (premiere.length <= TITRE_MAX) premiere else premiere.take(TITRE_MAX - 1) + "…"
    }

    private companion object {
        /** Longueur maximale du titre auto (spec E6). */
        private const val TITRE_MAX = 60

        /** Millisecondes par jour (purge de rétention). */
        private const val JOUR_MS = 24L * 60 * 60 * 1000
    }
}
