package jo.codeide.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.TerminalSessionRepository
import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.domain.ToolchainLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Mode d'affichage des sessions dans le tiroir (v0.32.2, ADR 0053).
 *
 * @property Liste cartes de sessions (chemin · heure, badge d'état,
 * boutons d'action) — mode par défaut.
 * @property SplitVertical sessions **empilées** : un panneau de rendu
 * interactif par session, hauteurs égales.
 * @property SplitColonnes sessions **côte à côte** : un panneau par
 * session, largeurs égales.
 * @property PleinEcranDansTiroir une seule session, son panneau remplit
 * tout le tiroir (retour à la liste par le bouton dédié).
 */
sealed interface ModeTerminalTiroir {
    /** Cartes de sessions. */
    data object Liste : ModeTerminalTiroir

    /** Panneaux empilés verticalement. */
    data object SplitVertical : ModeTerminalTiroir

    /** Panneaux côte à côte. */
    data object SplitColonnes : ModeTerminalTiroir

    /** Une session remplit le tiroir. */
    data class PleinEcranDansTiroir(
        val sessionId: String,
    ) : ModeTerminalTiroir
}

/**
 * État de rendu du fragment Terminal du tiroir (v0.32.2).
 *
 * @property sessions liste globale des sessions, dans l'ordre de création
 * (même registre que l'écran plein écran).
 * @property bootstrapInstalle vrai si les outils du terminal sont
 * installés — faux : l'installation passe avant toute session.
 * @property mode mode d'affichage courant.
 */
data class EtatTerminalTiroir(
    val sessions: List<TerminalSessionSummary> = emptyList(),
    val bootstrapInstalle: Boolean = false,
    val mode: ModeTerminalTiroir = ModeTerminalTiroir.Liste,
)

/**
 * Intentions utilisateur du fragment Terminal du tiroir (v0.32.2) : le
 * fragment n'émet que des actions — les commandes qui réclament l'état
 * de l'espace de travail (créer dans le dossier du projet, plein écran,
 * installation) partent vers le [jo.codeide.core.ui.ControleurTerminalTiroir]
 * de l'activité hôte, pas par ici.
 */
sealed interface ActionTerminalTiroir {
    /** Change le mode d'affichage (liste / splits / retour liste). */
    data class ChoisirMode(
        val mode: ModeTerminalTiroir,
    ) : ActionTerminalTiroir
}

/**
 * ViewModel du Terminal du tiroir (v0.32.2, ADR 0053) : sessions du
 * registre global + mode d'affichage. Les créations/fermetures passent
 * par le contrôleur de l'hôte ou l'écran plein écran — ce ViewModel ne
 * fait que décrire ce qu'il faut montrer.
 *
 * Le mode plein écran **dans le tiroir** retombe sur la liste si la
 * session visée disparaît (fermée depuis l'écran plein écran ou
 * ailleurs) : jamais de panneau orphelin.
 */
@HiltViewModel
class TerminalTiroirViewModel
    @Inject
    constructor(
        registre: TerminalSessionRepository,
        localisateur: ToolchainLocator,
    ) : ViewModel() {
        /** Mode courant (cœur local — les sessions viennent du registre). */
        private val modeInterne = MutableStateFlow<ModeTerminalTiroir>(ModeTerminalTiroir.Liste)

        /** État observable du fragment. */
        val etat: StateFlow<EtatTerminalTiroir> =
            combine(
                registre.observeSessions(),
                modeInterne,
            ) { sessions, mode ->
                EtatTerminalTiroir(
                    sessions = sessions,
                    bootstrapInstalle = localisateur.isBootstrapInstalled(),
                    mode = mode.aplatirSiSessionPerdue(sessions),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(DUREE_ABONNEMENT_MS), EtatTerminalTiroir())

        /** Point d'entrée unique du fragment. */
        fun onAction(action: ActionTerminalTiroir) {
            when (action) {
                is ActionTerminalTiroir.ChoisirMode -> modeInterne.value = action.mode
            }
        }

        private companion object {
            /** Fenêtre de ré-abonnement (millisecondes) — même règle que
             * l'écran plein écran : survit aux reconstructions brèves. */
            const val DUREE_ABONNEMENT_MS = 5_000L
        }
    }

/**
 * Un mode plein écran pointant une session disparue retombe sur la
 * liste — les splits restent tels quels (ils suivent la liste).
 */
private fun ModeTerminalTiroir.aplatirSiSessionPerdue(sessions: List<TerminalSessionSummary>): ModeTerminalTiroir {
    if (this !is ModeTerminalTiroir.PleinEcranDansTiroir) return this
    return if (sessions.any { it.id == sessionId }) this else ModeTerminalTiroir.Liste
}
