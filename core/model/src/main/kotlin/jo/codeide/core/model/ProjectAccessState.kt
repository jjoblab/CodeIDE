package jo.codeide.core.model

/**
 * État d'accès à un projet, **calculé à la demande** (section 5.6 du
 * prompt maître : « vérifier régulièrement que la permission est toujours
 * valide et que le dossier existe : état Introuvable / Permission perdue,
 * jamais un crash »).
 *
 * Cet état n'est **jamais persisté** : il reflète la situation du stockage
 * au moment du calcul et peut changer à tout instant (le système peut
 * révoquer une permission persistante, l'utilisateur peut supprimer le
 * dossier depuis un autre écran). L'accueil le recalcule à chaque affichage
 * via `VerifyProjectAccessUseCase` (étape 7).
 */
public sealed interface ProjectAccessState {
    /**
     * Le dossier est joignable et l'écriture y est autorisée : le projet
     * est utilisable normalement.
     */
    public data object Available : ProjectAccessState

    /**
     * La permission persistante sur l'arborescence a été révoquée ou
     * expirée (redémarrage du téléphone, nettoyage système, restore d'une
     * sauvegarde sans les permissions). L'action attendue est de
     * re-sélectionner le dossier pour re-prendre la permission.
     */
    public data object PermissionLost : ProjectAccessState

    /**
     * La permission est valide mais le dossier lui-même n'existe plus
     * (supprimé ou déplacé depuis l'extérieur). L'action attendue est
     * d'expliquer la situation et de proposer de retirer le projet du
     * registre.
     */
    public data object Missing : ProjectAccessState
}
