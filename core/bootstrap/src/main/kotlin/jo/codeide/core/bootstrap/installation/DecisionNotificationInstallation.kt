package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress

/**
 * Décision du cycle de vie du service de premier plan (logique
 * **pure**, testée — pattern `DecisionServiceTerminal`, ADR 0087 § 8) :
 * tant qu'une phase s'exécute, la notification reste (phase courante,
 * éventuellement sa progression) ; dès qu'aucune phase ne s'exécute, le
 * service s'arrête — l'état terminal (réussite comme échec) n'a pas
 * vocation à occuper le tiroir de notifications.
 */
internal object DecisionNotificationInstallation {
    /** Action à entreprendre vis-à-vis du service foreground. */
    internal sealed interface Action {
        /** Aucune phase en cours : arrêter le service (et sa notification). */
        data object Arreter : Action

        /**
         * Une phase s'exécute : notification visible, mise à jour.
         *
         * @property phase phase en cours d'exécution.
         * @property etape nom court de la sous-étape courante.
         * @property progression progression de la sous-étape.
         */
        data class Notifier(
            val phase: InstallPhase,
            val etape: String,
            val progression: Progress,
        ) : Action
    }

    /**
     * Décide de l'action pour un état du parcours donné.
     *
     * @param etat état courant du parcours (source de vérité unique).
     * @return l'action à entreprendre — jamais une chaîne localisée :
     * le service traduit via les ressources (fr/en).
     */
    fun decider(etat: EnvironmentSetupState): Action {
        val phase = etat.running ?: return Action.Arreter
        val running = etat.phase(phase) as? PhaseState.Running
        return Action.Notifier(
            phase = phase,
            etape = running?.step?.step.orEmpty(),
            progression = running?.progress ?: Progress.Indeterminate,
        )
    }
}
