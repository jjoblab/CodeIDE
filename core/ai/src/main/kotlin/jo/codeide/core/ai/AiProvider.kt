package jo.codeide.core.ai

import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Port d'accès à un fournisseur IA (ADR 0123 point 1) — la seule
 * abstraction que le chat (IA-3) et l'agent (IA-7) connaissent ; les
 * implémentations vivent dans `:ai:providers`, l'UNIQUE point de sortie
 * réseau IA de l'application (ADR 0121 point 2).
 *
 * Contexte d'exécution : [stream] est un flux froid — aucune requête ne
 * part tant qu'aucun collecteur ne s'y abonne ; l'annulation du
 * collecteur ferme la connexion (annulation coopérative, ADR 0123
 * point 4) ; [listModels] et [ping] sont suspendus et hors thread
 * principal.
 */
public interface AiProvider {
    /**
     * Envoie la requête en streaming et rapporte les événements
     * normalisés ([AiEvent]) au fil de l'eau.
     *
     * Les erreurs attendues arrivent comme événement terminal
     * [AiEvent.Failure] (jamais comme exception) ; les reprises
     * backoff sont bornées aux 429/5xx **avant** le premier jeton
     * (ADR 0123 point 5).
     *
     * @param requête la conversation et ses paramètres.
     * @return flux froid d'événements, terminé par [AiEvent.Done] ou
     * [AiEvent.Failure].
     */
    public fun stream(request: AiRequest): Flow<AiEvent>

    /**
     * Liste les modèles disponibles chez le fournisseur (bouton
     * « Actualiser les modèles », IA-2) — aucune liste figée dans le
     * code (ADR 0123 point 6).
     *
     * @return liste des modèles, ou erreur attendue typée.
     */
    public suspend fun listModels(): AppResult<List<AiModelInfo>>

    /**
     * Test de connexion minimal (écran réglages IA, IA-2) : une requête
     * légère, sans coût de génération.
     *
     * @return succès si le fournisseur répond et authentifie la clé.
     */
    public suspend fun ping(): AppResult<Unit>
}
