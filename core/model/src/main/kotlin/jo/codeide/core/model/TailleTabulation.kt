package jo.codeide.core.model

/**
 * Largeur d'une tabulation de l'éditeur de code, en espaces (section
 * Paramètres — Éditeur, ADR 0059).
 *
 * Trois largeurs standards suffisent : 2 (feuilles de style et langages
 * concis), 4 (défaut des projets Java/Kotlin Android Studio) et 8
 * (convention Unix historique). Le réglage est **consommé** (v0.168.0,
 * ADR 0136 phase 2) : `EditorView.setFormattingOptions` (code-editor
 * 3.74.0) l'envoie dans les requêtes LSP de mise en forme — il avait
 * été retiré en v0.37.0 (ADR 0066) faute de consommateur, la
 * bibliothèque expose désormais le setter promis. L'INDENTATION
 * AFFICHÉE, elle, reste auto-détectée par fichier côté bibliothèque
 * (IndentDetection) — le réglage ne change pas le dessin.
 */
@Suppress("MagicNumber") // Exemption ciblée (règle 16) : les largeurs 2/4/8 sont la sémantique même de l'enum.
public enum class TailleTabulation(
    /** Largeur en espaces — consommée par les requêtes de mise en forme
     * LSP (`AppSettings.editorTailleTabulation` → `setFormattingOptions`). */
    public val espaces: Int,
) {
    /** Largeur compacte (langages concis). */
    DEUX(2),

    /** Valeur par défaut d'une installation neuve (convention Android). */
    QUATRE(4),

    /** Largeur large (convention Unix historique). */
    HUIT(8),

    ;

    public companion object {
        /**
         * Reconstitue la largeur depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [QUATRE]).
         */
        public fun depuisNom(nom: String?): TailleTabulation? = entries.firstOrNull { it.name == nom }
    }
}
