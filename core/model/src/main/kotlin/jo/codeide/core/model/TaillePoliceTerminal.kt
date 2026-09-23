package jo.codeide.core.model

/**
 * Taille de la police à chasse fixe du terminal intégré (Terminal T5,
 * prompt Terminal-1, section 5 : « police à chasse fixe configurable »).
 *
 * Réglage **dédié minimal** : l'application n'avait aucun réglage de
 * police (l'éditeur `cel-ui` gère le sien en interne) — trois tailles
 * suffisent, persistées dans les paramètres applicatifs.
 *
 * La conversion en taille réelle (pixels) appartient à l'écran du
 * terminal, seul consommateur du réglage.
 */
public enum class TaillePoliceTerminal {
    /** Compacte — plus de colonnes visibles sur petit écran. */
    PETITE,

    /** Valeur par défaut d'une installation neuve. */
    MOYENNE,

    /** Confort visuel — lecture facilitée, moins de colonnes. */
    GRANDE,

    ;

    public companion object {
        /**
         * Reconstitue la taille depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [MOYENNE]).
         */
        public fun depuisNom(nom: String?): TaillePoliceTerminal? = entries.firstOrNull { it.name == nom }
    }
}
