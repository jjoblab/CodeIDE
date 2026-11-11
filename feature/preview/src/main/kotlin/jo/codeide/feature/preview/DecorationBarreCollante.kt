package jo.codeide.feature.preview

import android.graphics.Canvas
import android.view.View
import androidx.core.graphics.withTranslation
import androidx.recyclerview.widget.RecyclerView

/**
 * La barre d'outils ÉPINGLÉE du volet Attributs (PE11b, ADR 0132 —
 * miroir `position: sticky` de la maquette) : dès que l'item barre passe
 * le bord supérieur du volet, il est DESSINÉ par-dessus la liste, à la
 * hauteur du padding — le titre « MODIFIER · … » défile au-dessus de
 * lui, la recherche et la bascule restent à portée de pouce.
 *
 * L'épinglage ne vaut que tant que la barre reste un enfant vivant du
 * [RecyclerView] (recyclée hors écran au-delà d'un écran de défilement,
 * comme la maquette qui détruit son sticky avec son conteneur) : les
 * listes du volet sont courtes, le cas ne survient pas en pratique.
 */
internal class DecorationBarreCollante : RecyclerView.ItemDecoration() {
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(canvas, parent, state)
        var barre: View? = null
        for (indice in 0 until parent.childCount) {
            val enfant = parent.getChildAt(indice)
            if (parent.getChildViewHolder(enfant).itemViewType == AdaptateurAttributs.VUE_BARRE) {
                barre = enfant
                break
            }
        }
        val vue = barre ?: return
        val translation = parent.paddingTop.toFloat() - vue.top
        if (translation > 0f) {
            canvas.withTranslation(0f, translation) {
                vue.draw(this)
            }
        }
    }
}
