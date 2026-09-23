package jo.codeide.core.terminalruntime

import jo.codeide.core.domain.TerminalSessionSummary

/**
 * Décision du cycle de vie du service foreground (logique **pure**,
 * testée) : tant qu'au moins une session vit, la notification reste ;
 * dès qu'aucune ne vit, le service s'arrête — jamais l'inverse (une
 * session créée redémarre le service, cf. [DemarreurService]).
 */
internal object DecisionServiceTerminal {
    /** Action à entreprendre vis-à-vis du service foreground. */
    internal sealed interface Action {
        /** Aucune session vivante : arrêter le service (et sa notification). */
        data object Arreter : Action

        /** Au moins une session vit : notification visible (créée ou mise à jour). */
        data class Notifier(
            val sessionsVivantes: Int,
        ) : Action
    }

    /**
     * Décide de l'action pour un état de sessions donné.
     *
     * @param sessions liste courante des métadonnées.
     * @return l'action à entreprendre.
     */
    fun decider(sessions: List<TerminalSessionSummary>): Action {
        val vivantes = sessions.count { it.isAlive }
        return if (vivantes == 0) Action.Arreter else Action.Notifier(vivantes)
    }
}
