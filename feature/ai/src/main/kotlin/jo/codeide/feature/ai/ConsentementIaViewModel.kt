package jo.codeide.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ReglagesIaRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Actions de l'écran de consentement (UDF). */
public sealed interface ActionConsentementIa {
    /** L'utilisateur active l'assistant : consentement + date horodatés. */
    public data object Accepter : ActionConsentementIa

    /** « Pas maintenant » : aucun état persisté, simple retour. */
    public data object Refuser : ActionConsentementIa
}

/** Effets de l'écran de consentement. */
public sealed interface EffetConsentementIa {
    /** Ferme l'écran (dans les deux cas — Activer ou Pas maintenant). */
    public data object Fermer : EffetConsentementIa
}

/**
 * ViewModel du consentement IA (mission IA, lot IA-2, ADR 0121 point 3).
 * Activer positionne `assistantActive` + `consentementDonne` + date —
 * c'est le SEUL chemin d'activation de l'assistant : la garde du routeur
 * ([jo.codeide.core.ai.FournisseurIaActif]) refuse toute requête sans
 * ces deux drapeaux.
 */
@HiltViewModel
public class ConsentementIaViewModel
    @Inject
    constructor(
        private val reglages: ReglagesIaRepository,
    ) : ViewModel() {
        private val effetsInternes = Channel<EffetConsentementIa>(Channel.BUFFERED)

        /** Effets à collecter par le fragment. */
        public val effets: Flow<EffetConsentementIa> = effetsInternes.receiveAsFlow()

        /** Point d'entrée unique des actions (UDF). */
        public fun onAction(action: ActionConsentementIa) {
            when (action) {
                ActionConsentementIa.Accepter -> accepter()
                ActionConsentementIa.Refuser -> emettre(EffetConsentementIa.Fermer)
            }
        }

        private fun accepter() {
            viewModelScope.launch {
                reglages.update {
                    it.copy(
                        assistantActive = true,
                        consentementDonne = true,
                        consentementDateEpochMs = System.currentTimeMillis(),
                    )
                }
                emettre(EffetConsentementIa.Fermer)
            }
        }

        private fun emettre(effet: EffetConsentementIa) {
            viewModelScope.launch { effetsInternes.send(effet) }
        }
    }
