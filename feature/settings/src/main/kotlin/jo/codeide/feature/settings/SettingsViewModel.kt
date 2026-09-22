package jo.codeide.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ChangeWorkspaceUseCase
import jo.codeide.core.domain.ClearWorkspaceUseCase
import jo.codeide.core.domain.EffacementDossier
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ResetPreferencesUseCase
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.domain.ValidationDossier
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.License
import jo.codeide.core.model.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Intentions utilisateur de l'écran Paramètres (section 5.3 :
 * `XxxAction`).
 *
 * Le fragment n'émet que des actions ; toute la logique vit ici.
 */
sealed interface ActionParametres {
    /** Change le thème — persistance immédiate, effet immédiat. */
    data class ChangerTheme(
        val mode: ThemeMode,
    ) : ActionParametres

    /** Active ou désactive les couleurs dynamiques. */
    data class ChangerCouleursDynamiques(
        val activees: Boolean,
    ) : ActionParametres

    /**
     * Change la langue (tag BCP 47, `""` pour suivre le système).
     */
    data class ChangerLangue(
        val tag: String,
    ) : ActionParametres

    /**
     * Valide la saisie du nom d'auteur (perte de focus du champ) —
     * une écriture DataStore par frappe serait un goulot.
     */
    data class ValiderNomAuteur(
        val nom: String,
    ) : ActionParametres

    /** Change la licence par défaut proposée au wizard. */
    data class ChangerLicence(
        val licence: License,
    ) : ActionParametres

    /** Demande l'ouverture du sélecteur SAF (effet ponctuel). */
    data object DemanderChangementDossier : ActionParametres

    /** Un dossier est revenu du sélecteur SAF. */
    data class DossierChoisi(
        val uri: String,
    ) : ActionParametres

    /** Efface le dossier de travail du réglage. */
    data object EffacerDossier : ActionParametres

    /** Réinitialise les préférences (déjà confirmé par la boîte). */
    data object ReinitialiserPreferences : ActionParametres

    /** Relance l'assistant de premier lancement. */
    data object RelancerAssistant : ActionParametres
}

/** Effets ponctuels de l'écran Paramètres (section 5.3). */
sealed interface EffetParametres {
    /** Ouvre le sélecteur SAF du dossier de travail. */
    data object OuvrirSelecteurDossier : EffetParametres

    /** Ouvre l'assistant de premier lancement. */
    data object OuvrirAssistant : EffetParametres
}

/**
 * ViewModel de l'écran Paramètres (étape 6) : écran **personnalisé
 * Material 3** (pas de `PreferenceFragmentCompat`), piloté par DataStore
 * au travers des use cases du domaine.
 *
 * Chaque réglage se **persiste à l'instant** : l'effet immédiat vient de
 * la collecte des paramètres dans `MainActivity` (recréation d'écran) et
 * de la réémission de [etat] — jamais d'un état UI divergent. Les
 * opérations du dossier de travail suivent la règle de l'étape 6 :
 * changer ou effacer le dossier **ne libère l'ancienne permission que si
 * aucun projet n'en dépend** (use cases du domaine, testés).
 *
 * La réinitialisation remet les préférences par défaut sans toucher au
 * drapeau d'installation ni au registre des projets (ADR 0014).
 *
 * Journalisation (règle 15) : événements génériques, jamais de chemins,
 * d'URI ni de nom d'auteur.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) :
 * LongParameterList — injection des use cases du domaine et des
 * informations de build ; les regrouper en objet d'options nuirait à
 * la lisibilité Hilt.
 */
@Suppress("LongParameterList")
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        observerParametres: ObserveSettingsUseCase,
        private val majParametres: UpdateSettingsUseCase,
        private val changerDossier: ChangeWorkspaceUseCase,
        private val effacerDossier: ClearWorkspaceUseCase,
        private val reinitialiserPreferences: ResetPreferencesUseCase,
        infosBuild: CrashAppInfo,
        private val logger: AppLogger,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatParametres(infosBuild = infosBuild))
        private val canalEffets = Channel<EffetParametres>(Channel.BUFFERED)

        /** État observable de l'écran (UDF, section 5.3). */
        val etat: StateFlow<EtatParametres> = etatInterne.asStateFlow()

        /** Effets ponctuels (sélecteur SAF, assistant). */
        val effets: Flow<EffetParametres> = canalEffets.receiveAsFlow()

        init {
            observerParametres()
                .onEach { reglages -> etatInterne.update { it.copy(reglage = reglages) } }
                .launchIn(viewModelScope)
        }

        /** Point d'entrée unique du fragment (section 5.3 : `onAction`). */
        fun onAction(action: ActionParametres) {
            when (action) {
                is ActionParametres.ChangerTheme -> {
                    ecrireReglage("thème") { it.copy(themeMode = action.mode) }
                }

                is ActionParametres.ChangerCouleursDynamiques -> {
                    ecrireReglage("couleurs dynamiques") { it.copy(useDynamicColor = action.activees) }
                }

                is ActionParametres.ChangerLangue -> {
                    ecrireReglage("langue") { it.copy(languageTag = action.tag) }
                }

                is ActionParametres.ValiderNomAuteur -> {
                    ecrireReglage("nom d'auteur") { it.copy(authorName = action.nom.trim()) }
                }

                is ActionParametres.ChangerLicence -> {
                    ecrireReglage("licence par défaut") { it.copy(defaultLicense = action.licence) }
                }

                ActionParametres.DemanderChangementDossier -> {
                    envoyerEffet(EffetParametres.OuvrirSelecteurDossier)
                }

                is ActionParametres.DossierChoisi -> {
                    changerLeDossier(action.uri)
                }

                ActionParametres.EffacerDossier -> {
                    effacerLeDossier()
                }

                ActionParametres.ReinitialiserPreferences -> {
                    reinitialiser()
                }

                ActionParametres.RelancerAssistant -> {
                    relancerAssistant()
                }
            }
        }

        /** Change le dossier de travail (validation + règle de l'ancienne permission). */
        private fun changerLeDossier(uri: String) {
            etatInterne.update { it.copy(verificationDossier = true, retourDossier = RetourDossier.Aucun) }
            viewModelScope.launch {
                when (val resultat = changerDossier(uri)) {
                    is ValidationDossier.Valide -> {
                        logger.i(TAG) { "dossier de travail changé" }
                        etatInterne.update { it.copy(retourDossier = RetourDossier.Change) }
                    }

                    is ValidationDossier.Refuse -> {
                        logger.w(TAG) { "nouveau dossier de travail refusé par la plateforme" }
                        etatInterne.update { it.copy(retourDossier = RetourDossier.Refuse) }
                    }

                    is ValidationDossier.Erreur -> {
                        logger.w(TAG) { "échec du changement de dossier de travail" }
                        etatInterne.update { it.copy(retourDossier = RetourDossier.Erreur) }
                    }
                }
                etatInterne.update { it.copy(verificationDossier = false) }
            }
        }

        /** Efface le dossier de travail (règle de l'ancienne permission). */
        private fun effacerLeDossier() {
            etatInterne.update { it.copy(verificationDossier = true, retourDossier = RetourDossier.Aucun) }
            viewModelScope.launch {
                when (val resultat = effacerDossier()) {
                    is EffacementDossier.Efface -> {
                        logger.i(TAG) { "dossier de travail effacé" }
                        etatInterne.update {
                            it.copy(
                                // UI : signaler quand la permission est conservée
                                // (des projets vivent encore dans l'arbre).
                                retourDossier = RetourDossier.Efface(permissionGardee = !resultat.permissionLiberee),
                            )
                        }
                    }

                    is EffacementDossier.Erreur -> {
                        logger.w(TAG) { "échec de l'effacement du dossier de travail" }
                        etatInterne.update { it.copy(retourDossier = RetourDossier.Erreur) }
                    }
                }
                etatInterne.update { it.copy(verificationDossier = false) }
            }
        }

        /** Remet les préférences à leurs valeurs par défaut (ADR 0014). */
        private fun reinitialiser() {
            viewModelScope.launch {
                when (reinitialiserPreferences()) {
                    is AppResult.Success -> {
                        logger.i(TAG) { "préférences réinitialisées" }
                        etatInterne.update { it.copy(retourDossier = RetourDossier.Aucun) }
                    }

                    is AppResult.Failure -> {
                        logger.w(TAG) { "échec de la réinitialisation des préférences" }
                    }
                }
            }
        }

        /** Ouvre l'assistant : le drapeau d'installation repasse à faux. */
        private fun relancerAssistant() {
            viewModelScope.launch {
                when (majParametres { reglages: AppSettings -> reglages.copy(isSetupCompleted = false) }) {
                    is AppResult.Success -> {
                        logger.i(TAG) { "assistant de premier lancement relancé" }
                        canalEffets.send(EffetParametres.OuvrirAssistant)
                    }

                    is AppResult.Failure -> {
                        logger.w(TAG) { "échec du relancement de l'assistant" }
                    }
                }
            }
        }

        /** Écriture de réglage avec journalisation du seul échec. */
        private fun ecrireReglage(
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

        private fun envoyerEffet(effet: EffetParametres) {
            canalEffets.trySend(effet)
        }

        private companion object {
            const val TAG = "Parametres"
        }
    }
