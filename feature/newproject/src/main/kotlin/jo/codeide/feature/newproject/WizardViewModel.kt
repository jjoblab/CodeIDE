package jo.codeide.feature.newproject

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.MarkProjectOpenedUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReleaseCreationLocationUseCase
import jo.codeide.core.domain.ResolveCreationLocationUseCase
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.domain.VerificationCible
import jo.codeide.core.domain.VerifyCreationTargetUseCase
import jo.codeide.core.domain.templates.CalculeParcoursUseCase
import jo.codeide.core.domain.templates.CreateProjectUseCase
import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.EvaluerNomProjetUseCase
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.model.EtapeParcours
import jo.codeide.core.model.License
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel partagé du wizard de création (étape 10 — section 12.2), scopé à
 * l'hôte [NewProjectFragment] : les fragments d'étapes le récupèrent par
 * `viewModels({ requireParentFragment() })` et ne portent **aucun état
 * propre** — la rotation et la mort du processus passent par l'état unique
 * et le [SavedStateHandle].
 *
 * Responsabilités :
 * - charger le catalogue ([ListTemplatesUseCase]) et le recharger sur
 *   changement de langue ou sur « Réessayer » ;
 * - réévaluer le formulaire **à chaque changement**
 *   ([EvaluateTemplateFormUseCase]) : visibilité, valeurs dérivées,
 *   validité, **étapes effectives et préréglages** (ADR 0155) — le rendu
 *   dynamique de la section 12.2 ;
 * - **calculer le parcours effectif** ([CalculeParcoursUseCase]) à chaque
 *   réévaluation : une étape dont `when` disparaît ramène l'utilisateur à
 *   la dernière étape effective (jamais sauté en avant), les valeurs
 *   saisies restent conservées ;
 * - évaluer le nom du projet (raison typée, section 12.3) ;
 * - résoudre l'emplacement effectif (override éphémère ou dossier de
 *   travail des Paramètres) et vérifier la cible **avec délai** (permission,
 *   joignabilité, collision de nom — section 12.3) ;
 * - piloter la machine à étapes (suivant gardé par validité, précédent,
 *   « Modifier » du récapitulatif vers l'arrière uniquement) ;
 * - appliquer les **préréglages** déclarés (ADR 0155, B-8) et le **mode
 *   rapide** (préréglage → récapitulatif, L5d) ;
 * - porter les **options communes** de l'étape Fichiers (section 12.3) et
 *   **planifier** le récapitulatif et l'**aperçu en direct** (dry-run — ce
 *   qui est planifié est ce qui sera écrit, à l'octet près ; antirebond
 *   300 ms, différentiel ajouté/retiré entre deux plans consécutifs) ;
 * - **créer** le projet ([CreateProjectUseCase] : revalidation, écritures
 *   avec progression temps réel, rollback à tout échec ou annulation,
 *   registre en dernier) ; l'annulation déclenche le rollback côté domaine
 *   puis ramène au récapitulatif ;
 * - à l'abandon confirmé, relâcher l'emplacement éphémère de l'override
 *   avant l'effet de fermeture.
 *
 * Les cas d'usage sont injectés un par un (règle 8 : l'agrégation par
 * le domaine, les regrouper créerait un objet céleste).
 */
@HiltViewModel
// Machine à états complète en UN état observable unique — exemption
// LargeClass ciblée (règle 16 du prompt maître).
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class WizardViewModel
@Inject
constructor(
    private val listerModeles: ListTemplatesUseCase,
    private val evaluerFormulaire: EvaluateTemplateFormUseCase,
    private val calculeParcours: CalculeParcoursUseCase,
    private val evaluerNom: EvaluerNomProjetUseCase,
    private val resoudreEmplacement: ResolveCreationLocationUseCase,
    private val relacherEmplacement: ReleaseCreationLocationUseCase,
    private val verifierCible: VerifyCreationTargetUseCase,
    private val planifierCreation: PlanProjectCreationUseCase,
    private val creerProjet: CreateProjectUseCase,
    private val marquerOuvert: MarkProjectOpenedUseCase,
    private val horloge: TimeProvider,
    private val observerParametres: ObserveSettingsUseCase,
    private val journal: AppLogger,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val etatInterne = MutableStateFlow(EtatWizard())

    /** État observable du wizard (UDF, section 5.3). */
    val etat: StateFlow<EtatWizard> = etatInterne.asStateFlow()

    private val effetsInterne = Channel<EffetWizard>(Channel.BUFFERED)

    /** Événements ponctuels (fermeture), consommés une fois. */
    val effets: Flow<EffetWizard> = effetsInterne.receiveAsFlow()

    /** Langue courante des libellés de modèles (repli anglais). */
    private var langueModeles: String = ""

    /** Vérification de cible en cours (délai inclus). */
    private var verificationJob: Job? = null

    /** Création en cours (annulable — le domaine roule le rollback). */
    private var creationJob: Job? = null

    /** Planification en cours (antirebond de l'aperçu, L5d). */
    private var planificationJob: Job? = null

    /** Chemins du dernier plan connu — base du différentiel (L5d). */
    private var cheminsPlanPrecedent: Set<String> = emptySet()

    /**
     * Le différentiel est-il armé ? Faux à l'ouverture de l'aperçu (le
     * premier plan posé **est** la base) et hors aperçu — jamais de
     * « tout ajouté » qui ne signifierait rien.
     */
    private var differentielArme: Boolean = false

    init {
        restaurer()
        observerReglages()
        chargerCatalogue()
        if (etatInterne.value.etape == EtapeId.Recapitulatif) {
            // Mort du processus sur le récapitulatif : le plan se
            // recalcule (il n'est pas sauvé — il se redéduit).
            planifier()
        }
    }

    /**
     * Applique une action du wizard (dispatch exhaustif de l'UDF).
     *
     * Exemption detekt ciblée (règle 16) : un branch `when` par action
     * UDF (section 5.3) — tout éclatement arbitraire casserait la
     * lecture exhaustive du contrat.
     */
    @Suppress("CyclomaticComplexMethod")
    fun action(action: ActionWizard) {
        when (action) {
            ActionWizard.ReessayerCatalogue -> chargerCatalogue()
            is ActionWizard.ChoisirModele -> choisirModele(action.id)
            ActionWizard.Suivant -> avancer()
            ActionWizard.Precedent -> reculer()
            is ActionWizard.AllerEtape -> allerEtape(action.id)
            is ActionWizard.AppliquerPreset -> appliquerPreset(action.id, versRecapitulatif = false)
            is ActionWizard.ModeRapide -> appliquerPreset(action.id, versRecapitulatif = true)
            ActionWizard.OuvrirApercu -> ouvrirApercu()
            ActionWizard.FermerApercu -> fermerApercu()
            is ActionWizard.SaisirNom -> saisirNom(action.valeur)
            is ActionWizard.SaisirDescription -> saisirDescription(action.valeur)
            is ActionWizard.SaisirTexte -> saisirTexte(action.parametreId, action.valeur)
            is ActionWizard.ChoisirValeur -> choisirValeur(action.parametreId, action.valeur)
            is ActionWizard.Resynchroniser -> resynchroniser(action.parametreId)
            is ActionWizard.ChangerEmplacement -> changerEmplacement(action.grantUri)
            is ActionWizard.BasculerFichier -> basculerFichier(action.fichier, action.inclus)
            is ActionWizard.ChoisirLicence -> choisirLicence(action.licence)
            is ActionWizard.ChoisirLangueContenu -> choisirLangueContenu(action.langue)
            ActionWizard.Creer -> creer()
            ActionWizard.ReessayerCreation -> creer()
            ActionWizard.ReessayerPlan -> planifier()
            is ActionWizard.RenommerChemin -> renommerChemin(action.ancien, action.nouveauNom)
            ActionWizard.AnnulerCreation -> annulerCreation()
            ActionWizard.RetourRecapitulatif -> retourRecapitulatif()
            ActionWizard.OuvrirProjetCree -> ouvrirProjetCree()
            ActionWizard.RetourAccueil -> retourAccueil()
            is ActionWizard.Recommencer -> recommencer(action.garderModele)
            ActionWizard.Fermer -> fermer()
        }
    }

    // ------------------------------------------------------ catalogue

    /** Charge (ou recharge) le catalogue des modèles. */
    private fun chargerCatalogue() {
        etatInterne.update { it.copy(chargementCatalogue = true, erreurCatalogue = false) }
        viewModelScope.launch {
            when (val resultat = listerModeles(langueCourante())) {
                is AppResult.Success -> {
                    etatInterne.update { etat ->
                        etat.copy(
                            chargementCatalogue = false,
                            erreurCatalogue = false,
                            modeles =
                            resultat.value.map { resume ->
                                TemplateSummaryUi(
                                    id = resume.id,
                                    nom = resume.nom,
                                    description = resume.description,
                                    monogramme = resume.iconKey,
                                    tags = resume.tags,
                                )
                            },
                        )
                    }
                }

                is AppResult.Failure -> {
                    journal.w(TAG) { "échec du catalogue de modèles" }
                    etatInterne.update {
                        it.copy(chargementCatalogue = false, erreurCatalogue = true, modeles = emptyList())
                    }
                }
            }
        }
    }

    /** Suit les réglages : emplacement par défaut, langue et profil. */
    private fun observerReglages() {
        viewModelScope.launch {
            observerParametres().collect { reglages ->
                val langue = langueDesLibelles(reglages.languageTag)
                val recharger = langue != langueModeles && langueModeles != ""
                langueModeles = langue
                etatInterne.update { etat ->
                    val effectif = etat.emplacementOverride ?: reglages.workspace
                    etat.copy(
                        emplacement = effectif,
                        auteur = reglages.authorName,
                        annee = anneeCourante(),
                    )
                }
                if (recharger) chargerCatalogue() else reevaluer()
                replanifierVerification()
            }
        }
    }

    // ------------------------------------------------------- étapes

    /** Sélectionne un modèle puis réévalue le formulaire. */
    private fun choisirModele(id: TemplateId) {
        savedState[CLE_TEMPLATE] = id.value
        etatInterne.update { it.copy(templateId = id) }
        reevaluer()
    }

    /** Passe à l'étape suivante si l'étape courante est valide. */
    private fun avancer() {
        val etat = etatInterne.value
        if (!etat.etapeValide || !etat.aUneEtapeSuivante) return
        val suivante = etat.parcours[etat.indexEtape + 1]
        sauvegarderEtape(suivante)
        etatInterne.update { it.copy(etape = EtapeId.deCle(suivante.id)) }
        if (suivante.id == EtapeId.Recapitulatif.cle) planifier()
    }

    /** Revient à l'étape précédente (garde : première étape). */
    private fun reculer() {
        val etat = etatInterne.value
        if (etat.indexEtape <= 0) return
        val precedente = etat.parcours[etat.indexEtape - 1]
        sauvegarderEtape(precedente)
        etatInterne.update { it.copy(etape = EtapeId.deCle(precedente.id)) }
    }

    /**
     * « Modifier » du récapitulatif (section 12.3) : retour direct à
     * une étape — **vers l'arrière uniquement** (aucun raccourci qui
     * esquiverait les gardes de validité).
     */
    private fun allerEtape(id: EtapeId) {
        val etat = etatInterne.value
        val cible = etat.parcours.indexOfFirst { it.id == id.cle }
        if (cible < 0 || cible >= etat.indexEtape) return
        sauvegarderEtape(etat.parcours[cible])
        etatInterne.update { it.copy(etape = id) }
    }

    /** Persiste la clé de l'étape de parcours devenue courante. */
    private fun sauvegarderEtape(etape: EtapeParcours) {
        savedState[CLE_ETAPE] = etape.id
    }

    // ------------------------------------------------- champs communs

    /** Saisit le nom : réévalue formulaire (dérivations) et cible. */
    private fun saisirNom(valeur: String) {
        savedState[CLE_NOM] = valeur
        etatInterne.update {
            it.copy(nomProjet = valeur, raisonNom = evaluerNom(valeur.trim()))
        }
        reevaluer()
        replanifierVerification()
    }

    /** Saisit la description (aucune dérivation n'en dépend). */
    private fun saisirDescription(valeur: String) {
        savedState[CLE_DESCRIPTION] = valeur
        etatInterne.update { it.copy(description = valeur) }
    }

    /** Saisit un paramètre texte : dérivation figée (section 12.2). */
    private fun saisirTexte(parametreId: String, valeur: String) {
        val etat = etatInterne.value
        val valeurs = etat.valeursParametres + (parametreId to valeur)
        val manuels = etat.modifiesManuellement + parametreId
        persisterValeurs(valeurs, manuels)
        etatInterne.update {
            it.copy(valeursParametres = valeurs, modifiesManuellement = manuels)
        }
        reevaluer()
    }

    /** Choisit une valeur (choix ou interrupteur). */
    private fun choisirValeur(parametreId: String, valeur: String) {
        val etat = etatInterne.value
        val valeurs = etat.valeursParametres + (parametreId to valeur)
        persisterValeurs(valeurs, etat.modifiesManuellement)
        etatInterne.update { it.copy(valeursParametres = valeurs) }
        reevaluer()
    }

    /** Resynchronise un champ dérivé : il resuit ses sources. */
    private fun resynchroniser(parametreId: String) {
        val manuels = etatInterne.value.modifiesManuellement - parametreId
        savedState[CLE_PARAM_MANUELS] = ArrayList(manuels)
        etatInterne.update { it.copy(modifiesManuellement = manuels) }
        reevaluer()
    }

    // -------------------------------------------------- préréglages

    /**
     * Applique un préréglage déclaré (ADR 0155, B-8) : pose son jeu de
     * valeurs en un geste — [EtatWizard.modifiesManuellement] reste
     * inchangé (rien n'est figé, les champs dérivés restent
     * resynchronisables). [versRecapitulatif] implémente le **mode
     * rapide** (L5d, plan §9-Q9) : préréglage → récapitulatif.
     */
    private fun appliquerPreset(presetId: String, versRecapitulatif: Boolean) {
        val etat = etatInterne.value
        if (etat.templateId == null) return
        val preset = etat.evaluation?.presets?.firstOrNull { it.id == presetId } ?: return
        val valeurs = etat.valeursParametres + preset.valeurs
        persisterValeurs(valeurs, etat.modifiesManuellement)
        etatInterne.update {
            if (versRecapitulatif) {
                it.copy(valeursParametres = valeurs, etape = EtapeId.Recapitulatif)
            } else {
                it.copy(valeursParametres = valeurs)
            }
        }
        if (versRecapitulatif) {
            savedState[CLE_ETAPE] = EtapeId.Recapitulatif.cle
            planifier()
        } else {
            reevaluer()
        }
    }

    // -------------------------------------------------- emplacement

    /** Résout le dossier choisi « pour cette création uniquement ». */
    private fun changerEmplacement(grantUri: String) {
        viewModelScope.launch {
            when (val valide = resoudreEmplacement(grantUri)) {
                is ValidationDossier.Valide -> {
                    persisterOverride(valide.emplacement)
                    etatInterne.update {
                        it.copy(
                            emplacementOverride = valide.emplacement,
                            emplacement = valide.emplacement,
                            erreurEmplacement = null,
                        )
                    }
                }

                else -> {
                    etatInterne.update { it.copy(erreurEmplacement = valide) }
                }
            }
            replanifierVerification()
        }
    }

    /** Abandon confirmé : nettoie puis demande la fermeture. */
    private fun fermer() {
        creationJob?.cancel()
        viewModelScope.launch {
            relacherEmplacement(etatInterne.value.emplacementOverride)
            effetsInterne.send(EffetWizard.Fermer)
        }
    }

    // ---------------------------------------------------- options

    /** Inclut ou exclut un fichier optionnel (étape Fichiers, section 12.3). */
    private fun basculerFichier(fichier: FichierOptionnel, inclus: Boolean) {
        val options =
            when (fichier) {
                FichierOptionnel.README -> etatInterne.value.options.copy(includeReadme = inclus)
                FichierOptionnel.GITIGNORE -> etatInterne.value.options.copy(includeGitignore = inclus)
                FichierOptionnel.EDITORCONFIG -> etatInterne.value.options.copy(includeEditorconfig = inclus)
            }
        persisterOptions(options)
        etatInterne.update { it.copy(options = options) }
    }

    /** Choisit la licence à générer (étape Fichiers). */
    private fun choisirLicence(licence: License) {
        val options = etatInterne.value.options.copy(license = licence)
        persisterOptions(options)
        etatInterne.update { it.copy(options = options) }
    }

    /**
     * Choisit la langue du contenu généré — bornée aux langues connues du
     * moteur, tout autre valeur est ignorée.
     */
    private fun choisirLangueContenu(langue: String) {
        if (!TemplateOptions.langueValide(langue)) return
        val options = etatInterne.value.options.copy(contentLanguage = langue)
        persisterOptions(options)
        etatInterne.update { it.copy(options = options) }
    }

    /** Persiste les options dans le `SavedStateHandle`. */
    private fun persisterOptions(options: TemplateOptions) {
        savedState[CLE_OPT_README] = options.includeReadme
        savedState[CLE_OPT_GITIGNORE] = options.includeGitignore
        savedState[CLE_OPT_EDITORCONFIG] = options.includeEditorconfig
        savedState[CLE_OPT_LICENCE] = options.license.name
        savedState[CLE_OPT_LANGUE] = options.contentLanguage
    }

    /** Options restaurées après mort du processus (défauts sains). */
    private fun restaurerOptions(): TemplateOptions = TemplateOptions(
        includeReadme = savedState.get<Boolean>(CLE_OPT_README) ?: true,
        includeGitignore = savedState.get<Boolean>(CLE_OPT_GITIGNORE) ?: true,
        includeEditorconfig = savedState.get<Boolean>(CLE_OPT_EDITORCONFIG) ?: true,
        license =
        savedState.get<String>(CLE_OPT_LICENCE)?.let(License::fromPersistedName)
            ?: License.NONE,
        contentLanguage =
        savedState.get<String>(CLE_OPT_LANGUE)?.takeIf(TemplateOptions::langueValide)
            ?: TemplateOptions.LANGUE_DEFAUT,
    )

    // ---------------------------------------------- aperçu / plan / création

    /** Ouvre l'aperçu en direct (L5d) : le plan courant devient la base. */
    private fun ouvrirApercu() {
        cheminsPlanPrecedent =
            etatInterne.value.plan?.fichiers?.map { it.chemin }?.toSet() ?: emptySet()
        differentielArme = false
        etatInterne.update {
            it.copy(apercuOuvert = true, fichiersAjoutes = emptyList(), fichiersRetires = emptyList())
        }
        planifier()
    }

    /** Referme l'aperçu en direct (le plan du récapitulatif reste utile). */
    private fun fermerApercu() {
        etatInterne.update {
            it.copy(apercuOuvert = false, fichiersAjoutes = emptyList(), fichiersRetires = emptyList())
        }
    }

    /**
     * Planifie immédiatement (entrée récapitulatif, ouverture de l'aperçu,
     * « Réessayer ») — annule toute planification différée en vol.
     */
    private fun planifier() {
        planificationJob?.cancel()
        lancerPlanification()
    }

    /**
     * Replanifie **après** une réévaluation du formulaire (L5d) : seulement
     * si l'aperçu est ouvert ou que le récapitulatif est affiché, avec un
     * antirebond de 300 ms (ADR 0155 §6) — le plan est un rendu complet
     * du projet, chaque frappe ne doit pas le déclencher.
     */
    private fun planifierDiffere() {
        val etat = etatInterne.value
        if (!etat.apercuOuvert && !etat.estRecapitulatif) return
        planificationJob?.cancel()
        planificationJob =
            viewModelScope.launch {
                delay(DELAI_ANTIREBOND_MS)
                lancerPlanification()
            }
    }

    /**
     * Produit le plan (dry-run, section 12.4) : il n'exige ni emplacement
     * ni cible ([PlanProjectCreationUseCase] n'écrit rien — ADR 0017, le
     * plan ne dépend que du modèle, des valeurs et des options) ; seul le
     * modèle compte, la revalidation complète a lieu à la création.
     *
     * À chaque succès, le **différentiel** ajouté/retiré est calculé entre
     * deux plans consécutifs (L5d) — armé seulement quand l'aperçu est
     * ouvert, sinon il ne signifierait rien.
     */
    private fun lancerPlanification() {
        val etat = etatInterne.value
        if (etat.templateId == null) {
            etatInterne.update { it.copy(plan = null, chargementPlan = false, erreurPlan = true) }
            return
        }
        etatInterne.update { it.copy(chargementPlan = true, erreurPlan = false) }
        viewModelScope.launch {
            when (val resultat = planifierCreation(requeteDeLEtat())) {
                is AppResult.Success -> {
                    val chemins = resultat.value.fichiers.map { it.chemin }.toSet()
                    val (ajoutes, retires) =
                        if (differentielArme) {
                            (chemins - cheminsPlanPrecedent).sorted() to (cheminsPlanPrecedent - chemins).sorted()
                        } else {
                            emptyList<String>() to emptyList()
                        }
                    differentielArme = true
                    cheminsPlanPrecedent = chemins
                    etatInterne.update {
                        it.copy(
                            plan = resultat.value,
                            chargementPlan = false,
                            erreurPlan = false,
                            fichiersAjoutes = ajoutes,
                            fichiersRetires = retires,
                        )
                    }
                }

                is AppResult.Failure -> {
                    journal.w(TAG) { "plan impossible (modèle ${etat.templateId.value})" }
                    etatInterne.update {
                        it.copy(plan = null, chargementPlan = false, erreurPlan = true)
                    }
                }
            }
        }
    }

    /**
     * Lance la création (section 12.4) : le flot froid du domaine est
     * collecté ici — chaque événement alimente [EtatCreation.EnCours],
     * l'événement terminal tranche succès ou échec typé.
     *
     * **Pré-vol** (v0.31.5) : la vérification de l'étape Identité
     * (délai 400 ms) peut être obsolète au moment de l'appui sur
     * « Créer » (résidu d'une tentative échouée, dossier déposé
     * entre-temps par un gestionnaire de fichiers ou un nuage) : la
     * cible est re-vérifiée juste avant d'écrire. Un échec de pré-vol
     * s'affiche honnêtement SANS lancer une création condamnée.
     */
    private fun creer() {
        val etat = etatInterne.value
        if (!etat.estRecapitulatif || !etat.etapeValide) return
        if (etat.templateId == null || etat.emplacement == null) return
        creationJob?.cancel()
        val requete = requeteDeLEtat()
        etatInterne.update { it.copy(etatCreation = EtatCreation.EnCours(emptyList())) }
        creationJob =
            viewModelScope.launch {
                val verification =
                    verifierCible(requete.parentLocation.documentUri, requete.name)
                when (verification) {
                    VerificationCible.Valide -> {
                        lancerCreation(requete)
                    }

                    VerificationCible.NomDejaPris -> {
                        publierEchecPreVol(
                            AppError.Storage(
                                AppError.StorageReason.AlreadyExists,
                                PRE_VOL_COLLISION,
                            ),
                        )
                    }

                    is VerificationCible.EmplacementInaccessible -> {
                        publierEchecPreVol(verification.erreur)
                    }

                    is VerificationCible.Erreur -> {
                        publierEchecPreVol(verification.erreur)
                    }
                }
            }
    }

    /** Publie l'échec du pré-vol (aucune écriture n'a eu lieu). */
    private fun publierEchecPreVol(erreur: AppError) {
        etatInterne.update {
            it.copy(etatCreation = EtatCreation.Echec(erreur = erreur, residues = emptyList()))
        }
    }

    /** Collecte le flot du domaine — écritures, progression, terminal. */
    private suspend fun lancerCreation(requete: CreateProjectRequest) {
        try {
            creerProjet.create(requete).collect { evenement ->
                when (evenement) {
                    is CreationProgress.Termine -> {
                        when (val resultat = evenement.result) {
                            is AppResult.Success -> {
                                journal.i(TAG) { "projet créé" }
                                etatInterne.update {
                                    it.copy(etatCreation = EtatCreation.Succes(resultat.value))
                                }
                            }

                            is AppResult.Failure -> {
                                etatInterne.update {
                                    it.copy(
                                        etatCreation =
                                        EtatCreation.Echec(
                                            erreur = resultat.error,
                                            residues = evenement.residues,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        etatInterne.update { courant ->
                            val enCours = courant.etatCreation as? EtatCreation.EnCours
                            courant.copy(
                                etatCreation =
                                EtatCreation.EnCours(enCours?.evenements.orEmpty() + evenement),
                            )
                        }
                    }
                }
            }
        } catch (annulation: CancellationException) {
            // Annulation utilisateur : le domaine a déjà roulé le
            // rollback (NonCancellable) — retour au récapitulatif.
            etatInterne.update { it.copy(etatCreation = EtatCreation.Inactif) }
            throw annulation
        }
    }

    /**
     * Renomme un nœud de l'aperçu (ADR 0077) : la carte des renommages
     * grossit, le plan se recalcule **avec** la carte — l'arbre affiché
     * reflète les nouveaux chemins, chaque nœud gardant son identité
     * originale pour les renommages suivants.
     *
     * Échec du plan (collision détectée par le domaine, cas exotique non
     * couvert par la validation du dialogue) : le renommage est **annulé**
     * et l'erreur du plan s'affiche — « Réessayer » replanifie alors avec
     * l'ancienne carte et l'arbre revient intact. Jamais d'impasse.
     */
    @Suppress("ReturnCount") // Clauses de garde du renommage (règle 16).
    private fun renommerChemin(
        ancien: String,
        nouveauNom: String,
    ) {
        val etat = etatInterne.value
        if (!etat.estRecapitulatif || ancien.isBlank()) return
        if (ancien == DOSSIER_METADATA || ancien.startsWith("$DOSSIER_METADATA/")) return
        val nom = nouveauNom.trim()
        if (nom.isEmpty()) return
        val candidats = etat.renommages + (ancien to nom)
        persisterRenommages(candidats)
        etatInterne.update { it.copy(renommages = candidats, chargementPlan = true, erreurPlan = false) }
        viewModelScope.launch {
            when (val resultat = planifierCreation(requeteDeLEtat(renommages = candidats))) {
                is AppResult.Success -> {
                    cheminsPlanPrecedent = resultat.value.fichiers.map { it.chemin }.toSet()
                    etatInterne.update {
                        it.copy(plan = resultat.value, chargementPlan = false, erreurPlan = false)
                    }
                }

                is AppResult.Failure -> {
                    journal.w(TAG) { "renommage rejeté par le plan : $ancien → $nom" }
                    etatInterne.update { it.copy(renommages = etat.renommages) }
                    persisterRenommages(etat.renommages)
                    replanifierApresEchecRenommage()
                }
            }
        }
    }

    /**
     * Rétablit le plan après un renommage rejeté : replanifie avec
     * l'ancienne carte ; l'erreur reste affichée tant que le plan n'est
     * pas revenu (règle « jamais d'impasse »).
     */
    private suspend fun replanifierApresEchecRenommage() {
        when (val retour = planifierCreation(requeteDeLEtat())) {
            is AppResult.Success -> {
                cheminsPlanPrecedent = retour.value.fichiers.map { it.chemin }.toSet()
                etatInterne.update {
                    it.copy(plan = retour.value, chargementPlan = false, erreurPlan = true)
                }
            }

            is AppResult.Failure -> {
                etatInterne.update { it.copy(plan = null, chargementPlan = false, erreurPlan = true) }
            }
        }
    }

    /** Annule la création en cours : rollback domaine puis récapitulatif. */
    private fun annulerCreation() {
        creationJob?.cancel()
    }

    /** Referme l'écran d'échec : retour au récapitulatif (sans relance). */
    private fun retourRecapitulatif() {
        etatInterne.update { it.copy(etatCreation = EtatCreation.Inactif) }
    }

    /**
     * « Ouvrir le projet » (succès) : marque l'ouverture (tri des
     * récents — l'éditeur arrive à l'étape 13) puis referme.
     */
    private fun ouvrirProjetCree() {
        val succes = etatInterne.value.etatCreation as? EtatCreation.Succes ?: return
        viewModelScope.launch {
            marquerOuvert(succes.projet.id, horloge.nowMillis())
            effetsInterne.send(EffetWizard.OuvrirProjetEditeur(succes.projet.id))
        }
    }

    /** « Retour à l'accueil » (succès) : referme en signalant le projet. */
    private fun retourAccueil() {
        val succes = etatInterne.value.etatCreation as? EtatCreation.Succes ?: return
        viewModelScope.launch {
            effetsInterne.send(EffetWizard.ProjetCree(succes.projet.id))
        }
    }

    /**
     * « Créer un autre projet » : remise à zéro complète du wizard —
     * l'emplacement éphémère est relâché (sa nouvelle valeur sera
     * redemandée), le modèle peut être conservé pour enchaîner.
     */
    private fun recommencer(garderModele: Boolean) {
        viewModelScope.launch {
            val ancienOverride = etatInterne.value.emplacementOverride
            val modeleGarde = etatInterne.value.templateId.takeIf { garderModele }
            savedState[CLE_ETAPE] = EtapeId.Modele.cle
            savedState[CLE_TEMPLATE] = modeleGarde?.value
            savedState[CLE_NOM] = ""
            savedState[CLE_DESCRIPTION] = ""
            savedState[CLE_PARAM_CLES] = ArrayList<String>()
            savedState[CLE_PARAM_VALEURS] = ArrayList<String>()
            savedState[CLE_PARAM_MANUELS] = ArrayList<String>()
            savedState[CLE_RENOM_CLES] = ArrayList<String>()
            savedState[CLE_RENOM_VALEURS] = ArrayList<String>()
            savedState[CLE_OVERRIDE_GRANT] = null
            savedState[CLE_OVERRIDE_DOCUMENT] = null
            savedState[CLE_OVERRIDE_LIBELLE] = null
            etatInterne.update {
                it.copy(
                    etape = EtapeId.Modele,
                    parcours = emptyList(),
                    templateId = modeleGarde,
                    evaluation = null,
                    nomProjet = "",
                    description = "",
                    raisonNom = evaluerNom(""),
                    valeursParametres = emptyMap(),
                    modifiesManuellement = emptySet(),
                    renommages = emptyMap(),
                    emplacementOverride = null,
                    erreurEmplacement = null,
                    verificationCible = null,
                    verificationEnCours = false,
                    plan = null,
                    chargementPlan = false,
                    erreurPlan = false,
                    apercuOuvert = false,
                    fichiersAjoutes = emptyList(),
                    fichiersRetires = emptyList(),
                    etatCreation = EtatCreation.Inactif,
                )
            }
            differentielArme = false
            cheminsPlanPrecedent = emptySet()
            relacherEmplacement(ancienOverride)
            reevaluer()
            replanifierVerification()
        }
    }

    /**
     * Demande complète adressée au domaine (sections 12.3 et 12.4).
     *
     * ADR 0155 §4 : seuls les paramètres des **étapes effectives** sont
     * transmis — les valeurs saisies sur une étape disparue restent dans
     * l'état du wizard (elles reviennent si l'étape réapparaît) mais
     * retombent sur leur défaut à la revalidation du domaine.
     */
    private fun requeteDeLEtat(renommages: Map<String, String>? = null): CreateProjectRequest {
        val etat = etatInterne.value
        val idsParcours = etat.parcours.flatMap { it.idsParametres }.toSet()
        return CreateProjectRequest(
            templateId = requireNotNull(etat.templateId) { "Un modèle est requis pour planifier." },
            name = etat.nomProjet.trim(),
            description = etat.description.trim(),
            // Ignoré par le dry-run (ADR 0017 : le plan ne dépend que du
            // modèle, des valeurs et des options) — l'aperçu peut donc
            // planifier avant qu'un emplacement soit résolu.
            parentLocation = etat.emplacement ?: EMPLACEMENT_APERCU,
            parameterValues = etat.valeursParametres.filterKeys { it in idsParcours },
            manuallySetParameters = etat.modifiesManuellement.filter { it in idsParcours }.toSet(),
            cheminsRenommes = renommages ?: etat.renommages,
            options = etat.options,
        )
    }

    /** Année civile de l'horloge injectée (affichée avec l'auteur). */
    private fun anneeCourante(): String = Instant
        .ofEpochMilli(horloge.nowMillis())
        .atZone(ZoneId.systemDefault())
        .year
        .toString()

    // ------------------------------------------------ évaluation moteur

    /**
     * Réévalue le formulaire du modèle sélectionné, puis recalcule le
     * **parcours effectif** (ADR 0155 §3) : une étape de modèle dont la
     * condition cesse d'être vraie disparaît — si c'était l'étape
     * courante, l'utilisateur est ramené à la dernière étape effective
     * (jamais sauté en avant), ses saisies conservées.
     */
    private fun reevaluer() {
        val etat = etatInterne.value
        val id = etat.templateId
        if (id == null) {
            etatInterne.update { it.copy(evaluation = null, parcours = emptyList()) }
            return
        }
        viewModelScope.launch {
            when (
                val resultat =
                    evaluerFormulaire(
                        id,
                        etat.nomProjet,
                        etat.valeursParametres,
                        etat.modifiesManuellement,
                        langueCourante(),
                    )
            ) {
                is AppResult.Success -> {
                    etatInterne.update { courant -> majParcours(courant, resultat.value) }
                    planifierDiffere()
                }

                is AppResult.Failure -> {
                    journal.w(TAG) { "évaluation impossible du modèle ${id.value}" }
                    etatInterne.update { it.copy(evaluation = null, parcours = emptyList()) }
                }
            }
        }
    }

    /**
     * Intègre une évaluation à l'état : parcours recalculé par le domaine,
     * étape courante ramenée en arrière si elle a disparu (ADR 0155 §4 —
     * l'index **ancien** sert de borne : jamais de saut en avant vers une
     * étape jamais visitée).
     */
    private fun majParcours(
        courant: EtatWizard,
        evaluation: jo.codeide.core.model.TemplateFormEvaluation,
    ): EtatWizard {
        val nouveauParcours = calculeParcours(evaluation).etapes
        val existait = courant.parcours.indexOfFirst { it.id == courant.etape.cle }
        val existeEncore = nouveauParcours.any { it.id == courant.etape.cle }
        if (existeEncore || nouveauParcours.isEmpty()) {
            return courant.copy(evaluation = evaluation, parcours = nouveauParcours)
        }
        // ADR 0155 §4 : l'étape qui disparaît ramène à la dernière étape
        // effective **précédente** — jamais un saut en avant vers une étape
        // jamais visitée. Étape restaurée inconnue (existait < 0) : borne à
        // l'index courant du parcours précédent, coercé sur la liste.
        val index =
            if (existait > 0) {
                existait - 1
            } else {
                courant.indexEtape.coerceIn(0, nouveauParcours.lastIndex)
            }
        val cible = nouveauParcours[index.coerceIn(0, nouveauParcours.lastIndex)]
        savedState[CLE_ETAPE] = cible.id
        journal.i(TAG) { "étape « ${courant.etape.cle} » disparue du parcours — retour à « ${cible.id} »" }
        return courant.copy(
            evaluation = evaluation,
            parcours = nouveauParcours,
            etape = EtapeId.deCle(cible.id),
        )
    }

    // ------------------------------------------- vérification cible

    /** Replanifie la vérification asynchrone de la cible (délai). */
    private fun replanifierVerification() {
        verificationJob?.cancel()
        val etat = etatInterne.value
        val emplacement = etat.emplacement
        if (emplacement == null || etat.nomProjet.isBlank() || etat.raisonNom != null) {
            etatInterne.update { it.copy(verificationCible = null, verificationEnCours = false) }
            return
        }
        verificationJob =
            viewModelScope.launch {
                etatInterne.update { it.copy(verificationEnCours = true) }
                delay(DELAI_VERIFICATION_MS)
                val documentUri = etatInterne.value.emplacement?.documentUri ?: emplacement.documentUri
                val resultat = verifierCible(documentUri, etatInterne.value.nomProjet)
                etatInterne.update {
                    if (it.emplacement?.documentUri == documentUri) {
                        it.copy(verificationCible = resultat, verificationEnCours = false)
                    } else {
                        it.copy(verificationEnCours = false)
                    }
                }
            }
    }

    // ------------------------------------------------- SavedStateHandle

    /** Restitue l'état saisi après mort du processus (section 12.2). */
    private fun restaurer() {
        val etape =
            savedState
                .get<String>(CLE_ETAPE)
                ?.let(EtapeId::deCle)
                ?: EtapeId.Modele
        val template = savedState.get<String>(CLE_TEMPLATE)?.let(::TemplateId)
        val nom = savedState.get<String>(CLE_NOM) ?: ""
        val description = savedState.get<String>(CLE_DESCRIPTION) ?: ""
        val cles = savedState.get<ArrayList<String>>(CLE_PARAM_CLES).orEmpty()
        val valeurs = savedState.get<ArrayList<String>>(CLE_PARAM_VALEURS).orEmpty()
        val parametres = cles.zip(valeurs).toMap()
        val manuels = savedState.get<ArrayList<String>>(CLE_PARAM_MANUELS).orEmpty().toSet()
        val clesRenommages = savedState.get<ArrayList<String>>(CLE_RENOM_CLES).orEmpty()
        val valeursRenommages = savedState.get<ArrayList<String>>(CLE_RENOM_VALEURS).orEmpty()
        val renommages = clesRenommages.zip(valeursRenommages).toMap()
        val override = restaurerOverride()
        etatInterne.value =
            EtatWizard(
                etape = etape,
                templateId = template,
                nomProjet = nom,
                description = description,
                raisonNom = evaluerNom(nom.trim()),
                valeursParametres = parametres,
                modifiesManuellement = manuels,
                renommages = renommages,
                emplacementOverride = override,
                emplacement = override,
                options = restaurerOptions(),
            )
    }

    /** Reconstitue l'override éphémère depuis le `SavedStateHandle`. */
    @Suppress("ReturnCount") // Clauses de garde : une par chaîne manquante (règle 16).
    private fun restaurerOverride(): StorageLocation? {
        val grant = savedState.get<String>(CLE_OVERRIDE_GRANT) ?: return null
        val document = savedState.get<String>(CLE_OVERRIDE_DOCUMENT) ?: return null
        val libelle = savedState.get<String>(CLE_OVERRIDE_LIBELLE) ?: return null
        return StorageLocation(grantUri = grant, documentUri = document, displayPath = libelle)
    }

    /** Persiste les valeurs saisies (listes parallèles, ordre stable). */
    private fun persisterValeurs(valeurs: Map<String, String>, manuels: Set<String>) {
        savedState[CLE_PARAM_CLES] = ArrayList(valeurs.keys)
        savedState[CLE_PARAM_VALEURS] = ArrayList(valeurs.values)
        savedState[CLE_PARAM_MANUELS] = ArrayList(manuels)
    }

    /** Persiste la carte des renommages (listes parallèles, ordre stable). */
    private fun persisterRenommages(renommages: Map<String, String>) {
        savedState[CLE_RENOM_CLES] = ArrayList(renommages.keys)
        savedState[CLE_RENOM_VALEURS] = ArrayList(renommages.values)
    }

    private fun persisterOverride(emplacement: StorageLocation?) {
        savedState[CLE_OVERRIDE_GRANT] = emplacement?.grantUri
        savedState[CLE_OVERRIDE_DOCUMENT] = emplacement?.documentUri
        savedState[CLE_OVERRIDE_LIBELLE] = emplacement?.displayPath
    }

    // ------------------------------------------------------- langue

    /** Langue des libellés de modèles depuis l'étiquette BCP 47. */
    private fun langueDesLibelles(languageTag: String): String =
        when (languageTag.ifBlank { Locale.getDefault().language }.lowercase().substringBefore('-')) {
            "fr" -> "fr"
            else -> "en"
        }

    /** Langue courante (avant la première lecture des réglages). */
    private fun langueCourante(): String = langueModeles.ifBlank { langueDesLibelles("") }

    private companion object {
        /** Étiquette des journaux du wizard (identifiants uniquement, règle 15). */
        const val TAG = "Wizard"

        /** Délai de la vérification asynchrone de cible (section 12.3). */
        const val DELAI_VERIFICATION_MS = 400L

        /** Antirebond de l'aperçu en direct (ADR 0155 §6 — ~300 ms). */
        const val DELAI_ANTIREBOND_MS = 300L

        /** Détails de l'échec de pré-vol collision (v0.31.5) — lisible
         * dans les détails copiables de l'écran d'échec. */
        const val PRE_VOL_COLLISION = "pré-vol : un enfant du dossier parent porte déjà ce nom"

        /** Dossier des métadonnées du projet — jamais renommable (ADR 0077). */
        const val DOSSIER_METADATA = ".codeide"

        /**
         * Emplacement factice de l'aperçu : le dry-run ignore l'emplacement
         * parent (ADR 0017 — le plan ne dépend que du modèle, des valeurs
         * et des options), la planification peut donc précéder la
         * résolution du dossier de travail. Les valeurs factices sont
         * valides pour le contrat SAF mais jamais utilisées : le plan
         * n'écrit rien.
         */
        val EMPLACEMENT_APERCU =
            StorageLocation(
                grantUri = "content://apercu/tree/plan",
                documentUri = "content://apercu/document/plan",
                displayPath = "aperçu",
            )

        const val CLE_ETAPE = "wizard.etape"
        const val CLE_TEMPLATE = "wizard.template"
        const val CLE_NOM = "wizard.nom"
        const val CLE_DESCRIPTION = "wizard.description"
        const val CLE_PARAM_CLES = "wizard.parametres.cles"
        const val CLE_PARAM_VALEURS = "wizard.parametres.valeurs"
        const val CLE_PARAM_MANUELS = "wizard.parametres.manuels"
        const val CLE_RENOM_CLES = "wizard.renommages.cles"
        const val CLE_RENOM_VALEURS = "wizard.renommages.valeurs"
        const val CLE_OVERRIDE_GRANT = "wizard.emplacement.grant"
        const val CLE_OVERRIDE_DOCUMENT = "wizard.emplacement.document"
        const val CLE_OVERRIDE_LIBELLE = "wizard.emplacement.libelle"
        const val CLE_OPT_README = "wizard.options.readme"
        const val CLE_OPT_GITIGNORE = "wizard.options.gitignore"
        const val CLE_OPT_EDITORCONFIG = "wizard.options.editorconfig"
        const val CLE_OPT_LICENCE = "wizard.options.licence"
        const val CLE_OPT_LANGUE = "wizard.options.langue"
    }
}
