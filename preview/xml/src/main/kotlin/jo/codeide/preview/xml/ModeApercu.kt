package jo.codeide.preview.xml

/**
 * Le mode d'affichage du panneau (PE10d/e, maquette
 * `docs/mockups/apercu-xml.html` — la pastille segmented `.segMode` de
 * l'appbar : Design, Plan, Les deux) :
 * - [DESIGN] : le bitmap rendu (LayoutLib) ;
 * - [PLAN] : le SQUELETTE des vues rendues — contours pointillés accent
 *   sur le damier (miroir `.screen.bp` de la maquette, rendu « blueprint ») ;
 * - [LES_DEUX] : les deux planches CÔTE À CÔTE (miroir `buildBoards` :
 *   « Design · Pixel 4 » + « Plan », écart 40 px du monde).
 *
 * L'état vit dans la vue ([VuePreviewXml.modeAffichage], non persisté —
 * la maquette repart en Design à chaque ouverture) ; le hit (tap, drag)
 * se re-mappe sur la planche frappée ([CalculsVuePreview.coordonneesPlanche]).
 */
public enum class ModeApercu {
    DESIGN,
    PLAN,
    LES_DEUX,
}
