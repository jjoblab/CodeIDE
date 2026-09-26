package jo.codeide.feature.terminal

import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.model.StyleCurseurTerminal
import jo.codeide.core.model.TaillePoliceTerminal

/** Clés d'intent partagées entre la navigation (app) et l'écran. */
object ClesTerminal {
    /** Répertoire de travail suggéré par le point d'entrée (T6). */
    const val EXTRA_REPERTOIRE = "repertoire_travail"
}

/**
 * État de rendu de l'écran du terminal (prompt Terminal-1, section 5.1).
 *
 * @property sessions liste globale des sessions, dans l'ordre de création.
 * @property idSessionActive identifiant de la session rendue, ou `null`.
 * @property taillePolice taille de la police à chasse fixe (réglage
 * dédié minimal de l'étape T5, section Terminal des Paramètres — ADR 0059).
 * @property styleCurseur style du curseur de l'émulateur (ADR 0059).
 * @property copieSelectionAuto copier la sélection au presse-papiers dès
 * la fin de la sélection (ADR 0059).
 */
data class EtatTerminal(
    val sessions: List<TerminalSessionSummary> = emptyList(),
    val idSessionActive: String? = null,
    val taillePolice: TaillePoliceTerminal = TaillePoliceTerminal.MOYENNE,
    val styleCurseur: StyleCurseurTerminal = StyleCurseurTerminal.BLOC,
    val copieSelectionAuto: Boolean = false,
)

/**
 * Intentions utilisateur de l'écran du terminal (section 5.3 du prompt
 * maître : le fragment/l'activité n'émet que des actions).
 */
sealed interface ActionTerminal {
    /** Crée une session (répertoire suggéré ou répertoire général). */
    data object NouvelleSession : ActionTerminal

    /** Sélectionne une session — rebranche le rendu dessus. */
    data class OuvrirSession(
        val sessionId: String,
    ) : ActionTerminal

    /** Renomme une session (dialogue de l'appui long sur l'onglet). */
    data class RenommerSession(
        val sessionId: String,
        val libelle: String,
    ) : ActionTerminal

    /**
     * Ferme une session — le ViewModel décide : fermeture directe si le
     * shell semble au repos, effet de confirmation sinon (section 5 :
     * « confirmation si une commande semble en cours »).
     */
    data class FermerSession(
        val sessionId: String,
    ) : ActionTerminal

    /** Poursuit la fermeture après confirmation explicite. */
    data class ConfirmerFermeture(
        val sessionId: String,
    ) : ActionTerminal

    /** Duplique une session : même répertoire de travail, nouvelle session. */
    data class DupliquerSession(
        val sessionId: String,
    ) : ActionTerminal
}

/**
 * Effets ponctuels de l'écran du terminal (dialogues de confirmation).
 */
sealed interface EffetTerminal {
    /** Une commande semble en cours : demander confirmation avant fermeture. */
    data class DemanderConfirmationFermeture(
        val sessionId: String,
        val libelle: String,
    ) : EffetTerminal
}
