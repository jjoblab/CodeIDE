package jo.codeide.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.SetWorkspaceUseCase
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.domain.ValidateWorkspaceUseCase
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.License
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Intentions utilisateur de l'assistant (section 5.3 : `XxxAction`).
 *
 * Le fragment n'émet que des actions ; toute la logique vit ici.
 */
sealed interface ActionOnboarding {
    /** Page « Bienvenue » : commence le parcours. */
    data object Commencer : ActionOnboarding

    /** Avance d'une page (borné : sur la page finale, il finalise l'installation). */
    data object PageSuivante : ActionOnboarding

    /** Recule d'une page (borné : reste sur la bienvenue). */
    data object PagePrecedente : ActionOnboarding

    /** Demande l'ouverture du sélecteur SAF (effet ponctuel). */
    data object DemanderSelectionDossier : ActionOnboarding

    /**
     * Un dossier est revenu du sélecteur SAF.
     *
     * @property uri URI d'arborescence (`…/tree/…`) proposée par le
     * sélecteur, avec autorisation persistable.
     */
    data class DossierChoisi(
        val uri: String,
    ) : ActionOnboarding

    /** « Plus tard » : l'étape dossier est passable (bandeau à l'accueil). */
    data object PasserDossier : ActionOnboarding

    /**
     * Page Terminal : « Installer maintenant » — ouvre l'écran de
     * progression partagé (jamais bloquant pour la suite du parcours).
     */
    data object InstallerTerminal : ActionOnboarding

    /** Page Terminal : « Plus tard » — étape passable, bandeau à l'accueil. */
    data object PasserTerminal : ActionOnboarding

    /** Revérifie si les outils du terminal sont déjà installés (retour d'écran). */
    data object VerifierTerminal : ActionOnboarding

    /**
     * Page Notifications (v0.31.2) : demande l'autorisation de
     * notification — l'hôte déclenche la requête système (effet
     * ponctuel) ou relève l'état réel si la plateforme n'a rien à
     * demander.
     */
    data object DemanderNotifications : ActionOnboarding

    /**
     * Consigne l'état RÉEL de l'autorisation relevé par l'écran (retour
     * de la requête système ou des réglages, relecture au retour sur la
     * page).
     *
     * @property activees notifications autorisées pour l'application.
     */
    data class ConsignerNotifications(
        val activees: Boolean,
    ) : ActionOnboarding

    /** Page Notifications : ouvre les réglages de notification de l'app (repli si refus). */
    data object DemanderReglagesNotifications : ActionOnboarding

    /**
     * Page Notifications : demande l'accès au stockage partagé (v0.31.3,
     * ADR 0047 — opt-in pour le terminal) — l'hôte déclenche la requête
     * runtime (Android < 11) ou le réglage « Tous les fichiers » (11+).
     */
    data object DemanderStockage : ActionOnboarding

    /**
     * Consigne l'état RÉEL de l'accès au stockage partagé relevé par
     * l'écran (retour de requête/réglages, relecture au retour sur la
     * page — source de vérité : `isExternalStorageManager` en 11+,
     * permission WRITE sinon).
     *
     * @property actif stockage partagé accessible à l'application.
     */
    data class ConsignerStockage(
        val actif: Boolean,
    ) : ActionOnboarding

    /** Page Notifications : ouvre les réglages de stockage de l'app (repli si refus). */
    data object DemanderReglagesStockage : ActionOnboarding

    /** Change le thème — persistance et aperçu immédiat. */
    data class ChangerTheme(
        val mode: ThemeMode,
    ) : ActionOnboarding

    /** Active ou désactive les couleurs dynamiques. */
    data class ChangerCouleursDynamiques(
        val activees: Boolean,
    ) : ActionOnboarding

    /**
     * Change la langue.
     *
     * @property tag tag BCP 47 (`"fr"`, `"en"`) ou `""` pour suivre le
     * système.
     */
    data class ChangerLangue(
        val tag: String,
    ) : ActionOnboarding

    /** Frappe dans le champ nom d'auteur (état seul, pas d'écriture disque). */
    data class SaisirNomAuteur(
        val nom: String,
    ) : ActionOnboarding

    /** Change la licence par défaut. */
    data class ChangerLicence(
        val licence: License,
    ) : ActionOnboarding

    /** Termine l'assistant : `isSetupCompleted` et retour à l'accueil. */
    data object Terminer : ActionOnboarding
}

/**
 * Événements ponctuels de l'assistant (section 5.3 : `XxxEffect`).
 */
sealed interface EffetOnboarding {
    /** Ouvre le sélecteur SAF de dossier (`ACTION_OPEN_DOCUMENT_TREE`). */
    data object OuvrirSelecteurDossier : EffetOnboarding

    /** Ouvre l'écran d'installation des outils du terminal (état partagé). */
    data object OuvrirInstallation : EffetOnboarding

    /**
     * Lance la requête système d'autorisation de notification (page
     * Notifications, v0.31.2) — l'hôte l'exécute, puis consigne l'état
     * réel via [ActionOnboarding.ConsignerNotifications].
     */
    data object OuvrirAutorisationNotifications : EffetOnboarding

    /**
     * Ouvre les réglages de notification de l'application (repli quand
     * la requête directe est refusée ou indisponible).
     */
    data object OuvrirReglagesNotifications : EffetOnboarding

    /**
     * Lance la demande d'accès au stockage partagé (page Notifications,
     * v0.31.3, ADR 0047) — requête runtime sous Android 11, réglage
     * « Tous les fichiers » au-delà ; l'hôte consigne ensuite l'état
     * réel via [ActionOnboarding.ConsignerStockage].
     */
    data object OuvrirAutorisationStockage : EffetOnboarding

    /**
     * Ouvre les réglages de stockage de l'application (repli après refus
     * ou réactivation manuelle).
     */
    data object OuvrirReglagesStockage : EffetOnboarding

    /** L'assistant est terminé : retour à l'accueil. */
    data object RetourAccueil : EffetOnboarding
}

/**
 * ViewModel de l'assistant de premier lancement (étape 5, section 11).
 *
 * Périmètre : piloter le pager, valider et **persister** le dossier de
 * travail (permission persistante, test d'écriture créant puis supprimant
 * un fichier témoin), appliquer l'apparence immédiatement (la persistance
 * alimente la collecte de `MainActivity`, qui recrée l'écran), collecter
 * le profil et clore (`isSetupCompleted = true`).
 *
 * Survie : page et champs profil dans le `SavedStateHandle` (rotation et
 * mort du processus) ; apparence et dossier validé déjà persistés dans
 * les paramètres. Les écritures de paramètres par frappe sont exclues
 * (une écriture DataStore par touche serait un goulot).
 *
 * Journalisation (règle 15) : aucun chemin, URI ni nom d'auteur — seuls
 * des événements génériques (« dossier de travail configuré »).
 *
 * Exemptions detekt ciblées (règle 16 du prompt maître, jamais de
 * vérification désactivée pour « faire passer » le build) :
 * - LongParameterList : injection des use cases et ports du domaine —
 *   les regrouper en objet d'options nuirait à la lisibilité Hilt ;
 * - TooManyFunctions : l'assistant couvre cinq pages, chaque action du
 *   UDF a son gestionnaire privé cohésif ; l'éclater par page
 *   casserait la localité de l'état partagé (page, dossier, apparence,
 *   profil).
 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val observerParametres: ObserveSettingsUseCase,
        private val majParametres: UpdateSettingsUseCase,
        private val definirDossier: SetWorkspaceUseCase,
        private val validerDossier: ValidateWorkspaceUseCase,
        private val fichiers: FileSystem,
        private val localisateurOutils: ToolchainLocator,
        private val logger: AppLogger,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val sauvetage = savedStateHandle

        private val etatInterne = MutableStateFlow(etatInitial())
        private val canalEffets = Channel<EffetOnboarding>(Channel.BUFFERED)

        /** État observable de l'assistant (UDF, section 5.3). */
        val etat: StateFlow<EtatOnboarding> = etatInterne.asStateFlow()

        /** Effets ponctuels (navigation, sélecteur SAF). */
        val effets: Flow<EffetOnboarding> = canalEffets.receiveAsFlow()

        init {
            amorcerDepuisParametres()
            // Interrogation synchrone et pure (marqueur de fichier) : aucune
            // coroutine nécessaire, l'état est immédiatement disponible.
            etatInterne.update { it.copy(terminalInstalle = localisateurOutils.isBootstrapInstalled()) }
        }

        /** Point d'entrée unique du fragment (section 5.3 : `onAction`). */
        fun onAction(action: ActionOnboarding) {
            when (action) {
                ActionOnboarding.Commencer -> avancer()
                ActionOnboarding.PageSuivante -> avancer()
                ActionOnboarding.PagePrecedente -> reculer()
                ActionOnboarding.PasserDossier -> avancer()
                ActionOnboarding.PasserTerminal -> avancer()
                ActionOnboarding.Terminer -> terminer()
                else -> onActionTerminal(action)
            }
        }

        /** Routage des étapes interactives (terminal, dossier, saisie). */
        private fun onActionTerminal(action: ActionOnboarding) {
            when (action) {
                ActionOnboarding.DemanderSelectionDossier -> {
                    envoyerEffet(EffetOnboarding.OuvrirSelecteurDossier)
                }

                is ActionOnboarding.DossierChoisi -> {
                    verifierDossier(action.uri)
                }

                ActionOnboarding.InstallerTerminal -> {
                    envoyerEffet(EffetOnboarding.OuvrirInstallation)
                }

                ActionOnboarding.VerifierTerminal -> {
                    verifierTerminal()
                }

                ActionOnboarding.DemanderNotifications -> {
                    envoyerEffet(EffetOnboarding.OuvrirAutorisationNotifications)
                }

                ActionOnboarding.DemanderReglagesNotifications -> {
                    envoyerEffet(EffetOnboarding.OuvrirReglagesNotifications)
                }

                is ActionOnboarding.ConsignerNotifications -> {
                    etatInterne.update { it.copy(notificationsActivees = action.activees) }
                }

                ActionOnboarding.DemanderStockage -> {
                    envoyerEffet(EffetOnboarding.OuvrirAutorisationStockage)
                }

                ActionOnboarding.DemanderReglagesStockage -> {
                    envoyerEffet(EffetOnboarding.OuvrirReglagesStockage)
                }

                is ActionOnboarding.ConsignerStockage -> {
                    etatInterne.update { it.copy(stockagePartageActif = action.actif) }
                }

                else -> {
                    onActionSaisie(action)
                }
            }
        }

        /** Routage de la saisie (apparence et profil, écritures différées). */
        private fun onActionSaisie(action: ActionOnboarding) {
            when (action) {
                is ActionOnboarding.ChangerTheme -> changerTheme(action.mode)
                is ActionOnboarding.ChangerCouleursDynamiques -> changerCouleurs(action.activees)
                is ActionOnboarding.ChangerLangue -> changerLangue(action.tag)
                is ActionOnboarding.SaisirNomAuteur -> saisirNom(action.nom)
                is ActionOnboarding.ChangerLicence -> changerLicence(action.licence)
                else -> Unit // Routage exhaustif par les trois branches.
            }
        }

        /**
         * État initial : d'abord ce que le sauvetage a conservé (mort du
         * processus), sinon les valeurs en cours des paramètres — un
         * dossier déjà validé par une tentative précédente ne se redemande
         * pas, et l'apparence repart des choix réels.
         */
        private fun etatInitial(): EtatOnboarding {
            val page = sauvetage.get<String>(CLE_PAGE)?.let(PageOnboarding::valueOf)
            return EtatOnboarding(
                page = page ?: PageOnboarding.BIENVENUE,
                modeTheme = sauvetage.get<String>(CLE_THEME)?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM,
                couleursDynamiques = sauvetage.get<Boolean>(CLE_DYNAMIQUE) ?: true,
                langue = sauvetage.get<String>(CLE_LANGUE) ?: "",
                nomAuteur = sauvetage.get<String>(CLE_NOM_AUTEUR) ?: "",
                licenceDefaut = sauvetage.get<String>(CLE_LICENCE)?.let(License::fromPersistedName) ?: License.MIT,
            )
        }

        /**
         * Sème l'état depuis les paramètres réels, une seule fois par
         * vie du sauvetage : après une mort de processus, le drapeau
         * [CLE_SEME] est déjà posé et les valeurs conservées gagnent.
         */
        private fun amorcerDepuisParametres() {
            viewModelScope.launch {
                val reglages = observerParametres().first()
                if (sauvetage.get<Boolean>(CLE_SEME) == true) return@launch
                sauvetage[CLE_SEME] = true
                etatInterne.update { courant ->
                    courant.copy(
                        dossier = reglages.workspace?.let(EtatDossier::Configure) ?: courant.dossier,
                        modeTheme = reglages.themeMode,
                        couleursDynamiques = reglages.useDynamicColor,
                        langue = reglages.languageTag,
                        nomAuteur = reglages.authorName,
                        licenceDefaut = reglages.defaultLicense,
                    )
                }
            }
        }

        /** Avance d'une page, borné à la page finale. */
        private fun avancer() {
            // Sur la page finale, le bouton unique de l'hôte s'affiche
            // « Terminer » : avancer n'a plus de sens, c'est la finalisation
            // de l'installation qui est demandée (bug constaté sur appareil
            // réel : le bouton n'aboutissait à rien, `isSetupCompleted`
            // restait faux et l'assistant revenait à chaque lancement).
            if (etatInterne.value.page == PageOnboarding.TERMINE) {
                terminer()
                return
            }
            etatInterne.update { courant ->
                val suivante = courant.page.ordinal + 1
                if (suivante > PageOnboarding.entries.lastIndex) {
                    courant
                } else {
                    val page = PageOnboarding.entries[suivante]
                    sauvetage[CLE_PAGE] = page.name
                    courant.copy(page = page)
                }
            }
        }

        /** Revérifie la présence des outils du terminal (retour d'écran). */
        private fun verifierTerminal() {
            etatInterne.update { it.copy(terminalInstalle = localisateurOutils.isBootstrapInstalled()) }
        }

        /** Recule d'une page, borné à la bienvenue. */
        private fun reculer() {
            etatInterne.update { courant ->
                val precedente = courant.page.ordinal - 1
                if (precedente < 0) {
                    courant
                } else {
                    val page = PageOnboarding.entries[precedente]
                    sauvetage[CLE_PAGE] = page.name
                    courant.copy(page = page)
                }
            }
        }

        /** Change le thème et le persiste — l'aperçu suit la persistance. */
        private fun changerTheme(mode: ThemeMode) {
            sauvetage[CLE_THEME] = mode.name
            etatInterne.update { it.copy(modeTheme = mode) }
            ecrireParametres("thème") { it.copy(themeMode = mode) }
        }

        /** Active ou désactive les couleurs dynamiques et le persiste. */
        private fun changerCouleurs(activees: Boolean) {
            sauvetage[CLE_DYNAMIQUE] = activees
            etatInterne.update { it.copy(couleursDynamiques = activees) }
            ecrireParametres("couleurs dynamiques") { it.copy(useDynamicColor = activees) }
        }

        /** Change la langue et le persiste — recréation et aperçu immédiat. */
        private fun changerLangue(tag: String) {
            sauvetage[CLE_LANGUE] = tag
            etatInterne.update { it.copy(langue = tag) }
            ecrireParametres("langue") { it.copy(languageTag = tag) }
        }

        /** Mémorise la frappe (état et sauvetage, écriture différée). */
        private fun saisirNom(nom: String) {
            sauvetage[CLE_NOM_AUTEUR] = nom
            etatInterne.update { it.copy(nomAuteur = nom) }
        }

        /** Mémorise la licence par défaut (écriture à la fin). */
        private fun changerLicence(licence: License) {
            sauvetage[CLE_LICENCE] = licence.name
            etatInterne.update { it.copy(licenceDefaut = licence) }
        }

        /**
         * Termine l'assistant : profile les réglages retenus, marque
         * l'installation terminée et demande le retour à l'accueil.
         *
         * Garde anti double-appui ([EtatOnboarding.finalisation]) : la
         * finalisation ne se relance pas tant qu'une tentative est en vol ;
         * un échec d'écriture est **signalé à l'écran** (page Terminé) au
         * lieu d'un silence — le bouton redevient actif pour réessayer.
         */
        private fun terminer() {
            val final = etatInterne.value
            if (final.finalisation) return
            etatInterne.update { it.copy(finalisation = true, erreurFinalisation = false) }
            viewModelScope.launch {
                val resultat =
                    majParametres { reglages ->
                        reglages.copy(
                            authorName = final.nomAuteur,
                            defaultLicense = final.licenceDefaut,
                            isSetupCompleted = true,
                        )
                    }
                when (resultat) {
                    is AppResult.Success -> {
                        logger.i(TAG) { "assistant de premier lancement terminé" }
                        canalEffets.send(EffetOnboarding.RetourAccueil)
                    }

                    is AppResult.Failure -> {
                        // Sans écriture, « Terminé » ne vaut pas confirmation :
                        // on libère le bouton et la page signale l'échec.
                        logger.w(TAG) { "échec de la finalisation de l'assistant" }
                        etatInterne.update { it.copy(finalisation = false, erreurFinalisation = true) }
                    }
                }
            }
        }

        /**
         * Valide un dossier revenu du sélecteur SAF — cas d'usage de
         * domaine (`ValidateWorkspaceUseCase`, partagé avec l'écran
         * Paramètres de l'étape 6) : dossiers refusés par Android 11+
         * détectés avant toute prise de permission, permission
         * persistante, test d'écriture (création puis suppression d'un
         * fichier témoin). Tout échec du cas d'usage relâche déjà la
         * permission prise (le système en plafonne le nombre, 512 sur
         * Android 11+, section 5.6).
         *
         * Il reste ici à persister le dossier validé, en relâchant la
         * permission si cette écriture échoue à son tour.
         */
        private fun verifierDossier(uri: String) {
            etatInterne.update { it.copy(dossier = EtatDossier.Verification) }
            viewModelScope.launch {
                when (val resultat = validerDossier(uri)) {
                    is ValidationDossier.Refuse -> {
                        logger.w(TAG) { "dossier de travail refusé par la plateforme" }
                        etatInterne.update { it.copy(dossier = EtatDossier.Refuse(resultat.raison)) }
                    }

                    is ValidationDossier.Erreur -> {
                        logger.w(TAG) { "échec de la validation du dossier de travail" }
                        etatInterne.update { it.copy(dossier = EtatDossier.Erreur(resultat.erreur)) }
                    }

                    is ValidationDossier.Valide -> {
                        enregistrerDossier(resultat.emplacement)
                    }
                }
            }
        }

        /** Persiste le dossier validé comme dossier de travail. */
        private suspend fun enregistrerDossier(dossier: StorageLocation) {
            when (val resultat = definirDossier(dossier)) {
                is AppResult.Failure -> {
                    fichiers.releasePersistablePermission(dossier.grantUri)
                    logger.w(TAG) { "échec de persistance du dossier de travail" }
                    etatInterne.update { it.copy(dossier = EtatDossier.Erreur(resultat.error)) }
                }

                is AppResult.Success -> {
                    logger.i(TAG) { "dossier de travail configuré" }
                    etatInterne.update { it.copy(dossier = EtatDossier.Configure(dossier)) }
                }
            }
        }

        /** Écriture de paramètres avec journalisation du seul échec. */
        private fun ecrireParametres(
            quoi: String,
            transformation: (AppSettings) -> AppSettings,
        ) {
            viewModelScope.launch {
                when (val resultat = majParametres(transformation)) {
                    is AppResult.Success -> {
                        Unit
                    }

                    is AppResult.Failure -> {
                        logger.w(TAG) { "échec de persistance du réglage : $quoi" }
                    }
                }
            }
        }

        private fun envoyerEffet(effet: EffetOnboarding) {
            canalEffets.trySend(effet)
        }

        private companion object {
            const val TAG = "Onboarding"

            /** Clés du SavedStateHandle (page et champs du profil). */
            const val CLE_PAGE = "page"
            const val CLE_THEME = "theme"
            const val CLE_DYNAMIQUE = "couleurs_dynamiques"
            const val CLE_LANGUE = "langue"
            const val CLE_NOM_AUTEUR = "nom_auteur"
            const val CLE_LICENCE = "licence"
            const val CLE_SEME = "seme"
        }
    }
