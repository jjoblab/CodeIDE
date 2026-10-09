package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DependanceDeclaree
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ParseurDependances
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.ScriptDeBuild
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la section « Projet » du tiroir (mission Projet P2-P6,
 * ADR 0095).
 *
 * P2 : charge les scripts de build du projet via le serveur de tooling
 * (port `GradleToolingRepository.scriptsBuild`). Le `projectDir` passé
 * au port est le chemin FUSE réel, résolu par `ResoudreRepertoireProjet`
 * (ADR 0038).
 *
 * P3-P5 (à venir) : Dépendances résolues, Variantes, Tâches. L'onglet
 * Tâches délègue à `FeuilleTachesFragment` (réutilisé, ADR 0095 §5).
 */
@HiltViewModel
class ProjetViewModel
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val observerProjet: ObserveProjectUseCase,
        private val resoudreRepertoire: ResoudreRepertoireProjet,
        private val parseurDependances: ParseurDependances,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val projectId: ProjectId? =
            savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID)?.let { ProjectId(it) }

        private val _etat = MutableStateFlow(EtatProjet())
        val etat: StateFlow<EtatProjet> = _etat.asStateFlow()

        /** Charge les scripts de build du projet courant. */
        fun chargerScripts() {
            val id = projectId ?: return
            viewModelScope.launch {
                _etat.value = _etat.value.copy(chargement = true, erreur = false)
                val projet =
                    observerProjet(id).first() ?: run {
                        _etat.value = _etat.value.copy(chargement = false, erreur = true)
                        return@launch
                    }
                val cheminFuse =
                    resoudreRepertoire(projet.location.documentUri) ?: run {
                        _etat.value = _etat.value.copy(chargement = false, erreur = true)
                        return@launch
                    }
                when (val resultat = tooling.scriptsBuild(java.io.File(cheminFuse))) {
                    is AppResult.Success -> {
                        // P3 : parse les dépendances déclarées depuis les
                        // scripts lus (regex sur les déclarations
                        // implementation/api/etc.).
                        val dependances = parseurDependances.parser(resultat.value)
                        _etat.value =
                            _etat.value.copy(
                                chargement = false,
                                erreur = false,
                                scripts = resultat.value,
                                dependances = dependances,
                            )
                    }

                    is AppResult.Failure -> {
                        _etat.value = _etat.value.copy(chargement = false, erreur = true)
                    }
                }
            }
        }
    }

/** État immuable de la section Projet (P2-P3). */
data class EtatProjet(
    val chargement: Boolean = false,
    val erreur: Boolean = false,
    val scripts: List<ScriptDeBuild> = emptyList(),
    /** P3 : dépendances déclarées, parsées depuis [scripts]. */
    val dependances: List<DependanceDeclaree> = emptyList(),
    val ongletCourant: OngletProjet = OngletProjet.SCRIPTS,
)

/** Onglets du tiroir Projet (P2-P5). */
enum class OngletProjet {
    /** P2 : scripts de build lus via le serveur de tooling. */
    SCRIPTS,

    /** P3 (à venir) : dépendances résolues avec arbre et transitives. */
    DEPENDANCES,

    /** P4 (à venir) : variantes de build (debug/release, flavors). */
    VARIANTES,

    /** P5 : tâches Gradle — délègue à FeuilleTachesFragment. */
    TACHES,
}
