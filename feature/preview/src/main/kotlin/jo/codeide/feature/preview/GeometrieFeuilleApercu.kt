package jo.codeide.feature.preview

import android.util.TypedValue

/**
 * La GÉOMÉTRIE de la FEUILLE inférieure de l'aperçu (v0.166.5 — retour
 * propriétaire du 2026-10-22 : « l'expansion doit aussi arriver jusqu'au
 * appbar » ; « le bottomsheet même en collapse ne cache plus aussi la
 * partie bottom du preview » ; « la section zoom et rendue · n vues sont
 * cachées » ; v0.173.0 — retour du même jour : « utilise deux niveaux
 * pour expand à moitié puis total sous le appbar » et « le canva ne doit
 * pas glisser pour disparaitre »).
 *
 * Décisions (miroir `applySheet` de la maquette + la demande) :
 * - DEUX NIVEAUX D'EXPANSION (v0.173.0) : MOITIÉ (ratio de l'écran,
 * `halfExpandedRatio` du comportement) puis TOTAL sous l'en-tête
 *   (`expandedOffset` = bas de l'en-tête, `fitToContents` FAUX — sinon le
 *   glissement saute le demi-niveau, la source Material 1.14.0
 *   `onViewReleased` vise `STATE_EXPANDED` direct quand `fitToContents`) ;
 * - LE CANVAS SE RETIRE : le conteneur du rendu prend un padding bas
 *   égal au bord COURANT de la feuille + la marge du HUD — le rendu, le
 *   zoom (bas-droite) et la pastille « Rendu · N vues » (bas-gauche) ne
 *   passent JAMAIS sous la feuille, repliée comprise (anciennement une
 *   `translationY` qui ne couvrait ni l'état half-expanded ni la barre
 *   de gestes) ;
 * - LE CANVAS NE DISPARAIT JAMAIS (v0.173.0) : le retrait est BORNÉ —
 *   la feuille TOTALE couvre tout l'espace sous l'appbar, mais le canvas
 *   garde au moins sa bande minimale ([BANDE_CANVAS_MIN_DP]) : la vue
 *   reste MESURÉE, son zoom et son pan SURVIVENT aux éditions faites
 *   feuille déployée (la bande vit derrière la feuille, opaque) ;
 * - LA BULLE suit la même réserve (`sh` de `placeBubble` — la maquette
 *   n'a qu'UNE formule : le décalage du zoom EST la réserve de la bulle).
 *
 * Fonctions PURES (règle 1) : l'activité pose les mesures, les formules
 * vivent ici — testées à part ([GeometrieFeuilleApercuTest]).
 */
internal object GeometrieFeuilleApercu {
    /**
     * Hauteur repliée de la feuille SANS la barre de navigation : poignée
     * (8 + 4 + 6 + fil + 6 ≈ 40) + onglets 37 (miroir 76 de la maquette,
     * arrondi au-dessus).
     */
    internal const val PEEK_DP: Int = 78

    /**
     * Le RATIO du niveau MOITIÉ (v0.173.0) : `halfExpandedRatio` du
     * comportement — la feuille TOTALEMENT repliée sur la moitié BASSE
     * de l'écran (`halfExpandedOffset = parentHeight × (1 − ratio)` de
     * la source Material), le canvas garde la moitié haute.
     */
    internal const val RATIO_DEMI_EXPANSION: Float = 0.5f

    /**
     * La BANDE MINIMALE du canvas en dp (v0.173.0 — « le canva ne doit
     * pas glisser pour disparaitre ») : même TOTALEMENT déployée, la
     * feuille ne réduit jamais le canvas sous cette bande — la vue du
     * rendu reste mesurée, son zoom survit aux éditions ; la bande vit
     * DERRIÈRE la feuille (opaque), invisible au niveau total.
     */
    internal const val BANDE_CANVAS_MIN_DP: Int = 24

    /**
     * Le PEEK en pixels : la hauteur repliée VISIBLE au-dessus de la
     * barre de navigation — la feuille edge-to-edge descend SOUS la
     * barre, le peek la dépasse d'autant (le contenu de la feuille
     * reçoit ce même inset en padding bas).
     */
    internal fun peekPx(densite: Float, insetNavigationPx: Int): Int = dpEnPx(densite, PEEK_DP) + insetNavigationPx

    /** La BANDE minimale du canvas EN PIXELS (densité posée par l'appelant). */
    internal fun bandeCanvasMinPx(densite: Float): Int = dpEnPx(densite, BANDE_CANVAS_MIN_DP)

    /**
     * La hauteur du CONTENEUR DES VOLETS pour que la feuille DÉPLOYÉE
     * monte jusqu'à l'EN-TÊTE (appbar + puces) : l'espace sous l'en-tête,
     * moins le clavier ouvert, moins le chrome fixe de la feuille (poignée
     * + onglets) et l'inset de navigation (padding bas du contenu). La
     * feuille `wrap_content` égalant alors cet espace, son niveau TOTAL
     * (`expandedOffset` = bas de l'en-tête, v0.173.0) cale son bord haut
     * SUR le bas de l'en-tête — ou AU-DESSUS DU CLAVIER quand l'IME est
     * ouvert (la hauteur des volets perd l'IME, le bord HAUT reste à
     * l'en-tête, le bord BAS remonte au-dessus du clavier).
     */
    internal fun hauteurVoletsPx(
        hauteurEcranPx: Int,
        hauteurEntetePx: Int,
        insetImePx: Int,
        chromeFeuillePx: Int,
        insetNavigationPx: Int,
    ): Int = (hauteurEcranPx - hauteurEntetePx - insetImePx - chromeFeuillePx - insetNavigationPx).coerceAtLeast(0)

    /**
     * Le RETRAIT BAS du canvas (le padding du conteneur du rendu) : le
     * bord courant de la feuille AU-DESSUS du bas de l'écran plus la marge
     * du HUD — le rendu et ses pièces basses s'arrêtent au-dessus de la
     * feuille, jamais dessous (miroir `h = (S.open ? SHEET_OPEN : 72) + 10`
     * de `applySheet` ; la même valeur porte la réserve de la bulle `sh`).
     *
     * v0.173.0 — BORNÉ : le retrait ne dépasse JAMAIS la hauteur du
     * conteneur moins la bande minimale ([BANDE_CANVAS_MIN_DP]) — la
     * feuille TOTALE (sous l'appbar) ne réduit plus le canvas à néant,
     * le rendu reste mesuré et son zoom survit aux éditions faites
     * feuille déployée ; le surplus vit DERRIÈRE la feuille (opaque).
     */
    internal fun retraitCanvasPx(
        hauteurVisibleFeuillePx: Int,
        margeHudPx: Int,
        hauteurConteneurPx: Int,
        bandeCanvasMinPx: Int,
    ): Int = (hauteurVisibleFeuillePx + margeHudPx)
        .coerceAtMost((hauteurConteneurPx - bandeCanvasMinPx).coerceAtLeast(0))
        .coerceAtLeast(0)

    /**
     * Le NIVEAU SUIVANT du cycle d'expansion (v0.173.0 — « deux niveaux
     * pour expand à moitié puis total sous le appbar ») : replié →
     * MOITIÉ → TOTAL → replié. [deplierSeulement] (sélection d'onglet,
     * « Ligne n ») ne fait que DÉPLOYER au PREMIER niveau — la moitié :
     * l'édition d'attributs se fait le rendu ENCORE VISIBLE, le total
     * reste au geste (glisser ou second appui).
     *
     * Les états sont passés ENTIERS (et pas les constantes Material) :
     * la formule reste PURE, testable sans la bibliothèque.
     */
    internal fun etatSuivantExpansion(
        etatCourant: Int,
        deplierSeulement: Boolean,
        etatReplie: Int,
        etatMoitie: Int,
        etatTotal: Int,
    ): Int = when (etatCourant) {
        etatTotal -> if (deplierSeulement) etatTotal else etatReplie
        etatMoitie -> if (deplierSeulement) etatMoitie else etatTotal
        else -> etatMoitie
    }

    /** La conversion dp → px (la densité de l'écran courant, posée par l'appelant). */
    private fun dpEnPx(densite: Float, valeur: Int): Int = (valeur * densite).toInt()
}
