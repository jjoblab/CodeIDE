package jo.codeide.feature.preview

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * L'ESPACEMENT des cartes de l'éditeur (correctif du lot parents — miroir
 * les trois `gap` de la maquette, jetons [R.dimen.apercu_gap_sections] et
 * [R.dimen.apercu_correctifs_gap]) :
 *  - `.secs{display:flex;flex-direction:column;gap:10px}` : 10 dp ENTRE
 *    les cartes de section, au-dessus du bouton « Afficher les N autres »
 *    et du message « Aucun attribut ne correspond » (tous enfants du
 *    conteneur `.secs`) — la PREMIÈRE carte incluse : l'écart sous la
 *    barre d'outils ou sous les correctifs est le même `gap:10px` de
 *    `.ed` ;
 *  - `.fixes{display:flex;flex-direction:column;gap:6px}` : 6 dp ENTRE
 *    cartes de correction (le titre de groupe « CORRECTIONS » compris),
 *    et 10 dp au-dessus de CE titre (l'écart `.ed` entre la barre
 *    d'outils et le bloc `.fixes`).
 *
 * Les rythmes vivent ICI (une [RecyclerView.ItemDecoration], précédent
 * [EspacementGrilleTuiles]), PAS en marges des items : un item ne connaît
 * pas son voisin, la décoration si — l'écart ne s'accumule jamais (une
 * carte sous la DERNIÈRE correction vaut 10, pas 6 + 10). Les rangées de
 * LECTURE SEULE (sous le trait de fin) gardent leurs marges propres :
 * hors de l'éditeur, hors du correctif.
 */
internal class DecorationEspaceSections(
    contexte: Context,
) : RecyclerView.ItemDecoration() {
    /** L'écart de la colonne des cartes (`.secs` gap, `.ed` gap). */
    private val ecartSections: Int = contexte.resources.getDimensionPixelSize(R.dimen.apercu_gap_sections)

    /** L'écart intérieur du bloc des corrections (`.fixes` gap). */
    private val ecartCorrectifs: Int = contexte.resources.getDimensionPixelSize(R.dimen.apercu_correctifs_gap)

    override fun getItemOffsets(
        rect: Rect,
        vue: View,
        parent: RecyclerView,
        etat: RecyclerView.State,
    ) {
        val adaptateur = parent.adapter as? AdaptateurAttributs ?: return
        val position = parent.getChildAdapterPosition(vue)
        // La première rangée ne porte RIEN (le padding du volet, 8/10/14/10,
        // tient déjà son rôle — miroir `.ed` bord à bord).
        if (position <= 0) return
        val items = adaptateur.currentList
        val precedent = items[position - 1]
        rect.top =
            when (items.getOrNull(position)) {
                // La colonne `.secs` : cartes, bouton « autres », message
                // vide — 10 dp au-dessus de CHACUN (la première incluse :
                // même écart sous la barre ou les correctifs).
                is ItemAttributs.Carte,
                is ItemAttributs.BoutonAutres,
                is ItemAttributs.Message,
                -> {
                    ecartSections
                }

                // Le bloc `.fixes` : 6 dp entre ses cartes ; son TITRE
                // porte les 10 dp de l'écart `.ed` sous la barre. Un
                // titre de groupe HORS correctifs (lecture seule) garde
                // ses marges propres : rien ici.
                is ItemAttributs.Correctif -> {
                    if (precedent is ItemAttributs.TitreGroupe || precedent is ItemAttributs.Correctif) {
                        ecartCorrectifs
                    } else {
                        AUCUN_ECART
                    }
                }

                is ItemAttributs.TitreGroupe -> {
                    if (precedent is ItemAttributs.BarreOutils) ecartSections else AUCUN_ECART
                }

                else -> {
                    AUCUN_ECART
                }
            }
    }

    private companion object {
        /** Aucun écart (rangées hors éditeur, première rangée). */
        private const val AUCUN_ECART: Int = 0
    }
}
