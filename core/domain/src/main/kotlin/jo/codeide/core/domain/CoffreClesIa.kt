package jo.codeide.core.domain

import jo.codeide.core.model.AppResult

/**
 * Port du coffre de clés IA (mission IA, lot IA-2, ADR 0124 §1) : chiffre
 * et relit les clés d'API de l'utilisateur **sans jamais les exposer hors
 * de la mémoire du processus**.
 *
 * Contrat de sécurité (ADR 0124 §1) :
 * - la clé maître est **AES-256-GCM générée dans l'Android Keystore**
 *   (alias versionné `codeide.ai.vault.v1`, non exportable) — côté implémentation ;
 * - les blobs vivent dans `Context.noBackupFilesDir` (**exclu de la
 *   sauvegarde** par construction), jamais dans DataStore ni `SharedPreferences` ;
 * - le texte en clair n'existe qu'en mémoire le temps d'une requête et
 *   n'apparaît **jamais** dans un état d'UI, un `Bundle` ou un journal ;
 * - perte de la clé maître (réinitialisation de l'appareil…) : le blob
 *   devient illisible → `AppResult.Failure(AppError.Storage(KeyUnavailable))`,
 *   état « Clé à ressaisir », **aucun plantage**.
 *
 * Implémentation : `CoffreClesKeystore` (`:core:ai-storage`). Les tests
 * JVM couvrent la logique via une clé maître logicielle de remplacement.
 */
public interface CoffreClesIa {
    /**
     * Chiffre et enregistre la clé du fournisseur [identifiant] (remplace
     * toute clé existante). Le tableau est **effacé** après usage quand
     * c'est possible ; l'appelant ne le réutilise pas.
     */
    public suspend fun enregistrer(identifiant: String, cle: CharArray): AppResult<Unit>

    /**
     * Déchiffre et renvoie la clé du fournisseur [identifiant] — l'appelant
     * efface le tableau après usage (`fill('\u0000')`).
     *
     * Échecs attendus : `Storage(NotFound)` (aucune clé), `Storage(KeyUnavailable)`
     * (clé maître perdue ou blob corrompu — « Clé à ressaisir »).
     */
    public suspend fun lire(identifiant: String): AppResult<CharArray>

    /**
     * Supprime la clé (et son aperçu) du fournisseur [identifiant] —
     * idempotent : supprimer une clé absente est un succès.
     */
    public suspend fun supprimer(identifiant: String): AppResult<Unit>

    /** `true` si une clé (même illisible) est enregistrée pour [identifiant]. */
    public suspend fun contient(identifiant: String): Boolean

    /**
     * Aperçu public `••••1234` (4 derniers caractères) ou `null` — cet
     * aperçu est la SEULE trace de la clé autorisée hors du coffre
     * (écran de réglages, jamais un journal).
     */
    public suspend fun apercu(identifiant: String): String?
}
