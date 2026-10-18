package jo.codeide.core.ai

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Cas d'usage « envoyer un message » (mission IA, plan IA-1) : la porte
 * d'entrée du chat (IA-3) vers le fournisseur actif. Le ViewModel ne
 * touche jamais [AiProvider] directement — règle 5 du plan (aucune
 * logique métier hors `:core:ai`).
 *
 * En IA-1, le fournisseur est injecté directement ; le choix dynamique
 * du fournisseur actif (réglages, IA-2) s'insèrera derrière la même
 * signature — [AiProvider] reste le contrat stable.
 *
 * Contexte d'exécution : l'appelant collecte le flux renvoyé ; aucune
 * requête ne part avant l'abonnement (flux froid).
 */
public class SendMessageUseCase
    @Inject
    constructor(
        private val provider: AiProvider,
    ) {
        /**
         * Envoie la requête au fournisseur et rapporte les événements
         * normalisés au fil de l'eau (transparence : le cas d'usage
         * délègue au port sans altérer les événements).
         *
         * @param request la conversation et ses paramètres.
         * @return flux froid d'événements, terminé par
         * [AiEvent.Done] ou [AiEvent.Failure].
         */
        public operator fun invoke(request: AiRequest): Flow<AiEvent> = provider.stream(request)
    }
