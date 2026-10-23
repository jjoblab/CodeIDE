package jo.codeide.core.ai

/**
 * ChangeSet de l'agent (mission IA, lot IA-7, ADR 0125) : les écritures
 * proposées s'accumulent ICI — AUCUN disque touché tant que
 * l'utilisateur n'a pas examiné puis appliqué.
 */
public data class ChangeSetIa(public val changements: List<ChangementIa> = emptyList()) {
    /** Vide = rien à examiner ni appliquer. */
    public val vide: Boolean get() = changements.isEmpty()

    /** Ajoute (ou remplace : dernier état proposé par fichier+type). */
    public operator fun plus(changement: ChangementIa): ChangeSetIa {
        val restants =
            changements.filterNot {
                it.cheminRelatif == changement.cheminRelatif && it.type == changement.type
            }
        return ChangeSetIa(restants + changement)
    }

    /** Fusionne un autre ChangeSet (ordre stable). */
    public operator fun plus(autre: ChangeSetIa): ChangeSetIa = ChangeSetIa(changements + autre.changements)
}

/** Type de changement proposé. */
public enum class TypeChangementIa {
    /** Remplace le contenu du fichier existant. */
    MODIFIER,

    /** Crée le fichier (échoue à l'application s'il existe entre-temps). */
    CREER,

    /** Supprime le fichier (confirmation renforcée à l'examen). */
    SUPPRIMER,
}

/**
 * Un changement proposé (ADR 0125 : `propose_edit`, `create_file`,
 * `delete_file`).
 *
 * @property cheminRelatif chemin résolu (ProjectPathResolver — déjà
 * validé : pas d'évasion, pas de fichier exclu).
 * @property type nature du changement.
 * @property contenu nouveau contenu (MODIFIER/CREER), ignoré pour
 * SUPPRIMER.
 * @property empreinteAttendue SHA-256 du contenu ACTUEL attendu au
 * moment de la proposition — divergence = **conflit**, jamais
 * d'écrasement silencieux (ADR 0125 « Réversibilité »).
 * @property raison courte raison donnée par l'agent (affichée).
 */
public data class ChangementIa(
    public val cheminRelatif: String,
    public val type: TypeChangementIa,
    public val contenu: String,
    public val empreinteAttendue: String?,
    public val raison: String,
)

/**
 * Porte d'approbation de l'agent (ADR 0125 « Niveaux d'approbation ») :
 * LECTURE = automatique (la porte n'est même pas sollicitée) ;
 * EXÉCUTION (run_gradle) = une décision explicite par appel, sans
 * « toujours autoriser ». L'UI fournit l'implémentation (carte
 * d'approbation) ; les tests injectent une décision directe.
 */
public fun interface PorteApprobationIa {
    /**
     * Demande une décision pour un appel d'outil EXÉCUTION.
     *
     * @return `true` = autorisé CETTE fois ; `false` = refusé (résultat
     * « refusé » renvoyé au modèle, l'exécution continue).
     */
    public suspend fun approuver(outil: String, arguments: Map<String, String>, raison: String): Boolean
}
