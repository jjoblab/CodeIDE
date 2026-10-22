package jo.codeide.feature.preview

import android.util.TypedValue

/**
 * La GÉOMÉTRIE de la FEUILLE inférieure de l'aperçu (v0.166.5 — retour
 * propriétaire du 2026-10-22 : « l'expansion doit aussi arriver jusqu'au
 * appbar » ; « le bottomsheet même en collapse ne cache plus aussi la
 * partie bottom du preview » ; « la section zoom et rendue · n vues sont
 * cachées »).
 *
 * Décisions (miroir `applySheet` de la maquette + la demande) :
 * - DÉPLOYÉE JUSQU'À L'APPBAR : la hauteur des volets remplit l'espace
 *   SOUS l'en-tête (moins le clavier) — le contenu `wrap_content` de la
 *   feuille égalant cet espace, l'état `expanded` (fit-to-contents) cale
 *   le bord haut de la feuille SUR le bas de l'en-tête, clavier exclu ;
 * - LE CANVAS SE RETIRE : le conteneur du rendu prend un padding bas
 *   égal au bord COURANT de la feuille + la marge du HUD — le rendu, le
 *   zoom (bas-droite) et la pastille « Rendu · N vues » (bas-gauche) ne
 *   passent JAMAIS sous la feuille, repliée comprise (anciennement une
 *   `translationY` qui ne couvrait ni l'état half-expanded ni la barre
 *   de gestes) ;
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
     * Le PEEK en pixels : la hauteur repliée VISIBLE au-dessus de la
     * barre de navigation — la feuille edge-to-edge descend SOUS la
     * barre, le peek la dépasse d'autant (le contenu de la feuille
     * reçoit ce même inset en padding bas).
     */
    internal fun peekPx(
        densite: Float,
        insetNavigationPx: Int,
    ): Int = dpEnPx(densite, PEEK_DP) + insetNavigationPx

    /**
     * La hauteur du CONTENEUR DES VOLETS pour que la feuille DÉPLOYÉE
     * monte jusqu'à l'EN-TÊTE (appbar + puces) : l'espace sous l'en-tête,
     * moins le clavier ouvert, moins le chrome fixe de la feuille (poignée
     * + onglets) et l'inset de navigation (padding bas du contenu). La
     * feuille `wrap_content` égalant alors cet espace, son état expanded
     * (fit-to-contents) cale son bord haut SUR le bas de l'en-tête — ou
     * AU-DESSUS DU CLAVIER quand l'IME est ouvert.
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
     */
    internal fun retraitCanvasPx(
        hauteurVisibleFeuillePx: Int,
        margeHudPx: Int,
    ): Int = (hauteurVisibleFeuillePx + margeHudPx).coerceAtLeast(0)

    /** La conversion dp → px (la densité de l'écran courant, posée par l'appelant). */
    private fun dpEnPx(
        densite: Float,
        valeur: Int,
    ): Int = (valeur * densite).toInt()
}
