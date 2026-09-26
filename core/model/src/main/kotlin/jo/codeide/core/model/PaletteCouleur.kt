package jo.codeide.core.model

/**
 * Palette de couleurs statique de l'application (ADR 0060) : le jeu de
 * rôles Material 3 appliqué quand les couleurs dynamiques sont
 * **désactivées** (chaque palette est un schéma complet jour/nuit généré
 * par le moteur HCT de Material — même algorithme que Material Theme
 * Builder, seed indiquée dans values/palettes.xml de core:ui).
 *
 * La palette se choisit dans la section Apparence des Paramètres et
 * s'applique à **tous les écrans, tous les processus** — point
 * d'application unique ([jo.codeide.core.ui.AppliquerApparence]), le
 * réglage reste source-de-vérité dans DataStore (miroir synchrone pour
 * le démarrage à froid).
 */
public enum class PaletteCouleur {
    /** Indigo — palette de marque, défaut historique. */
    INDIGO,

    /** Bleu franc. */
    BLEU,

    /** Turquoise (teal). */
    TURQUOISE,

    /** Vert forêt. */
    VERT,

    /** Ambre chaud. */
    AMBRE,

    /** Rouge terracotta. */
    ROUGE,

    /** Violet électrique. */
    VIOLET,

    /** Rose framboise. */
    ROSE,

    ;

    public companion object {
        /**
         * Reconstitue la palette depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [INDIGO]).
         */
        public fun depuisNom(nom: String?): PaletteCouleur? = entries.firstOrNull { it.name == nom }
    }
}
