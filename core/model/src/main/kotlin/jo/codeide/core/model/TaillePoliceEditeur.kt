package jo.codeide.core.model

/**
 * Taille de la police de l'éditeur de code (section Paramètres —
 * Éditeur, ADR 0059) : trois tailles miroir de celles du terminal.
 *
 * Réglage **persisté avant consommation** : l'éditeur embarqué n'expose
 * pas encore sa typographie (cel-ui gère la sienne en interne) — le
 * réglage vit dans les paramètres dès maintenant pour que l'écran existe
 * et que le futur moteur s'y abonne sans nouvelle migration.
 */
public enum class TaillePoliceEditeur {
    /** Compacte — plus de lignes visibles sur petit écran. */
    PETITE,

    /** Valeur par défaut d'une installation neuve. */
    MOYENNE,

    /** Confort visuel — lecture facilitée. */
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
        public fun depuisNom(nom: String?): TaillePoliceEditeur? = entries.firstOrNull { it.name == nom }
    }
}
