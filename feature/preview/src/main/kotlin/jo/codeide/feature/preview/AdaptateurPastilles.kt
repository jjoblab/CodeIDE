package jo.codeide.feature.preview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.preview.databinding.ItemPastillePaletteBinding

/**
 * L'ADAPTATEUR de la palette de COULEURS du contrôle couleur (PE11e —
 * miroir le bloc `.cpal` de la maquette : « Object.keys(RES.color) »
 * puis les entrées FIXES du `.concat(...)`) : UNE pastille =
 * `item_pastille_palette` (disque 28 dp) + la couche TEINTE que le
 * liage POSE par-dessus (miroir son commentaire : « le liage pose la
 * couleur par backgroundTint sur `teinte` » — les anneaux
 * « trait »/« surface » restent au drawable racine).
 *
 * Chaque pastille porte son NOM en `contentDescription` (miroir
 * `b.title = n ; b.setAttribute('aria-label', n)`).
 */
internal class AdaptateurPastilles(private val pastilles: List<Pastille>, private val auChoix: (String) -> Unit) :
    RecyclerView.Adapter<AdaptateurPastilles.Detenteur>() {
    /** UNE entrée de la palette (nom complet + teinte ARGB résolue). */
    internal data class Pastille(
        /** Le NOM complet écrit au clic (`@color/x`, `@android:color/white`, `?attr/colorPrimary`). */
        val nom: String,
        /** La teinte ARGB résolue pour la pastille (`null` = blanc du drawable racine). */
        val teinte: Int?,
    )

    /** Le détenteur d'une pastille. */
    internal class Detenteur(val liaison: ItemPastillePaletteBinding) : RecyclerView.ViewHolder(liaison.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Detenteur {
        val gonfleur = LayoutInflater.from(parent.context)
        return Detenteur(ItemPastillePaletteBinding.inflate(gonfleur, parent, false))
    }

    override fun getItemCount(): Int = pastilles.size

    override fun onBindViewHolder(detenteur: Detenteur, position: Int) {
        val pastille = pastilles[position]
        val racine = detenteur.liaison.pastillePalette
        racine.contentDescription = pastille.nom
        poserTeinte(racine, pastille.teinte)
        racine.setOnClickListener { auChoix(pastille.nom) }
    }

    /**
     * POSE la couche TEINTE (miroir le commentaire de la référence) :
     * une vue enfant à l'INSET des anneaux du drawable racine, fond
     * `apercu_pastille_palette_teinte` TINTÉ (SRC_IN sur le blanc).
     * La racine est RECYCLÉE : la teinte se retire avant de se poser.
     */
    private fun poserTeinte(racine: FrameLayout, teinte: Int?) {
        racine.findViewWithTag<View>(MARQUE_TEINTE)?.let(racine::removeView)
        val ressources = racine.resources
        val inset = ressources.getDimensionPixelSize(R.dimen.apercu_pastille_palette_inset)
        val enfant =
            View(racine.context).apply {
                tag = MARQUE_TEINTE
                layoutParams =
                    FrameLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ).apply { setMargins(inset, inset, inset, inset) }
                setBackgroundResource(R.drawable.apercu_pastille_palette_teinte)
                // Teinte NULLE = PAS de tint : l'ovale BLANC du drawable
                // racine reste visible (repli de la maquette — jamais
                // de damier en palette, écart documenté au drawable).
                backgroundTintList = teinte?.let(android.content.res.ColorStateList::valueOf)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        racine.addView(enfant)
    }

    internal companion object {
        /**
         * Le NOMBRE DE COLONNES de la palette (miroir `flex-wrap` +
         * `gap: 6px` : la maquette enroule les disques 28 dp + écart —
         * une grille Android s'en approche par la division entière de
         * la largeur utile par la CELLULE 34 dp = 28 + 2×3 de marges
         * de l'item). Bornée à 1 (jamais de colonne nulle).
         */
        internal fun colonnesPastille(largeurPx: Int, cellulePx: Int): Int =
            if (largeurPx <= 0 || cellulePx <= 0) 1 else maxOf(1, largeurPx / cellulePx)

        /** La MARQUE de la couche teinte posée au liage (lookup au recyclage). */
        private const val MARQUE_TEINTE: String = "teinte"
    }
}
