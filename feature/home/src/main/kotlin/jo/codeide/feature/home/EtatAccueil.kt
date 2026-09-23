package jo.codeide.feature.home

import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.model.AppError
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId

/**
 * Tri choisi par l'utilisateur pour la liste de l'accueil (étape 7).
 *
 * Les épingles flottent **toujours** en tête, quel que soit le tri ;
 * « récents » ordonne ensuite par dernier ouvert (jamais ouverts en
 * fin), « nom » par nom croissant insensible à la casse et aux accents.
 */
enum class TriAccueil {
    /** Dernier ouvert d'abord, jamais ouverts en fin. */
    RECENTS,

    /** Nom croissant, insensible à la casse et aux accents. */
    NOM,
}

/**
 * État observable de l'accueil (section 5.3) — étape 7 : la liste des
 * projets complète le bandeau du dossier de travail.
 *
 * [projets] est déjà **filtrée** par la recherche et **triée** selon
 * [tri] : le fragment n'a plus qu'à la rendre. [etatsAcces] porte les
 * états d'accès calculés à la demande (section 5.6 : jamais persistés) ;
 * une clé absente signifie « pas encore vérifié » ou « vérification
 * impossible » — pas de badge.
 *
 * @property chargement vrai jusqu'à la première émission du registre.
 * @property projets projets filtrés et triés pour l'affichage.
 * @property requete recherche courante (appliquée après le délai).
 * @property tri tri choisi par l'utilisateur.
 * @property etatsAcces états d'accès par projet, calculés à la demande.
 * @property rafraichissement vrai pendant la revérification
 * (pull-to-refresh) — pilote l'indicateur de rafraîchissement.
 * @property montrerBandeau l'assistant s'est terminé sans dossier de
 * travail (bandeau « Configurer », étape 5).
 * @property libelleDossier libellé lisible du dossier de travail.
 * @property bootstrapInstalle bootstrap présent (T6) : l'action
 * « Terminal » de la toolbar ouvre l'écran plein écran, sinon elle mène
 * à l'installation — jamais un terminal non fonctionnel.
 * @property erreur le registre est illisible : écran d'erreur avec
 * « Réessayer » ; `null` en situation normale.
 */
data class EtatAccueil(
    val chargement: Boolean = true,
    val projets: List<jo.codeide.core.model.Project> = emptyList(),
    val requete: String = "",
    val tri: TriAccueil = TriAccueil.RECENTS,
    val etatsAcces: Map<ProjectId, ProjectAccessState> = emptyMap(),
    val rafraichissement: Boolean = false,
    val montrerBandeau: Boolean = false,
    val libelleDossier: String? = null,
    /** Bandeau d'invitation au terminal (T3) : configuré mais non installé. */
    val montrerBandeauTerminal: Boolean = false,
    /** Bootstrap installé (T6) : l'action « Terminal » peut l'ouvrir. */
    val bootstrapInstalle: Boolean = false,
    val erreur: AppError? = null,
    /** Projet créé par le wizard : défilement + surlignage (étape 11). */
    val projetEnEvidence: ProjectId? = null,
)

/**
 * Intentions utilisateur de l'accueil (section 5.3 : `XxxAction`).
 *
 * Le fragment n'émet que des actions ; toute la logique vit dans le
 * ViewModel.
 */
sealed interface ActionAccueil {
    /** Frappe dans le champ de recherche (appliquée avec délai). */
    data class Rechercher(
        val requete: String,
    ) : ActionAccueil

    /** Change le tri de la liste. */
    data class ChangerTri(
        val tri: TriAccueil,
    ) : ActionAccueil

    /** Revérifie les états d'accès (pull-to-refresh). */
    data object Rafraichir : ActionAccueil

    /** Ouvre un projet (marquage « ouvert », tri des récents). */
    data class OuvrirProjet(
        val id: ProjectId,
    ) : ActionAccueil

    /**
     * Renomme un projet (libellé en base uniquement, ADR 0012).
     *
     * @property nom nouveau libellé — validé côté ViewModel, refus
     * typé si vide ou trop long.
     */
    data class RenommerProjet(
        val id: ProjectId,
        val nom: String,
    ) : ActionAccueil

    /** Épingle ou désépingle un projet. */
    data class EpinglerProjet(
        val id: ProjectId,
        val epingle: Boolean,
    ) : ActionAccueil

    /** Retire un projet de la liste (le dossier reste sur disque). */
    data class RetirerProjet(
        val id: ProjectId,
    ) : ActionAccueil

    /**
     * Supprime un projet **du disque** (dossier + entrée de registre).
     * L'UI a déjà demandé la confirmation avec rappel du nom.
     */
    data class SupprimerDuDisque(
        val id: ProjectId,
    ) : ActionAccueil

    /**
     * Un dossier est revenu du sélecteur SAF (« Ouvrir un dossier
     * existant »).
     *
     * @property grantUri URI d'arborescence proposée par le sélecteur.
     */
    data class ImporterDossier(
        val grantUri: String,
    ) : ActionAccueil

    /**
     * Un dossier est revenu du sélecteur SAF pour relocaliser un projet
     * marqué « Permission perdue » ou « Introuvable ».
     */
    data class RelocaliserProjet(
        val id: ProjectId,
        val grantUri: String,
    ) : ActionAccueil

    /** Réessaie après un échec de lecture du registre. */
    data object Reessayer : ActionAccueil

    /**
     * Ouvre le terminal intégré depuis la toolbar (T6) : écran plein
     * écran si le bootstrap est installé, écran d'installation sinon
     * (section 7 du prompt Terminal-1).
     */
    data object OuvrirTerminal : ActionAccueil

    /**
     * Met en évidence le projet créé par le wizard (section 12.3 :
     * « le nouveau projet apparaît, mis en évidence » — étape 11).
     */
    data class SurlignerProjet(
        val id: ProjectId,
    ) : ActionAccueil
}

/**
 * Événements ponctuels de l'accueil (section 5.3 : `XxxEffect`) —
 * consommés par le fragment en messages (snackbars) ; ne survivent ni
 * à la rotation ni à la mort du processus.
 */
sealed interface EffetAccueil {
    /** Projet importé avec succès. */
    data class ProjetImporte(
        val nom: String,
    ) : EffetAccueil

    /** Le dossier choisi est déjà référencé. */
    data object DossierDejaPresent : EffetAccueil

    /** Dossier refusé par Android 11+ (message actionnable). */
    data class DossierRefuse(
        val raison: ForbiddenFolders.Reason,
    ) : EffetAccueil

    /** Projet relocalisé avec succès. */
    data class ProjetDeplace(
        val nom: String,
    ) : EffetAccueil

    /** Projet retiré de la liste (dossier intact). */
    data object ProjetRetire : EffetAccueil

    /** Projet supprimé du disque. */
    data object ProjetSupprime : EffetAccueil

    /** Ouvrir le projet dans l'espace de travail (étape 13). */
    data class OuvrirEditeur(
        val id: ProjectId,
    ) : EffetAccueil

    /** Ouvrir l'écran plein écran du terminal (T6, bootstrap installé). */
    data object OuvrirTerminalEcran : EffetAccueil

    /** Ouvrir l'écran d'installation du bootstrap (T6, section 7). */
    data object OuvrirInstallationTerminal : EffetAccueil

    /** Échec typé d'une action (message localisé par l'UI). */
    data class Echec(
        val erreur: AppError,
    ) : EffetAccueil
}
