package jo.codeide.core.domain

import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.StateFlow

/**
 * État de la session de projet VU DES ÉCRANS (mission « Cycle de vie »
 * étape E — cohérence de navigation) : projection LECTURE SEULE du
 * contrat de `:core:session`, sans exposer ce module aux features.
 *
 * L'accueil affiche l'état « projet ouvert » (matrice de transitions
 * §6.3 du prompt 3 : « Accueil → Éditeur (P) : session P ouverte ;
 * UNE seule ») — la valeur survit à la fermeture transitoire
 * ([Fermeture]) pour montrer la transition honnêtement, puis revient à
 * [Aucune].
 */
public sealed interface EtatSessionProjetVue {
    /** Aucune session de projet : écran de repos. */
    public data object Aucune : EtatSessionProjetVue {
        override val projet: ProjectId? get() = null
    }

    /** Ouverture en cours : les travaux de [projet] démarrent. */
    public data class Ouverture(override val projet: ProjectId) : EtatSessionProjetVue

    /** Projet ouvert : session active. */
    public data class Ouverte(override val projet: ProjectId) : EtatSessionProjetVue

    /** Fermeture en cours : les ressources de [projet] se libèrent. */
    public data class Fermeture(override val projet: ProjectId) : EtatSessionProjetVue

    /** Projet concerné par l'état — `null` pour [Aucune]. */
    public val projet: ProjectId?
}

/**
 * Port d'observation de la session de projet (mission « Cycle de
 * vie » étape E) : l'accueil observe l'état « projet ouvert » SANS
 * dépendre de `:core:session` (règle de dépendance : seuls
 * `:feature:editor` et `:app` voient le contrat). L'implémentation est
 * fournie par le module éditeur, qui possède la session — la liaison
 * Hilt vit dans le composant singleton, donc l'injectable ici résout
 * au runtime.
 *
 * Usage : injecter ce port dans un ViewModel d'écran, collecter
 * [etatSession] comme n'importe quel flux d'état. Aucune mutation n'est
 * offerte — la session ne se pilote que par l'espace de travail.
 */
public interface ObservateurSessionProjet {
    /** Projection observable de l'état de la session unique du processus. */
    public val etatSession: StateFlow<EtatSessionProjetVue>
}
