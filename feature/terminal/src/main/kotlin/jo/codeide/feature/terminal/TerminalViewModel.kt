package jo.codeide.feature.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.TerminalSessionRepository
import jo.codeide.core.domain.TerminalSessionSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel de l'écran plein écran du terminal (Terminal T5, prompt
 * Terminal-1, section 5).
 *
 * Ne touche **jamais** aux objets Termux : la liste globale vient du
 * [TerminalSessionRepository] (métadonnées pures), le rebranchement du
 * rendu est le travail de l'activité via `TerminalRuntime`. Le
 * répertoire de travail suggéré transite par l'intent
 * ([ClesTerminal.EXTRA_REPERTOIRE]) et le `SavedStateHandle` — il
 * survit à la rotation.
 *
 * Fermeture d'une session (section 5) : heuristique simple « le shell
 * semble-t-il au prompt ? » — la fin de l'aperçu de sortie se termine
 * par un indicateur de prompt courant (`$`, `#`, `%`, `>`). Une session
 * vivante sans indicateur est présumée en cours d'exécution → effet de
 * confirmation ; une session terminée se ferme sans confirmation.
 */
@HiltViewModel
class TerminalViewModel
    @Inject
    constructor(
        private val registre: TerminalSessionRepository,
        private val environnement: ProcessEnvironmentProvider,
        observeReglages: ObserveSettingsUseCase,
        savedState: SavedStateHandle,
        private val journal: AppLogger,
    ) : ViewModel() {
        /** Répertoire suggéré par le point d'entrée (T6 : accueil/tiroir). */
        private val repertoireSuggere: String? = savedState[CLE_REPERTOIRE_SUGGERE]

        private val _effets = Channel<EffetTerminal>(Channel.BUFFERED)

        /** Effets ponctuels (dialogues de confirmation de fermeture). */
        val effets = _effets.receiveAsFlow()

        val uiState: StateFlow<EtatTerminal> =
            combine(
                registre.observeSessions(),
                registre.observeActiveSessionId(),
                observeReglages(),
            ) { sessions, idActive, reglages ->
                EtatTerminal(
                    sessions = sessions,
                    idSessionActive = idActive,
                    taillePolice = reglages.taillePoliceTerminal,
                    styleCurseur = reglages.styleCurseurTerminal,
                    copieSelectionAuto = reglages.copieSelectionAuto,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(DUREE_ABONNEMENT_MS), EtatTerminal())

        /** Point d'entrée unique de l'écran (section 5.3 : `onAction`). */
        fun onAction(action: ActionTerminal) {
            when (action) {
                is ActionTerminal.NouvelleSession -> creer(repertoireParDefaut())
                is ActionTerminal.OuvrirSession -> registre.setActiveSession(action.sessionId)
                is ActionTerminal.RenommerSession -> renommer(action.sessionId, action.libelle)
                is ActionTerminal.FermerSession -> demanderFermeture(action.sessionId)
                is ActionTerminal.ConfirmerFermeture -> fermer(action.sessionId)
                is ActionTerminal.DupliquerSession -> dupliquer(action.sessionId)
            }
        }

        /** Répertoire par défaut d'une nouvelle session : suggestion du
         * point d'entrée, sinon le `HOME` de l'environnement canonique. */
        private fun repertoireParDefaut(): File {
            val suggere = repertoireSuggere?.takeIf { it.isNotBlank() }
            if (suggere != null) return File(suggere)
            val home = environnement.baseEnvironment()["HOME"]
            return if (home.isNullOrBlank()) File(HOME_PAR_DEFAUT) else File(home)
        }

        private fun creer(repertoire: File) {
            viewModelScope.launch {
                registre.createSession(repertoire)
                journal.i(TAG) { "session créée depuis l'écran du terminal" }
            }
        }

        private fun dupliquer(sessionId: String) {
            val source = uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
            creer(File(source.workingDirectoryPath))
        }

        private fun renommer(
            sessionId: String,
            libelle: String,
        ) {
            val libelleNettoye = libelle.trim().takeIf { it.isNotEmpty() } ?: return
            viewModelScope.launch { registre.renameSession(sessionId, libelleNettoye) }
        }

        private fun demanderFermeture(sessionId: String) {
            val session = uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
            if (commandeSembleEnCours(session)) {
                val effet = EffetTerminal.DemanderConfirmationFermeture(sessionId, session.label)
                viewModelScope.launch { _effets.send(effet) }
            } else {
                fermer(sessionId)
            }
        }

        private fun fermer(sessionId: String) {
            viewModelScope.launch { registre.closeSession(sessionId) }
        }

        private companion object {
            const val TAG = "EcranTerminal"

            /** Fenêtre de ré-abonnement de l'état (millisecondes) — survit
             * aux reconstructions brèves (rotation) sans re-collecte. */
            const val DUREE_ABONNEMENT_MS = 5_000L

            /** Clé SavedStateHandle portant l'extra d'intent. */
            const val CLE_REPERTOIRE_SUGGERE = ClesTerminal.EXTRA_REPERTOIRE

            /** Repli si l'environnement n'expose pas de HOME (jamais vu en
             * pratique : EnvironnementProcessus le fixe toujours) — racine
             * du système, jamais un chemin `/data` codé en dur (lint). */
            const val HOME_PAR_DEFAUT = "/"
        }
    }

/**
 * Heuristique « une commande semble en cours » (section 5) : session
 * vivante **et** dernier affichage sans indicateur de prompt en fin.
 *
 * Interne au module — testé unitairement.
 */
internal fun commandeSembleEnCours(session: TerminalSessionSummary): Boolean {
    if (!session.isAlive) return false
    val fin = session.lastOutputPreview.trimEnd().takeLast(2)
    return fin.none { it in INDICATEURS_PROMPT }
}

private val INDICATEURS_PROMPT = charArrayOf('$', '#', '%', '>')
