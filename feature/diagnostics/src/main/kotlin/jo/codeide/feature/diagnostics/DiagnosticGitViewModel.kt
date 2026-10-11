package jo.codeide.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DiagnostiquerGitProjetUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'onglet « Git » de l'écran Diagnostic (v0.90.1, mission
 * « section Git figée » étape A).
 *
 * Le diagnostic se lance dès l'ouverture de l'onglet et se relance à la
 * demande (bouton Relancer) — il s'exécute sur le dispatcher IO du cas
 * d'usage (sous-processus git + lectures système), jamais sur le
 * principal. L'état porte les deux textes prêts à l'emploi : l'affichage
 * (valeurs réelles) et la copie (expurgée — aucune donnée personnelle).
 *
 * @property diagnostiquer cas d'usage du domaine (sélection du projet
 * le plus récent, résolution FUSE, exécution des sondes).
 */
@HiltViewModel
class DiagnosticGitViewModel
    @Inject
    constructor(
        private val diagnostiquer: DiagnostiquerGitProjetUseCase,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatDiagnosticGit())

        /** État observable de l'onglet Diagnostic Git. */
        val etat: StateFlow<EtatDiagnosticGit> = etatInterne.asStateFlow()

        init {
            lancer()
        }

        /** Relance le diagnostic (bouton Relancer). */
        fun relancer() {
            lancer()
        }

        private fun lancer() {
            viewModelScope.launch {
                etatInterne.update { it.copy(chargement = true) }
                val rapport = diagnostiquer()
                etatInterne.update {
                    EtatDiagnosticGit(
                        chargement = false,
                        texteAffiche = FormateurDiagnosticGit.formater(rapport),
                        texteCopie = FormateurDiagnosticGit.formaterExpurge(rapport),
                    )
                }
            }
        }
    }

/**
 * État de l'onglet « Git » de l'écran Diagnostic.
 *
 * @property chargement vrai pendant l'exécution des sondes.
 * @property texteAffiche rapport formaté pour l'affichage (valeurs
 * réelles), `null` avant le premier diagnostic.
 * @property texteCopie rapport formaté pour la copie (expurgé : chemins,
 * courriels, identités et jetons masqués — règle 15).
 */
data class EtatDiagnosticGit(
    val chargement: Boolean = true,
    val texteAffiche: String? = null,
    val texteCopie: String? = null,
)
