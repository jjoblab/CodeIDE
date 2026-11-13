package jo.codeide.core.model

/**
 * Parcours de création **effectif** (ADR 0155 §2/§3) : liste ordonnée des
 * étapes réellement parcourues pour le formulaire courant — socle imposé
 * par l'application (`modele` → étapes de modèle dont `when` est vrai →
 * `identite` → `fichiers` → `recapitulatif`) — recalculée à **chaque**
 * changement du formulaire.
 *
 * Une étape dont la condition n'existe plus **disparaît** du parcours
 * (jamais d'écran vide ni de « Suivant » inutile) ; les valeurs saisies
 * restent dans l'état du wizard et retombent sur leur défaut à la
 * génération tant que l'étape est absente.
 *
 * @property etapes étapes effectives, dans l'ordre de parcours.
 */
public data class ParcoursCreation(public val etapes: List<EtapeParcours>)

/**
 * Une étape effective du parcours (ADR 0155 §3) : socle ou étape de modèle.
 *
 * Le titre socle et le titre de l'étape par défaut « Configuration » sont
 * des **libellés applicatifs** (ressources de la fonctionnalité wizard) :
 * [titre] est vide pour eux et l'interface substitue sa ressource. Les
 * étapes de modèle portent leur titre résolu du dictionnaire.
 *
 * @property id identifiant de l'étape — socle : `modele`, `identite`,
 * `fichiers`, `recapitulatif` ; modèle : identifiant déclaré ou
 * `configuration` (étape par défaut).
 * @property socle `true` pour les quatre étapes imposées par l'application
 * (ADR 0155 §2 : non déclarables par un manifeste).
 * @property titre titre résolu pour une étape de modèle, vide pour le socle
 * et l'étape par défaut (libellé applicatif).
 * @property icone icône maison résolue d'une étape de modèle, vide si
 * absente ou hors modèle.
 * @property parametres évaluations des paramètres portés par l'étape, dans
 * l'ordre de rendu — `CONFIGURATION` pour une étape de modèle,
 * `INFORMATION` pour l'identité (socle), vide ailleurs.
 */
public data class EtapeParcours(
    public val id: String,
    public val socle: Boolean,
    public val titre: String = "",
    public val icone: String = "",
    public val parametres: List<TemplateParameterEvaluation> = emptyList(),
) {
    /** Identifiants des paramètres portés par l'étape (ordre de rendu). */
    public val idsParametres: List<String> get() = parametres.map { it.parameterId }
}
