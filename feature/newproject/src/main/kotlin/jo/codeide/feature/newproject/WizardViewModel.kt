package jo.codeide.feature.newproject

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReleaseCreationLocationUseCase
import jo.codeide.core.domain.ResolveCreationLocationUseCase
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.domain.VerifyCreationTargetUseCase
import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.EvaluerNomProjetUseCase
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
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
 *   validité — le rendu dynamique de la section 12.2 ;
 * - évaluer le nom du projet (raison typée, section 12.3) ;
 * - résoudre l'emplacement effectif (override éphémère ou dossier de
 *   travail des Paramètres) et vérifier la cible **avec délai** (permission,
 *   joignabilité, collision de nom — section 12.3) ;
 * - piloter la machine à étapes (suivant gardé par validité, précédent) ;
 * - à l'abandon confirmé, relâcher la permission éphémère de l'override
 *   si elle ne sert plus à rien ([ReleaseCreationLocationUseCase]).
 *
 * Journalisation (règle 15) : identifiants uniquement — jamais un nom de
 * projet, jamais un chemin.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) :
 * TooManyFunctions — un gestionnaire par action du wizard (section 5.3,
 * UDF) ; LongParameterList — chaque paramètre est un cas d'usage distinct
 * du domaine, les regrouper créerait un objet céleste.
 */
@HiltViewModel
@Suppress("TooManyFunctions", "LongParameterList")
class WizardViewModel
    @Inject
    constructor(
        private val listerModeles: ListTemplatesUseCase,
        private val evaluerFormulaire: EvaluateTemplateFormUseCase,
        private val evaluerNom: EvaluerNomProjetUseCase,
        private val resoudreEmplacement: ResolveCreationLocationUseCase,
        private val relacherEmplacement: ReleaseCreationLocationUseCase,
        private val verifierCible: VerifyCreationTargetUseCase,
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

        init {
            restaurer()
            observerReglages()
            chargerCatalogue()
        }

        /** Applique une action du wizard. */
        fun action(action: ActionWizard) {
            when (action) {
                ActionWizard.ReessayerCatalogue -> chargerCatalogue()
                is ActionWizard.ChoisirModele -> choisirModele(action.id)
                ActionWizard.Suivant -> avancer()
                ActionWizard.Precedent -> reculer()
                is ActionWizard.SaisirNom -> saisirNom(action.valeur)
                is ActionWizard.SaisirDescription -> saisirDescription(action.valeur)
                is ActionWizard.SaisirTexte -> saisirTexte(action.parametreId, action.valeur)
                is ActionWizard.ChoisirValeur -> choisirValeur(action.parametreId, action.valeur)
                is ActionWizard.Resynchroniser -> resynchroniser(action.parametreId)
                is ActionWizard.ChangerEmplacement -> changerEmplacement(action.grantUri)
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

        /** Suit les réglages : emplacement par défaut et langue. */
        private fun observerReglages() {
            viewModelScope.launch {
                observerParametres().collect { reglages ->
                    val langue = langueDesLibelles(reglages.languageTag)
                    val recharger = langue != langueModeles && langueModeles != ""
                    langueModeles = langue
                    etatInterne.update { etat ->
                        val effectif = etat.emplacementOverride ?: reglages.workspace
                        if (effectif != etat.emplacement) etat.copy(emplacement = effectif) else etat
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
            val suivante = ETAPES_WIZARD[etat.indexEtape + 1]
            savedState[CLE_ETAPE] = suivante.id.name
            etatInterne.update { it.copy(etape = suivante.id) }
        }

        /** Revient à l'étape précédente (garde : première étape). */
        private fun reculer() {
            val etat = etatInterne.value
            if (etat.indexEtape <= 0) return
            val precedente = ETAPES_WIZARD[etat.indexEtape - 1]
            savedState[CLE_ETAPE] = precedente.id.name
            etatInterne.update { it.copy(etape = precedente.id) }
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
        private fun saisirTexte(
            parametreId: String,
            valeur: String,
        ) {
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
        private fun choisirValeur(
            parametreId: String,
            valeur: String,
        ) {
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
            viewModelScope.launch {
                relacherEmplacement(etatInterne.value.emplacementOverride)
                effetsInterne.send(EffetWizard.Fermer)
            }
        }

        // ------------------------------------------------ évaluation moteur

        /** Réévalue le formulaire du modèle sélectionné. */
        private fun reevaluer() {
            val etat = etatInterne.value
            val id = etat.templateId
            if (id == null) {
                etatInterne.update { it.copy(evaluation = null) }
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
                        etatInterne.update { it.copy(evaluation = resultat.value) }
                    }

                    is AppResult.Failure -> {
                        journal.w(TAG) { "évaluation impossible du modèle ${id.value}" }
                        etatInterne.update { it.copy(evaluation = null) }
                    }
                }
            }
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
                    ?.let { nom -> EtapeId.entries.firstOrNull { it.name == nom } }
                    ?: EtapeId.MODELE
            val template = savedState.get<String>(CLE_TEMPLATE)?.let(::TemplateId)
            val nom = savedState.get<String>(CLE_NOM) ?: ""
            val description = savedState.get<String>(CLE_DESCRIPTION) ?: ""
            val cles = savedState.get<ArrayList<String>>(CLE_PARAM_CLES).orEmpty()
            val valeurs = savedState.get<ArrayList<String>>(CLE_PARAM_VALEURS).orEmpty()
            val parametres = cles.zip(valeurs).toMap()
            val manuels = savedState.get<ArrayList<String>>(CLE_PARAM_MANUELS).orEmpty().toSet()
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
                    emplacementOverride = override,
                    emplacement = override,
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
        private fun persisterValeurs(
            valeurs: Map<String, String>,
            manuels: Set<String>,
        ) {
            savedState[CLE_PARAM_CLES] = ArrayList(valeurs.keys)
            savedState[CLE_PARAM_VALEURS] = ArrayList(valeurs.values)
            savedState[CLE_PARAM_MANUELS] = ArrayList(manuels)
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

            const val CLE_ETAPE = "wizard.etape"
            const val CLE_TEMPLATE = "wizard.template"
            const val CLE_NOM = "wizard.nom"
            const val CLE_DESCRIPTION = "wizard.description"
            const val CLE_PARAM_CLES = "wizard.parametres.cles"
            const val CLE_PARAM_VALEURS = "wizard.parametres.valeurs"
            const val CLE_PARAM_MANUELS = "wizard.parametres.manuels"
            const val CLE_OVERRIDE_GRANT = "wizard.emplacement.grant"
            const val CLE_OVERRIDE_DOCUMENT = "wizard.emplacement.document"
            const val CLE_OVERRIDE_LIBELLE = "wizard.emplacement.libelle"
        }
    }
