package jo.codeide.preview.xml

/**
 * Aides de sélection P6 (PE3, PREVIEW.md §3.1 : « clic sur une vue du
 * bitmap → sélection dans l'éditeur ») — fonctions PURES sur l'arbre
 * aplati [InfoVueRendu] (testées sans Android).
 *
 * Les deux directions :
 *  - **preview → éditeur** : [vueSelectableEn] cherche la vue la plus
 *    profonde contenant le point (coordonnées BITMAP), puis remonte vers
 *    l'ancêtre le plus proche PORTANT un span source (comme Android
 *    Studio : cliquer le texte d'un bouton sélectionne le `<Button>`,
 *    cliquer un enfant d'un `<include>` non résolu remonte au parent
 *    sélectionnable le plus proche) ;
 *  - **éditeur → preview** : [indiceVuePourSpan] cherche la vue au span
 *    le plus PETIT contenant l'offset du curseur (l'élément le plus
 *    INTERNE gagne : curseur dans un `<TextView>` enfant, pas dans tout
 *    le `<LinearLayout>`).
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount et
 * LoopWithTooManyJumpStatements — chaque ISSUE de la sélection (aucune
 * cible, cible profonde, remontée épuisée, span interne) est un retour
 * DISTINCT du contrat P6 ; les gardes du parcours (vue dégénérée, hors
 * bounds) sont le patron même du hit-test (précédent racinesSourcesDe
 * du dépôt).
 */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
public object SelectionPreview {
    /**
     * La vue sélectionnable au point (coordonnées bitmap), ou null.
     *
     * @param vues arbre aplati en pré-ordre (indices de parent valides).
     * @param x abscisse dans le BITMAP (px).
     * @param y ordonnée dans le BITMAP (px).
     */
    public fun vueSelectableEn(vues: List<InfoVueRendu>, x: Float, y: Float): InfoVueRendu? {
        val cible = vueLaPlusProfondeEn(vues, x, y)
        if (cible < 0) return null
        var indice = cible
        var garde = 0
        while (indice >= 0 && garde < PROFONDEUR_MAX_REMONTEE) {
            val vue = vues[indice]
            if (vue.selectable) return vue
            indice = vue.indiceParent
            garde += 1
        }
        return null
    }

    /** Indice de la vue la plus PROFONDE contenant le point (-1 si aucune). */
    public fun vueLaPlusProfondeEn(vues: List<InfoVueRendu>, x: Float, y: Float): Int {
        var meilleur = -1
        for ((indice, vue) in vues.withIndex()) {
            if (vue.droite <= vue.gauche || vue.bas <= vue.haut) continue
            if (vue.contient(x.toInt(), y.toInt()) &&
                (meilleur < 0 || vue.profondeur > vues[meilleur].profondeur)
            ) {
                meilleur = indice
            }
        }
        return meilleur
    }

    /**
     * Indice de la vue dont le SPAN contient l'offset du curseur — la plus
     * INTERNE (plus petit span) gagne ; -1 si aucune (hors document, ou
     * vues sans span : drawables, values, includes non résolus).
     */
    public fun indiceVuePourSpan(vues: List<InfoVueRendu>, offset: Int): Int {
        var meilleur = -1
        var meilleurTaille = Int.MAX_VALUE
        for ((indice, vue) in vues.withIndex()) {
            if (!vue.selectable) continue
            if (offset < vue.debutElement || offset > vue.finElement) continue
            val taille = vue.finElement - vue.debutElement
            if (taille < meilleurTaille) {
                meilleurTaille = taille
                meilleur = indice
            }
        }
        return meilleur
    }

    private const val PROFONDEUR_MAX_REMONTEE: Int = 64
}
