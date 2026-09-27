package jo.codeide.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.InstantaneTas
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'écran de configuration du tooling (v3) — ouvert par
 * l'engrenage de l'onglet Sortie, scopé au dialogue qui l'héberge.
 *
 * Réglages **persistés à l'instant** (même contrat que les sections des
 * Paramètres, ADR 0059) : chaque bascule/interrupteur écrit le champ dans
 * DataStore dès le geste — l'état observé revient confirmer le rendu, un
 * écran rouvert repart du persisté, jamais d'un tampon local divergent.
 *
 * L'état vivant de l'orchestrateur (connexion, tas) complète le réglage :
 * l'utilisateur voit QUI il configure, pas seulement CE qu'il règle.
 */
@HiltViewModel
class ConfigToolingViewModel
    @Inject
    constructor(
        observerReglages: ObserveSettingsUseCase,
        private val majReglages: UpdateSettingsUseCase,
        tooling: GradleToolingRepository,
        private val journal: AppLogger,
    ) : ViewModel() {
        /** Réglages courants (retour du DataStore — source de vérité,
         *  exposée au rendu idempotent du dialogue). */
        val reglages: StateFlow<AppSettings> =
            observerReglages()
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        /** État vivant de l'orchestrateur, exposé en lecture seule. */
        private val etatVivantInterne = MutableStateFlow(EtatVivantTooling())

        /** État observable de l'écran. */
        val etatVivant: StateFlow<EtatVivantTooling> = etatVivantInterne.asStateFlow()

        init {
            // Connexion + tas de l'orchestrateur : deux flux froids fusionnés
            // dans UN état (le dialogue ne collecte qu'une fois).
            viewModelScope.launch {
                combine(tooling.observeConnectionState(), tooling.observeHeap()) { connexion, tas ->
                    EtatVivantTooling(connexion = connexion, tas = tas)
                }.collect { etatVivantInterne.value = it }
            }
        }

        /** Affichage des tâches pendant le build (v3). */
        fun definirAfficherTaches(afficher: Boolean) {
            viewModelScope.launch { persister { it.copy(toolingAfficherTaches = afficher) } }
        }

        /** Mode hors ligne (`--offline`) des builds. */
        fun definirHorsLigne(horsLigne: Boolean) {
            viewModelScope.launch { persister { it.copy(toolingHorsLigne = horsLigne) } }
        }

        /** Arguments Gradle libres (séparés par des espaces). */
        fun definirArguments(arguments: String) {
            viewModelScope.launch { persister { it.copy(toolingArguments = arguments.trim()) } }
        }

        /** Transformation atomique du réglage, échec journalisé (règle 15 :
         *  identifiants seulement, le contenu des arguments n'y paraît pas). */
        private suspend fun persister(transformation: (AppSettings) -> AppSettings) {
            majReglages(transformation)
                .onFailure { erreur ->
                    journal.w(TAG) { "réglage tooling non persisté (${erreur::class.simpleName})" }
                }
        }

        /** Regroupement de l'état vivant de l'orchestrateur (v3). */
        data class EtatVivantTooling(
            val connexion: EtatConnexion = EtatConnexion.DECONNECTEE,
            val tas: InstantaneTas = InstantaneTas(moUtilises = 0, moMax = 0),
        )

        private companion object {
            const val TAG = "ConfigTooling"
        }
    }
