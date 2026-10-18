package jo.codeide.core.ai

/**
 * Source de la clé d'API d'un fournisseur (mission IA, ADR 0124) : la clé
 * vit dans le coffre (`:core:ai-storage`, lot IA-2) et ne circule **jamais**
 * dans le modèle de requête, les états d'UI, les journaux ou les tests
 * (plan §2 règle 6).
 *
 * En IA-1 (aucune UI), l'implémentation de test renvoie une valeur
 * factice évidente ; à partir d'IA-2, l'implémentation lue au coffre est
 * fournie par l'injection de dépendances.
 */
public fun interface ApiKeySource {
    /**
     * Fournit la clé d'API courante du fournisseur.
     *
     * @return la clé, ou `null` si aucune clé n'est configurée —
     * le fournisseur échoue alors avec `AppError.Ai(Authentication)`
     * **avant** toute requête réseau (ADR 0123 point 5).
     */
    public suspend fun obtain(): String?
}
