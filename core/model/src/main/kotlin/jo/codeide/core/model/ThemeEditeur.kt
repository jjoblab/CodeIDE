package jo.codeide.core.model

/**
 * Thème de coloration de l'éditeur de code (v0.37.0 — la section Éditeur
 * des Paramètres devient **consommée** : chaque entrée projette un thème
 * embarqué de cel-ui, appliqué à `EditorView` par `OptionsEditeur`).
 *
 * [AUTO] est le seul état qui **suit** l'application : thème clair de cel
 * le jour, thème sombre la nuit — le comportement d'avant v0.37.0, préservé
 * par défaut pour les installations existantes. Toute autre entrée force un
 * thème précis, indépendamment du mode clair/sombre du système (comme le
 * sélecteur de thème d'éditeur d'Android Studio / VS Code).
 *
 * Les neuf thèmes forcés correspondent exactement aux fabriques statiques
 * publiques de `jo.codeeditor.view.chrome.EditorTheme` (cel-ui 3.37.0) :
 * dark, light, dracula, oneDark, monokai, solarizedDark, gitHubLight,
 * gitHubDark, nord.
 */
public enum class ThemeEditeur {
    /** Suit l'application : clair le jour, sombre la nuit (défaut). */
    AUTO,

    /** VS Code Dark+ (thème sombre par défaut de cel-ui). */
    VSCODE_SOMBRE,

    /** VS Code Light+ (thème clair par défaut de cel-ui). */
    VSCODE_CLAIR,

    /** Dracula. */
    DRACULA,

    /** Atom One Dark. */
    ONE_DARK,

    /** Monokai Pro. */
    MONOKAI,

    /** Solarized Dark. */
    SOLARIZED_SOMBRE,

    /** GitHub Light. */
    GITHUB_CLAIR,

    /** GitHub Dark (Dark Dimmed). */
    GITHUB_SOMBRE,

    /** Nord. */
    NORD,

    ;

    public companion object {
        /**
         * Reconstitue le thème depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [AUTO]).
         */
        public fun depuisNom(nom: String?): ThemeEditeur? = entries.firstOrNull { it.name == nom }
    }
}
