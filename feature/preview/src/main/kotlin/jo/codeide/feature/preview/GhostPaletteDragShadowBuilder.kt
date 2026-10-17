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
 * entière sans style). La vue suit le doigt centrée sur le point de
 * contact.
 */
@SuppressLint("DiscouragedApi") // L'icône suit la CONVENTION ic_tuile_<id> du
// catalogue — une table de correspondance dupliquerait CataloguePalette.
internal class GhostPaletteDragShadowBuilder(
    contexte: Context,
    entree: CataloguePalette.EntreePalette,
) : View.DragShadowBuilder() {
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

    override fun onProvideShadowMetrics(
        metrics: Point,
        touchPoint: Point,
    ) {
        metrics.set(ghost.width, ghost.height)
        touchPoint.set(ghost.width / 2, ghost.height / 2)
    }

    override fun onDrawShadow(canevas: Canvas) {
        ghost.draw(canevas)
    }
}
