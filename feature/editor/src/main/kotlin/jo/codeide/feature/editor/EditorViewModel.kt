package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel de l'espace de travail (étape 13 — fondations) : charge le
 * projet dont l'identifiant est arrivé par l'intention (transmis par le
 * [SavedStateHandle] — survit à la rotation et à la mort du processus) et
 * le suit au registre : renommage, relocalisation ou suppression depuis
 * l'accueil se répercutent sans rechargement.
 *
 * Aucune logique d'édition ici tant que les étapes 14 à 16 n'ont pas
 * branché l'explorateur, les onglets et le panneau inférieur.
 */
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        observerProjet: ObserveProjectUseCase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatEditor())

        /** État observable de l'espace de travail. */
        val etat: StateFlow<EtatEditor> = etatInterne.asStateFlow()

        init {
            val identifiant = savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID).orEmpty()
            observerProjet(ProjectId(identifiant))
                .onEach { projet ->
                    etatInterne.update {
                        it.copy(projet = projet, chargement = false)
                    }
                }.launchIn(viewModelScope)
        }
    }
