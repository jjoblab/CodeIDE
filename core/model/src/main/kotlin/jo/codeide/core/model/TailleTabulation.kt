package jo.codeide.core.model

/**
 * Largeur d'une tabulation de l'éditeur de code, en espaces (section
 * Paramètres — Éditeur, ADR 0059).
 *
 * Trois largeurs standards suffisent : 2 (feuilles de style et langages
 * concis), 4 (défaut des projets Java/Kotlin Android Studio) et 8
 * (convention Unix historique). Le réglage est **persisté avant
 * consommation** : le moteur d'édition s'y abonnera quand la
 * coloration/indentation intelligente existera.
 */
@Suppress("MagicNumber") // Exemption ciblée (règle 16) : les largeurs 2/4/8 sont la sémantique même de l'enum.
public enum class TailleTabulation(
    /** Largeur en espaces — exposée au moteur d'édition. */
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
