package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DependanceDeclaree
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.MavenVersionesDisponibles
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ParseurDependances
import jo.codeide.core.domain.ResoudreFichierRelatifUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.ScriptDeBuild
import jo.codeide.core.domain.TypeDependance
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * P3 : parse les dépendances déclarées depuis les scripts.
 *
 * P6 : interroge Maven (`MavenVersionesDisponibles`, ADR 0097) pour
 * exposer les versions disponibles de chaque dépendance — l'onglet
 * « Mises à jour » compare ces versions à celle déclarée.
 */
@HiltViewModel
// Une fonction par onglet du tiroir (règle 16) ; chaque paramètre du constructeur
// est un cas d'usage distinct du domaine (même exemption que HomeViewModel).
@Suppress("TooManyFunctions", "LongParameterList")
class ProjetViewModel
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val observerProjet: ObserveProjectUseCase,
        private val resoudreRepertoire: ResoudreRepertoireProjet,
        private val parseurDependances: ParseurDependances,
        private val mavenVersiones: MavenVersionesDisponibles,
        private val resoudreFichierRelatif: ResoudreFichierRelatifUseCase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val projectId: ProjectId? =
            savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID)?.let { ProjectId(it) }

        private val _etat = MutableStateFlow(EtatProjet())
        val etat: StateFlow<EtatProjet> = _etat.asStateFlow()

        private val _effets = MutableSharedFlow<EffetProjet>(extraBufferCapacity = 4)
        val effets: SharedFlow<EffetProjet> = _effets.asSharedFlow()

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

        /**
         * P6 : interroge Maven pour les versions disponibles de chaque
         * dépendance bibliothèque (les modules frères et jars locaux
         * sont ignorés — Maven ne les connaît pas).
         */
        fun chargerMisesAJour() {
            val deps = _etat.value.dependances.filter { it.type == TypeDependance.BIBLIOTHEQUE }
            if (deps.isEmpty()) {
                _etat.value = _etat.value.copy(misesAJour = EtatMisesAJour(termine = true))
                return
            }
            viewModelScope.launch {
                _etat.value = _etat.value.copy(misesAJour = EtatMisesAJour(chargement = true))
                val entrees = mutableListOf<EntreeMiseAJour>()
                deps.forEach { dep ->
                    val versions =
                        when (
                            val r =
                                mavenVersiones.versions(
                                    group = dep.groupe,
                                    name = dep.nom,
                                )
                        ) {
                            is AppResult.Success -> r.value
                            is AppResult.Failure -> emptyList()
                        }
                    val derniere = versions.lastOrNull()
                    val miseAJour = derniere != null && derniere != dep.version
                    entrees +=
                        EntreeMiseAJour(
                            coordonnes = dep.coordonnes,
                            versionCourante = dep.version,
                            versionDerniere = derniere ?: "",
                            miseAJourDisponible = miseAJour,
                            scriptOrigine = dep.scriptOrigine,
                        )
                }
                _etat.value =
                    _etat.value.copy(
                        misesAJour =
                            EtatMisesAJour(
                                termine = true,
                                entrees = entrees.sortedByDescending { it.miseAJourDisponible },
                            ),
                    )
            }
        }

        /**
         * P6+ : ouvre un script dans l'éditeur — v0.80.4 : l'URI de
         * document est résolue ICI (segment par segment depuis la
         * racine du projet, [ResoudreFichierRelatifUseCase]) et voyage
         * dans l'effet ; l'activité hôte n'a plus qu'à ouvrir l'onglet.
         * Un chemin introuvable émet une URI `null` : l'UI garde son
         * repli d'information, honnête.
         */
        fun ouvrirScript(script: ScriptDeBuild) {
            viewModelScope.launch {
                val uriRacine = uriDocumentProjet()
                val uri =
                    uriRacine?.let { racine ->
                        resoudreFichierRelatif(racine, script.cheminRelatif)
                    }
                _effets.emit(EffetProjet.OuvrirScript(uri, script.cheminRelatif))
            }
        }

        /** URI de document de la racine du projet courant (ou null). */
        private suspend fun uriDocumentProjet(): String? {
            val id = projectId ?: return null
            return observerProjet(id).first()?.location?.documentUri
        }
    }

/** État immuable de la section Projet (P2-P6). */
data class EtatProjet(
    val chargement: Boolean = false,
    val erreur: Boolean = false,
    val scripts: List<ScriptDeBuild> = emptyList(),
    /** P3 : dépendances déclarées, parsées depuis [scripts]. */
    val dependances: List<DependanceDeclaree> = emptyList(),
    /** P6 : état de l'onglet « Mises à jour ». */
    val misesAJour: EtatMisesAJour = EtatMisesAJour(),
    val ongletCourant: OngletProjet = OngletProjet.SCRIPTS,
)

/** État de l'onglet « Mises à jour » (P6). */
data class EtatMisesAJour(
    val chargement: Boolean = false,
    val termine: Boolean = false,
    val entrees: List<EntreeMiseAJour> = emptyList(),
)

/** Une entrée de l'onglet « Mises à jour » (P6). */
data class EntreeMiseAJour(
    val coordonnes: String,
    val versionCourante: String,
    val versionDerniere: String,
    val miseAJourDisponible: Boolean,
    val scriptOrigine: String,
)

/** Onglets du tiroir Projet (P2-P6). */
enum class OngletProjet {
    /** P2 : scripts de build lus via le serveur de tooling. */
    SCRIPTS,

    /** P3 : dépendances déclarées parsées depuis les scripts. */
    DEPENDANCES,

    /** P6 : mises à jour disponibles depuis Maven. */
    MISES_A_JOUR,

    /** P5 (à venir) : variantes de build (debug/release, flavors). */
    VARIANTES,

    /** P5 : tâches Gradle — délègue à FeuilleTachesFragment. */
    TACHES,
}

/** Effets de la section Projet (P6+). */
sealed interface EffetProjet {
    /**
     * P6+ : ouvrir un script de build dans l'éditeur.
     *
     * @property uri URI de document SAF du script (résolue depuis la
     * racine du projet) — `null` si introuvable (repli d'information).
     * @property cheminRelatif chemin relatif du script, pour l'affichage.
     */
    data class OuvrirScript(
        val uri: String?,
        val cheminRelatif: String,
    ) : EffetProjet
}
