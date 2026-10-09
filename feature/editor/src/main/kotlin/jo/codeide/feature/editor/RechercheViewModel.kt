package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.OptionsRecherche
import jo.codeide.core.domain.RechercheMoteur
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.ResultatRecherche
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la section Recherche du tiroir (mission Recherche S2-S5,
 * ADR 0094).
 *
 * Recherche en direct avec temporisation (≈ 300 ms), résultats groupés
 * par fichier, annulation immédiate quand la requête change.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class RechercheViewModel
    @Inject
    constructor(
        private val moteur: RechercheMoteur,
        private val observerProjet: ObserveProjectUseCase,
        private val resoudreRepertoire: ResoudreRepertoireProjet,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val projectId: ProjectId? =
            savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID)?.let { ProjectId(it) }

        private val _etat = MutableStateFlow(EtatRecherche())
        val etat: StateFlow<EtatRecherche> = _etat.asStateFlow()

        private var jobRecherche: Job? = null

        /** Met à jour la requête et lance la recherche (temporisation 300 ms). */
        fun requete(nouveau: String) {
            _etat.value = _etat.value.copy(requete = nouveau)
            jobRecherche?.cancel()
            if (nouveau.isBlank()) {
                _etat.value = _etat.value.copy(resultats = emptyList(), chargement = false)
                return
            }
            jobRecherche =
                viewModelScope.launch {
                    delay(DELAI_RECHERCHE_MS)
                    lancerRecherche()
                }
        }

        /** Bascule l'option ignorer la casse. */
        fun basculerCasse() {
            _etat.value = _etat.value.copy(ignorerCasse = !_etat.value.ignorerCasse)
            relancer()
        }

        /** Bascule l'option mot entier. */
        fun basculerMotEntier() {
            _etat.value = _etat.value.copy(motEntier = !_etat.value.motEntier)
            relancer()
        }

        /** Bascule l'option regex. */
        fun basculerRegex() {
            _etat.value = _etat.value.copy(regex = !_etat.value.regex)
            relancer()
        }

        /** Efface la requête et les résultats. */
        fun effacer() {
            requete("")
        }

        private fun relancer() {
            if (_etat.value.requete.isNotBlank()) {
                jobRecherche?.cancel()
                jobRecherche = viewModelScope.launch { lancerRecherche() }
            }
        }

        @Suppress("ReturnCount")
        private suspend fun lancerRecherche() {
            val id = projectId ?: return
            val projet = observerProjet(id).first() ?: return
            val cheminFuse =
                resoudreRepertoire(projet.location.documentUri) ?: run {
                    _etat.value = _etat.value.copy(chargement = false, erreur = "Chemin inaccessible")
                    return
                }
            _etat.value = _etat.value.copy(chargement = true, resultats = emptyList(), erreur = null)
            val options =
                OptionsRecherche(
                    ignorerCasse = _etat.value.ignorerCasse,
                    motEntier = _etat.value.motEntier,
                    regex = _etat.value.regex,
                )
            val tousResultats = mutableListOf<ResultatRecherche>()
            var fichiersBalayes = 0
            var plafondAtteint = false
            moteur.rechercher(cheminFuse, _etat.value.requete, options).collect { lot ->
                tousResultats.addAll(lot.resultats)
                fichiersBalayes = lot.fichiersBalayes
                plafondAtteint = lot.plafondAtteint
                _etat.value =
                    _etat.value.copy(
                        resultats = tousResultats.toList(),
                        fichiersBalayes = fichiersBalayes,
                        plafondAtteint = plafondAtteint,
                        chargement = !lot.termine,
                    )
            }
        }

        private companion object {
            const val DELAI_RECHERCHE_MS = 300L
        }
    }

/**
 * État observable de la section Recherche.
 */
data class EtatRecherche(
    val requete: String = "",
    val resultats: List<ResultatRecherche> = emptyList(),
    val chargement: Boolean = false,
    val ignorerCasse: Boolean = false,
    val motEntier: Boolean = false,
    val regex: Boolean = false,
    val fichiersBalayes: Int = 0,
    val plafondAtteint: Boolean = false,
    val erreur: String? = null,
) {
    /** Nombre de fichiers contenant des résultats. */
    val nbFichiers: Int get() = resultats.map { it.chemin }.toSet().size

    /** Résumé « N résultats dans M fichiers ». */
    val resume: String
        get() =
            if (resultats.isEmpty()) {
                ""
            } else {
                "${resultats.size} résultat" +
                    "${if (resultats.size > 1) "s" else ""} dans $nbFichiers fichier" +
                    "${if (nbFichiers > 1) "s" else ""}"
            }
}
