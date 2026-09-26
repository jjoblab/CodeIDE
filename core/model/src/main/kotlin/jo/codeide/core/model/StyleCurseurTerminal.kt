package jo.codeide.core.model

/**
 * Style du curseur du terminal intégré (section Paramètres — Terminal,
 * ADR 0059) : exposé en réglage, traduit en constante DECSCUSR de
 * l'émulateur Termux (`TERMINAL_CURSOR_STYLE_*`) au moment du rendu.
 *
 * Le curseur de l'émulateur étant piloté par le client de session
 * (`TerminalSessionClient.getTerminalCursorStyle`), le réglage se lit
 * **à chaque demande de l'émulateur** — un changement s'applique aux
 * sessions vivantes dès le prochain `setCursorStyle()`.
 */
public enum class StyleCurseurTerminal {
    /** Rectangles plein caractère (défaut Termux). */
    BLOC,

    /** Trait horizontal sous le caractère. */
    LIGNE,

    /** Barre verticale fine avant le caractère. */
    BARRE,

    ;

    public companion object {
        /**
         * Reconstitue le style depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [BLOC]).
         */
        public fun depuisNom(nom: String?): StyleCurseurTerminal? = entries.firstOrNull { it.name == nom }
    }
}
