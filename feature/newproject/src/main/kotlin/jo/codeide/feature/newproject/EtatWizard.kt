package jo.codeide.feature.newproject

import androidx.annotation.StringRes
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.domain.VerificationCible
import jo.codeide.core.model.AppError
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.model.EtapeParcours
import jo.codeide.core.model.License
import jo.codeide.core.model.ParcoursCreation
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan

/**
 * Identifiant d'une étape du wizard (ADR 0155 §5) : **type fermé** — les
 * quatre identifiants du socle imposé par l'application, plus l'identifiant
 * personnalisé de toute étape de modèle déclarée (bloc `wizard` du
 * manifeste) ou de l'étape par défaut « Configuration ».
 *
 * La liste des étapes actives ne vit plus dans une constante
 * ([ETAPES_WIZARD] supprimée — ADR 0155 amende l'ADR 0020) : elle est
 * **calculée par le domaine** ([ParcoursCreation]) et portée par l'état.
 */
public sealed interface EtapeId {
    /** Clé stable de l'étape — persistance `SavedStateHandle` et parcours. */
    public val cle: String

    /** Étape socle « Modèle » — grille de cartes de modèles. */
    public data object Modele : EtapeId {
        override val cle: String get() = "modele"
    }

    /** Étape socle « Identité » — nom, description, emplacement (+ paramètres `INFORMATION`). */
    public data object Identite : EtapeId {
        override val cle: String get() = "identite"
    }

    /** Étape socle « Fichiers » — options adaptées au modèle (ADR 0155 §2). */
    public data object Fichiers : EtapeId {
        override val cle: String get() = "fichiers"
    }

    /** Étape socle « Récapitulatif » — fidèle au parcours réellement suivi. */
    public data object Recapitulatif : EtapeId {
        override val cle: String get() = "recapitulatif"
    }

    /**
     * Étape de modèle — identifiant déclaré par le manifeste
     * (`^[a-z][a-z0-9-]*$`), ou `configuration` pour l'étape par défaut
     * matérialisée par le moteur (paramètres `CONFIGURATION` non rangés).
     */
    public data class EtapeModele(val id: String) : EtapeId {
        override val cle: String get() = id
    }

    public companion object {
        /** Identifiants du socle, réservés au chargement des manifestes. */
        public val SOCLES: Set<String> = setOf(Modele.cle, Identite.cle, Fichiers.cle, Recapitulatif.cle)

        /** Identifiant de l'étape par défaut « Configuration » (moteur). */
        public const val ID_ETAPE_DEFAUT: String = "configuration"

        /**
         * Retrouve une étape par clé persistée — socle reconnu directement,
         * tout le reste étant une étape de modèle (contrôlée contre le
         * parcours à l'arrivée de l'évaluation : une clé inconnue est
         * ramenée en arrière, jamais sautée en avant).
         */
        public fun deCle(cle: String): EtapeId = when (cle) {
            Modele.cle -> Modele

            Identite.cle -> Identite

            Fichiers.cle -> Fichiers

            Recapitulatif.cle -> Recapitulatif

            // Anciennes clés pré-L5 (sauvegardes d'une version
            // antérieure) : migration mécanique vers le socle.
            "CONFIGURATION" -> EtapeModele(ID_ETAPE_DEFAUT)

            "INFORMATIONS" -> Identite

            else -> EtapeModele(cle)
        }
    }
}

/**
 * État observable du wizard (UDF, section 5.3) — unique source de vérité
 * des étapes : les fragments ne rendent que cet état, la survie passe par
 * le `SavedStateHandle` du ViewModel. Le **parcours** ([parcours]) est
 * recalculé par le domaine à chaque réévaluation du formulaire ; l'indicateur
 * « Étape N sur M » lit M dans cette liste (ADR 0155 §3).
 *
 * @property chargementCatalogue catalogue en cours de chargement.
 * @property erreurCatalogue le catalogue a échoué (bouton Réessayer).
 * @property modeles catalogue résolu pour la langue courante.
 * @property parcours étapes effectives calculées par le domaine
 * ([CalculeParcoursUseCase]) — socle + étapes de modèle dont `when` est
 * vrai ; vide tant qu'aucun modèle n'est sélectionné.
 * @property etape étape courante (identifiant fermé [EtapeId]).
 * @property templateId modèle sélectionné (étape 1).
 * @property evaluation dernière évaluation du moteur (null sans modèle) —
 * porte aussi les étapes effectives, les préréglages et les groupes de
 * fichiers déclarés (ADR 0155).
 * @property nomProjet nom saisi (champ commun, section 12.3).
 * @property description description saisie (champ commun).
 * @property raisonNom raison typée du nom invalide, `null` si valide.
 * @property valeursParametres valeurs saisies des paramètres du modèle.
 * @property modifiesManuellement paramètres modifiés à la main (dérivation
 * figée, section 12.2).
 * @property emplacementOverride emplacement choisi « pour cette création
 * uniquement » (bouton « Changer de dossier »), `null` = dossier de travail.
 * @property emplacement emplacement effectif : l'override, sinon le dossier
 * de travail des Paramètres.
 * @property erreurEmplacement dernier échec de choix SAF (carte emplacement).
 * @property verificationCible résultat de la vérification asynchrone
 * (permission, joignabilité, collision de nom).
 * @property verificationEnCours vérification en cours (indicateur).
 * @property options options communes du moteur (étape Fichiers : fichiers
 * optionnels, licence, langue du contenu — section 12.3).
 * @property auteur nom d'auteur des paramètres (affiché avec l'année pour
 * les licences MIT et BSD ; jamais saisi ici).
 * @property annee année civile de l'horloge injectée (affichée avec l'auteur).
 * @property plan plan de création du récapitulatif et de l'aperçu en direct
 * (dry-run, section 12.3 — recalculé à chaque changement quand l'aperçu est
 * ouvert).
 * @property chargementPlan plan en cours de calcul.
 * @property erreurPlan le plan a échoué (message + bouton Réessayer).
 * @property renommages renommages demandés depuis l'aperçu (ADR 0077) :
 * clé = chemin original du nœud, valeur = nouveau nom du segment — l'aperçu
 * se recalcule après chaque renommage, ce qui est planifié reste ce qui
 * est écrit.
 * @property apercuOuvert aperçu en direct ouvert (L5d) — feuille basse
 * (téléphone) ou panneau latéral (tablette).
 * @property fichiersAjoutes fichiers apparus entre deux plans consécutifs
 * (différentiel de l'aperçu, L5d).
 * @property fichiersRetires fichiers disparus entre deux plans consécutifs
 * (différentiel de l'aperçu, L5d).
 * @property etatCreation écran de création (hors numérotation, section 12.2).
 */
data class EtatWizard(
    val chargementCatalogue: Boolean = true,
    val erreurCatalogue: Boolean = false,
    val modeles: List<TemplateSummaryUi> = emptyList(),
    val parcours: List<EtapeParcours> = emptyList(),
    val etape: EtapeId = EtapeId.Modele,
    val templateId: TemplateId? = null,
    val evaluation: TemplateFormEvaluation? = null,
    val nomProjet: String = "",
    val description: String = "",
    val raisonNom: RaisonValidation? = RaisonValidation.LongueurNom,
    val valeursParametres: Map<String, String> = emptyMap(),
    val modifiesManuellement: Set<String> = emptySet(),
    val emplacementOverride: StorageLocation? = null,
    val emplacement: StorageLocation? = null,
    val erreurEmplacement: ValidationDossier? = null,
    val verificationCible: VerificationCible? = null,
    val verificationEnCours: Boolean = false,
    val options: TemplateOptions = TemplateOptions(),
    val auteur: String = "",
    val annee: String = "",
    val plan: TemplatePlan? = null,
    val chargementPlan: Boolean = false,
    val erreurPlan: Boolean = false,
    val renommages: Map<String, String> = emptyMap(),
    val apercuOuvert: Boolean = false,
    val fichiersAjoutes: List<String> = emptyList(),
    val fichiersRetires: List<String> = emptyList(),
    val etatCreation: EtatCreation = EtatCreation.Inactif,
) {
    /** L'index de l'étape courante dans [parcours] (0 par défaut). */
    val indexEtape: Int get() = parcours.indexOfFirst { it.id == etape.cle }.coerceAtLeast(0)

    /** L'étape courante existe-t-elle dans le parcours effectif ? */
    val etapeExistante: Boolean get() = parcours.any { it.id == etape.cle }

    /** L'étape suivante existe-t-elle ? (fausse sur la dernière). */
    val aUneEtapeSuivante: Boolean get() = etapeExistante && indexEtape < parcours.lastIndex

    /** L'étape courante est-elle le récapitulatif final ? */
    val estRecapitulatif: Boolean get() = etape == EtapeId.Recapitulatif

    /** L'étape courante est-elle l'étape socle « Modèle » ? */
    val estEtapeModele: Boolean get() = etape == EtapeId.Modele

    /**
     * L'étape courante porte-t-elle des paramètres de modèle ? (Étape de
     * modèle ou identité — c'est là que l'aperçu en direct est proposé,
     * ADR 0155 §6.)
     */
    val etapeAvecParametres: Boolean
        get() =
            when (etape) {
                is EtapeId.EtapeModele -> true
                EtapeId.Identite -> true
                else -> false
            }

    /** L'étape de parcours courante, ou `null` hors parcours. */
    val etapeCourante: EtapeParcours? get() = parcours.firstOrNull { it.id == etape.cle }

    /**
     * L'étape courante est-elle valide ? « Suivant » — « Créer le projet »
     * sur le récapitulatif — reste désactivé tant que non (section 12.2).
     *
     * Les messages `ERREUR` des règles de projet **ne bloquent pas** les
     * étapes intermédiaires (l'utilisateur doit pouvoir avancer jusqu'au
     * choix fautif pour le corriger) — ils bloquent la création elle-même
     : la revalidation globale du récapitulatif les refuse.
     */
    val etapeValide: Boolean
        get() =
            when (val etape = etape) {
                EtapeId.Modele -> templateId != null

                EtapeId.Identite ->
                    raisonNom == null &&
                        parametresValides(etapeCourante?.parametres.orEmpty()) &&
                        emplacement != null &&
                        verificationCible is VerificationCible.Valide

                EtapeId.Fichiers ->
                    // Options libres : toute combinaison est valide.
                    true

                EtapeId.Recapitulatif ->
                    // Revalidation globale (section 12.4, point 1 : jamais
                    // confiance à l'UI — le domaine revalidera de toute façon).
                    templateId != null &&
                        raisonNom == null &&
                        emplacement != null &&
                        verificationCible is VerificationCible.Valide &&
                        evaluation != null &&
                        evaluation.isValid &&
                        messagesErreurVides

                is EtapeId.EtapeModele ->
                    // Paramètres VISIBLES de l'étape tous valides ; un
                    // paramètre désactivé (grisé) n'est pas validé (ADR 0153 §2).
                    parametresValides(etapeCourante?.parametres.orEmpty())
            }

    /** Aucun message `ERREUR` dans l'évaluation courante ? (création). */
    private val messagesErreurVides: Boolean
        get() = evaluation?.messages?.none { it.severite == jo.codeide.core.model.SeveriteRegle.ERREUR } ?: true

    /** Les paramètres visibles d'une étape sont-ils tous valides ? */
    private fun parametresValides(parametres: List<jo.codeide.core.model.TemplateParameterEvaluation>): Boolean =
        parametres.filter { it.visible }.all { it.error == null }

    /**
     * Des données ont-elles été saisies ? Conditionne le dialogue de
     * confirmation « Abandonner la création ? » (section 12.2).
     */
    val donneesSaisies: Boolean
        get() =
            templateId != null ||
                nomProjet.isNotBlank() ||
                description.isNotBlank() ||
                valeursParametres.isNotEmpty() ||
                renommages.isNotEmpty() ||
                options != TemplateOptions()

    /**
     * Les paramètres visibles de l'étape courante, dans l'ordre de rendu
     * du parcours (étape de modèle : `CONFIGURATION` déclarée ; identité :
     * `INFORMATION`).
     */
    fun parametresEtapeCourante(): List<jo.codeide.core.model.TemplateParameterEvaluation> =
        etapeCourante?.parametres.orEmpty().filter { it.visible }
}

/** Titres des étapes du socle (ressources applicatives — ADR 0155 §2). */
val TITRES_SOCLE: Map<String, Int> =
    mapOf(
        EtapeId.Modele.cle to R.string.wizard_etape_modele,
        EtapeId.Identite.cle to R.string.wizard_etape_identite,
        EtapeId.Fichiers.cle to R.string.wizard_etape_fichiers,
        EtapeId.Recapitulatif.cle to R.string.wizard_etape_recapitulatif,
    )

/** Résumé d'un modèle prêt à afficher (catalogue de l'étape 1) — pastille
 * monogramme, pas de logo officiel (règle de l'étape 9). */
data class TemplateSummaryUi(
    val id: TemplateId,
    val nom: String,
    val description: String,
    val monogramme: String,
    val tags: List<String>,
)

/** Actions du wizard (chaque interaction utilisateur devient une action). */
sealed interface ActionWizard {
    /** Recharge le catalogue après un échec. */
    data object ReessayerCatalogue : ActionWizard

    /** Sélectionne un modèle (étape 1). */
    data class ChoisirModele(val id: TemplateId) : ActionWizard

    /** Passe à l'étape suivante (garde : étape courante valide). */
    data object Suivant : ActionWizard

    /** Revient à l'étape précédente. */
    data object Precedent : ActionWizard

    /**
     * Revient à une étape précise depuis le récapitulatif (bouton
     * « Modifier » d'une section, section 12.3) — vers l'arrière uniquement.
     */
    data class AllerEtape(val id: EtapeId) : ActionWizard

    /**
     * Applique un préréglage déclaré (ADR 0155, B-8) : pose son jeu de
     * valeurs cohérent en un geste — rien n'est figé
     * ([EtatWizard.modifiesManuellement] inchangé).
     */
    data class AppliquerPreset(val id: String) : ActionWizard

    /**
     * Mode rapide (L5d, plan §9-Q9) : applique le préréglage puis saute
     * directement au récapitulatif — exception déclarée au retour
     * strictement arrière de « Modifier ».
     */
    data class ModeRapide(val id: String) : ActionWizard

    /** Ouvre l'aperçu en direct du plan (L5d, ADR 0155 §6). */
    data object OuvrirApercu : ActionWizard

    /** Referme l'aperçu en direct. */
    data object FermerApercu : ActionWizard

    /** Saisit le nom du projet (champ commun). */
    data class SaisirNom(val valeur: String) : ActionWizard

    /** Saisit la description (champ commun). */
    data class SaisirDescription(val valeur: String) : ActionWizard

    /** Saisit un paramètre texte — la modification fige la dérivation. */
    data class SaisirTexte(val parametreId: String, val valeur: String) : ActionWizard

    /** Choisit une valeur de paramètre (choix ou interrupteur). */
    data class ChoisirValeur(val parametreId: String, val valeur: String) : ActionWizard

    /** Resynchronise un champ dérivé : il resuit ses sources. */
    data class Resynchroniser(val parametreId: String) : ActionWizard

    /** Change de dossier « pour cette création uniquement » (sélecteur SAF). */
    data class ChangerEmplacement(val grantUri: String) : ActionWizard

    /** Inclut ou exclut un fichier optionnel (étape Fichiers, section 12.3). */
    data class BasculerFichier(val fichier: FichierOptionnel, val inclus: Boolean) : ActionWizard

    /** Choisit la licence à générer (étape Fichiers). */
    data class ChoisirLicence(val licence: License) : ActionWizard

    /** Choisit la langue du contenu généré (`fr` ou `en`). */
    data class ChoisirLangueContenu(val langue: String) : ActionWizard

    /** Lance la création depuis le récapitulatif (garde : état valide). */
    data object Creer : ActionWizard

    /** Recalcule le plan après un échec (dry-run). */
    data object ReessayerPlan : ActionWizard

    /**
     * Renomme un nœud de l'aperçu (ADR 0077) : [ancien] est le chemin
     * original du nœud (identité stable), [nouveauNom] un simple segment
     * déjà validé par le dialogue — le domaine revalide tout.
     */
    data class RenommerChemin(val ancien: String, val nouveauNom: String) : ActionWizard

    /** Annule la création en cours (rollback puis retour au récapitulatif). */
    data object AnnulerCreation : ActionWizard

    /** Referme l'écran d'échec sans relancer : retour au récapitulatif. */
    data object RetourRecapitulatif : ActionWizard

    /** Relance la création après un échec. */
    data object ReessayerCreation : ActionWizard

    /**
     * « Ouvrir le projet » depuis l'écran de succès : marque le projet
     * ouvert puis referme le wizard (l'éditeur arrive à l'étape 13 —
     * le marquage prépare le tri des récents).
     */
    data object OuvrirProjetCree : ActionWizard

    /** « Retour à l'accueil » depuis l'écran de succès. */
    data object RetourAccueil : ActionWizard

    /** « Créer un autre projet » : remise à zéro du wizard. */
    data class Recommencer(val garderModele: Boolean) : ActionWizard

    /** Abandon confirmé : referme le wizard (après nettoyage). */
    data object Fermer : ActionWizard
}

/** Fichier optionnel de l'étape Fichiers (section 12.3). */
enum class FichierOptionnel {
    README,
    GITIGNORE,
    EDITORCONFIG,
}

/** Événements ponctuels du wizard, consommés une fois. */
sealed interface EffetWizard {
    /** Referme l'écran (navigation vers l'accueil). */
    data object Fermer : EffetWizard

    /**
     * Création réussie : referme le wizard en signalant le projet à
     * l'accueil pour mise en évidence (étape 11, section 12.3).
     */
    data class ProjetCree(val id: ProjectId) : EffetWizard

    /**
     * « Ouvrir le projet » depuis l'écran de succès (étape 13) : ouvre
     * l'espace de travail par-dessus — le wizard se referme à son retour.
     */
    data class OuvrirProjetEditeur(val id: ProjectId) : EffetWizard
}

/**
 * État de l'écran de création (hors numérotation, section 12.2) : l'hôte
 * remplace les étapes et la barre d'actions par cet écran tant qu'il est
 * actif.
 */
sealed interface EtatCreation {
    /** Le wizard affiche les étapes normalement. */
    data object Inactif : EtatCreation

    /**
     * Création en cours : la liste des événements s'allonge en temps réel.
     *
     * @property evenements événements déjà émis (préparation, dossier,
     * fichiers, enregistrement) dans l'ordre.
     */
    data class EnCours(val evenements: List<CreationProgress>) : EtatCreation

    /**
     * Création réussie (section 12.3 : animation sobre, boutons).
     *
     * @property projet projet enregistré (registre + disque).
     */
    data class Succes(val projet: Project) : EtatCreation

    /**
     * Création échouée : message compréhensible, nettoyage signalé,
     * boutons Réessayer et Copier les détails (section 12.3).
     *
     * @property erreur erreur typée (expurgée par construction).
     * @property residues chemins relatifs impossibles à supprimer lors du
     * rollback — vide si le nettoyage a été complet.
     */
    data class Echec(val erreur: AppError, val residues: List<String>) : EtatCreation
}
