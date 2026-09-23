package jo.codeide.feature.newproject

import androidx.annotation.StringRes
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.domain.VerificationCible
import jo.codeide.core.model.AppError
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.model.License
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.TemplateSection

/**
 * Identifiants des étapes du wizard (section 12.2 : cinq étapes numérotées).
 *
 * L'ordre réel et la composition vivent dans [ETAPES_WIZARD] — jamais dans
 * un `when` dispersé : l'étape 11 insérera `FICHIERS` et `RECAPITULATIF`
 * dans la liste sans toucher au cadre.
 */
enum class EtapeId {
    /** Étape 1 — grille de cartes de modèles. */
    MODELE,

    /** Étape 2 — paramètres de section `CONFIGURATION`. */
    CONFIGURATION,

    /** Étape 3 — paramètres de section `INFORMATION` et emplacement. */
    INFORMATIONS,

    /** Étape 4 — fichiers et licence (étape 11). */
    FICHIERS,

    /** Étape 5 — récapitulatif (étape 11). */
    RECAPITULATIF,
}

/**
 * Une étape déclarée du wizard (section 12.2).
 *
 * @property id identifiant stable de l'étape.
 * @property titreRes titre localisé (« Modèle », « Configuration »…).
 */
data class WizardStep(
    val id: EtapeId,
    @field:StringRes val titreRes: Int,
)

/**
 * Étapes actives du wizard (section 12.2 : cinq étapes numérotées, puis
 * l'écran de création hors numérotation).
 */
val ETAPES_WIZARD: List<WizardStep> =
    listOf(
        WizardStep(EtapeId.MODELE, R.string.wizard_etape_modele),
        WizardStep(EtapeId.CONFIGURATION, R.string.wizard_etape_configuration),
        WizardStep(EtapeId.INFORMATIONS, R.string.wizard_etape_informations),
        WizardStep(EtapeId.FICHIERS, R.string.wizard_etape_fichiers),
        WizardStep(EtapeId.RECAPITULATIF, R.string.wizard_etape_recapitulatif),
    )

/**
 * État observable du wizard (UDF, section 5.3) — unique source de vérité
 * des étapes : les fragments ne rendent que cet état, la survie passe par
 * le `SavedStateHandle` du ViewModel.
 *
 * @property chargementCatalogue catalogue en cours de chargement.
 * @property erreurCatalogue le catalogue a échoué (bouton Réessayer).
 * @property modeles catalogue résolu pour la langue courante.
 * @property etape étape courante.
 * @property templateId modèle sélectionné (étape 1).
 * @property evaluation dernière évaluation du moteur (null sans modèle).
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
 * @property options options communes du moteur (étape 4 Fichiers :
 * fichiers optionnels, licence, langue du contenu — section 12.3).
 * @property auteur nom d'auteur des paramètres (affiché avec l'année pour
 * les licences MIT et BSD ; jamais saisi ici).
 * @property annee année civile de l'horloge injectée (affichée avec l'auteur).
 * @property plan plan de création du récapitulatif (dry-run, section 12.3).
 * @property chargementPlan plan en cours de calcul.
 * @property erreurPlan le plan a échoué (message + bouton Réessayer).
 * @property etatCreation écran de création (hors numérotation, section 12.2).
 */
data class EtatWizard(
    val chargementCatalogue: Boolean = true,
    val erreurCatalogue: Boolean = false,
    val modeles: List<TemplateSummaryUi> = emptyList(),
    val etape: EtapeId = EtapeId.MODELE,
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
    val etatCreation: EtatCreation = EtatCreation.Inactif,
) {
    /** L'index de l'étape courante dans [ETAPES_WIZARD] (0 par défaut). */
    val indexEtape: Int get() = ETAPES_WIZARD.indexOfFirst { it.id == etape }.coerceAtLeast(0)

    /** L'étape suivante existe-t-elle ? (fausse sur la dernière). */
    val aUneEtapeSuivante: Boolean get() = indexEtape < ETAPES_WIZARD.lastIndex

    /** L'étape courante est-elle le récapitulatif final ? */
    val estRecapitulatif: Boolean get() = etape == EtapeId.RECAPITULATIF

    /**
     * L'étape courante est-elle valide ? « Suivant » — « Créer le projet »
     * sur le récapitulatif — reste désactivé tant que non (section 12.2).
     */
    val etapeValide: Boolean
        get() =
            when (etape) {
                EtapeId.MODELE -> {
                    templateId != null
                }

                EtapeId.CONFIGURATION -> {
                    parametresValides(TemplateSection.CONFIGURATION)
                }

                EtapeId.INFORMATIONS -> {
                    raisonNom == null &&
                        parametresValides(TemplateSection.INFORMATION) &&
                        emplacement != null &&
                        verificationCible is VerificationCible.Valide
                }

                EtapeId.FICHIERS -> {
                    // Options libres : toute combinaison est valide.
                    true
                }

                EtapeId.RECAPITULATIF -> {
                    // Revalidation globale (section 12.4, point 1 : jamais
                    // confiance à l'UI — le domaine revalidera de toute façon).
                    templateId != null &&
                        raisonNom == null &&
                        parametresValides(TemplateSection.CONFIGURATION) &&
                        parametresValides(TemplateSection.INFORMATION) &&
                        emplacement != null &&
                        verificationCible is VerificationCible.Valide
                }
            }

    /** Les paramètres visibles d'une section sont-ils tous valides ? */
    private fun parametresValides(section: TemplateSection): Boolean =
        evaluation
            ?.parameters
            ?.filter { it.visible && it.section == section }
            ?.all { it.error == null } == true

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
                options != TemplateOptions()

    /** Les paramètres visibles d'une section, dans l'ordre du manifeste. */
    fun parametresSection(section: TemplateSection): List<jo.codeide.core.model.TemplateParameterEvaluation> =
        evaluation?.parameters.orEmpty().filter { it.visible && it.section == section }
}

/**
 * Résumé d'un modèle prêt à afficher (catalogue de l'étape 1) — pastille
 * monogramme, pas de logo officiel (règle de l'étape 9).
 */
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
    data class ChoisirModele(
        val id: TemplateId,
    ) : ActionWizard

    /** Passe à l'étape suivante (garde : étape courante valide). */
    data object Suivant : ActionWizard

    /** Revient à l'étape précédente. */
    data object Precedent : ActionWizard

    /**
     * Revient à une étape précise depuis le récapitulatif (bouton
     * « Modifier » d'une section, section 12.3) — vers l'arrière uniquement.
     */
    data class AllerEtape(
        val id: EtapeId,
    ) : ActionWizard

    /** Saisit le nom du projet (champ commun). */
    data class SaisirNom(
        val valeur: String,
    ) : ActionWizard

    /** Saisit la description (champ commun). */
    data class SaisirDescription(
        val valeur: String,
    ) : ActionWizard

    /** Saisit un paramètre texte — la modification fige la dérivation. */
    data class SaisirTexte(
        val parametreId: String,
        val valeur: String,
    ) : ActionWizard

    /** Choisit une valeur de paramètre (choix ou interrupteur). */
    data class ChoisirValeur(
        val parametreId: String,
        val valeur: String,
    ) : ActionWizard

    /** Resynchronise un champ dérivé : il resuit ses sources. */
    data class Resynchroniser(
        val parametreId: String,
    ) : ActionWizard

    /** Change de dossier « pour cette création uniquement » (sélecteur SAF). */
    data class ChangerEmplacement(
        val grantUri: String,
    ) : ActionWizard

    /** Inclut ou exclut un fichier optionnel (étape 4, section 12.3). */
    data class BasculerFichier(
        val fichier: FichierOptionnel,
        val inclus: Boolean,
    ) : ActionWizard

    /** Choisit la licence à générer (étape 4). */
    data class ChoisirLicence(
        val licence: License,
    ) : ActionWizard

    /** Choisit la langue du contenu généré (étape 4 : `fr` ou `en`). */
    data class ChoisirLangueContenu(
        val langue: String,
    ) : ActionWizard

    /** Lance la création depuis le récapitulatif (garde : état valide). */
    data object Creer : ActionWizard

    /** Recalcule le plan du récapitulatif après un échec (dry-run). */
    data object ReessayerPlan : ActionWizard

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
    data class Recommencer(
        val garderModele: Boolean,
    ) : ActionWizard

    /** Abandon confirmé : referme le wizard (après nettoyage). */
    data object Fermer : ActionWizard
}

/** Fichier optionnel de l'étape 4 (section 12.3). */
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
    data class ProjetCree(
        val id: ProjectId,
    ) : EffetWizard

    /**
     * « Ouvrir le projet » depuis l'écran de succès (étape 13) : ouvre
     * l'espace de travail par-dessus — le wizard se referme à son retour.
     */
    data class OuvrirProjetEditeur(
        val id: ProjectId,
    ) : EffetWizard
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
    data class EnCours(
        val evenements: List<CreationProgress>,
    ) : EtatCreation

    /**
     * Création réussie (section 12.3 : animation sobre, boutons).
     *
     * @property projet projet enregistré (registre + disque).
     */
    data class Succes(
        val projet: Project,
    ) : EtatCreation

    /**
     * Création échouée : message compréhensible, nettoyage signalé,
     * boutons Réessayer et Copier les détails (section 12.3).
     *
     * @property erreur erreur typée (expurgée par construction).
     * @property residues chemins relatifs impossibles à supprimer lors du
     * rollback — vide si le nettoyage a été complet.
     */
    data class Echec(
        val erreur: AppError,
        val residues: List<String>,
    ) : EtatCreation
}
