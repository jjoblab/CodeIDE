package jo.codeide.feature.preview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.view.LayoutInflater
import android.view.View
import jo.codeide.feature.preview.databinding.ItemGhostPaletteBinding
import jo.codeide.preview.xml.CataloguePalette

/**
 * Le GHOST du drag d'une tuile de la palette (PE9e, miroir `.ghost` de
 * la maquette : « l'ombre porte la pill violette icône + nom du widget »
 * — l'ombre PAR DÉFAUT de [View.DragShadowBuilder] montrait la tuile
 * entière sans style).
 *
 * PE11j (ADR 0134, § 4.3) : le point d'accroche est AU-DESSUS du doigt
 * (miroir `translate(-50 %, -130 %)` — le doigt tient la pill PAR-DESSOUS,
 * le rendu reste visible) ; le libellé « → cible » du layout de
 * référence (item_ghost_palette.xml) reste MASQUÉ : l'ombre d'un drag
 * Android se fige au lancement, elle ne peut pas suivre la cible
 * survolée — la cible EN DIRECT reste dessinée par le moteur (zones de
 * dépôt PE7b, PREVIEW.md § 3.4).
 */
@SuppressLint("DiscouragedApi") // L'icône suit la CONVENTION ic_tuile_<id> du
// catalogue — une table de correspondance dupliquerait CataloguePalette.
internal class GhostPaletteDragShadowBuilder(contexte: Context, entree: CataloguePalette.EntreePalette) :
    View.DragShadowBuilder() {
    /** La vue hors hiérarchie (mesurée puis dessinée dans l'ombre). */
    private val ghost: View = ItemGhostPaletteBinding.inflate(LayoutInflater.from(contexte)).root

    init {
        val liaison = ItemGhostPaletteBinding.bind(ghost)
        val iconeRes =
            contexte.resources.getIdentifier(
                "ic_tuile_" + entree.id,
                "drawable",
                contexte.packageName,
            )
        if (iconeRes != 0) liaison.iconeGhost.setImageResource(iconeRes)
        liaison.nomGhost.text = entree.nomAffiche
        // Mesure + layout HORS écran : la shadow n'est attachée nulle part.
        ghost.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED,
        )
        ghost.layout(0, 0, ghost.measuredWidth, ghost.measuredHeight)
    }

    override fun onProvideShadowMetrics(metrics: Point, touchPoint: Point) {
        metrics.set(ghost.width, ghost.height)
        // Miroir translate(-50 %, -130 %) : le doigt sous la pill —
        // le point d'accroche est à 50 % de largeur et 130 % de hauteur.
        touchPoint.set(ghost.width / 2, (ghost.height * DECALAGE_ACCROCHE).toInt())
    }

    override fun onDrawShadow(canevas: Canvas) {
        ghost.draw(canevas)
    }

    private companion object {
        /** La hauteur du point d'accroche, en FRACTION de la hauteur (miroir −130 %). */
        private const val DECALAGE_ACCROCHE: Float = 1.3f
    }
}
