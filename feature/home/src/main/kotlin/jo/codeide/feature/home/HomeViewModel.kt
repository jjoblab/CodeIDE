package jo.codeide.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ObserveSettingsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * État observable de l'accueil (section 5.3) — étape 5 : le bandeau du
 * dossier de travail.
 *
 * @property montrerBandeau l'utilisateur a terminé l'assistant **sans**
 * dossier de travail : l'accueil affiche le bandeau « Configurer le
 * dossier de travail » (étape passable de l'onboarding). Tant que
 * l'assistant n'est pas terminé, le bandeau est inutile — c'est lui
 * qui demande le dossier.
 * @property libelleDossier libellé lisible du dossier de travail, pour
 * l'affichage (null si non configuré).
 */
data class EtatAccueil(
    val montrerBandeau: Boolean = false,
    val libelleDossier: String? = null,
)

/**
 * ViewModel de l'accueil (étape 5) : expose l'état du bandeau du dossier
 * de travail, dérivé des paramètres applicatifs. La liste des projets
 * arrive à l'étape 7 (tri, recherche, actions) et enrichira ce même état.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        observerParametres: ObserveSettingsUseCase,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatAccueil())

        /** État observable de l'accueil (UDF, section 5.3). */
        val etat: StateFlow<EtatAccueil> = etatInterne.asStateFlow()

        init {
            viewModelScope.launch {
                observerParametres().collect { reglages ->
                    etatInterne.value =
                        EtatAccueil(
                            montrerBandeau = reglages.isSetupCompleted && reglages.workspace == null,
                            libelleDossier = reglages.workspace?.displayPath,
                        )
                }
            }
        }
    }
