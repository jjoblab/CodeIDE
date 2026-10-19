package jo.codeide.feature.preview

import android.content.Context
import android.content.res.ColorStateList
import jo.codeide.feature.preview.databinding.PopoverMenuCalquesBinding
import jo.codeide.preview.xml.VuePreviewXml
import jo.codeide.core.ui.R as RUi

/**
 * Le popover CALQUES (PE9c, PE11j — miroir `showPop("layers")` de la
 * maquette) : les SIX calques de la VUE (titre, état lu à la volée,
 * pose) et le groupe « CHROME SYSTÈME » — « Barre de navigation », qui
 * vit dans la CONFIGURATION DE RENDU (un changement RELANCE le rendu),
 * pas dans la vue.
 *
 * Extraction PE11k (règle 8, LargeClass de [PreviewActivity] — même
 * patron que [BandeauApercu] en PE11i) : chrome PUR, aucun état métier
 * — l'activité pose les accès à la vue et à la configuration de rendu.
 */
internal class PopoverCalquesApercu(
    private val contexte: Context,
    private val vueRendu: VuePreviewXml,
    /** L'état « Barre de navigation » de la configuration de rendu (défaut ON). */
    private val barreNavigation: () -> Boolean,
    /** Pose l'état « Barre de navigation » (un changement relance le rendu). */
    private val poserBarreNavigation: (Boolean) -> Unit,
) {
    /** Lie les rangées du popover : accents, états lus à la volée, bascules. */
    internal fun lier(popover: PopoverMenuCalquesBinding) {
        val calques = entrees()
        val accents =
            listOf(
                RUi.attr.colorCanalSync,
                RUi.attr.colorCanalTaches,
                RUi.attr.colorCanalBuild,
                androidx.appcompat.R.attr.colorError,
                RUi.attr.colorSucces,
                RUi.attr.colorInfo,
            )
        val ranges =
            listOf(
                popover.calqueParentFreres,
                popover.calqueMargesPadding,
                popover.calqueContraintes,
                popover.calqueProblemes,
                popover.calqueBornes,
                popover.calqueGrille,
            )
        calques.forEachIndexed { indice, calque ->
            val rangee = ranges[indice]
            rangee.pastilleCalque.backgroundTintList =
                ColorStateList.valueOf(StyleVolet.couleur(contexte, accents[indice]))
            rangee.libelleCalque.setText(calque.titreRes)
            rangee.basculeCalque.isChecked = calque.estActif()
            rangee.rangeeCalque.setOnClickListener { rangee.basculeCalque.toggle() }
            rangee.basculeCalque.setOnCheckedChangeListener { _, coche ->
                calque.poser(coche)
            }
        }
        // PE11j — « Chrome système / Barre de navigation » : l'état vient
        // de la CONFIGURATION DE RENDU (défaut ON — miroir `S.nav`) ; la
        // pastille porte le canal étiquette (système).
        popover.calqueNavigation.pastilleCalque.backgroundTintList =
            ColorStateList.valueOf(StyleVolet.couleur(contexte, RUi.attr.colorCanalEtiquette))
        popover.calqueNavigation.libelleCalque.setText(R.string.preview_calque_navigation)
        popover.calqueNavigation.basculeCalque.isChecked = barreNavigation()
        popover.calqueNavigation.rangeeCalque.setOnClickListener {
            popover.calqueNavigation.basculeCalque.toggle()
        }
        popover.calqueNavigation.basculeCalque.setOnCheckedChangeListener { _, coche ->
            poserBarreNavigation(coche)
        }
    }

    /** Les SIX calques de la VUE (titre, état lu à la volée, pose). */
    private fun entrees(): List<EntreeCalque> =
        listOf(
            EntreeCalque(R.string.preview_calque_parent_freres, { vueRendu.calquesParentFreres }, {
                vueRendu.calquesParentFreres = it
            }),
            EntreeCalque(R.string.preview_calque_marges_padding, { vueRendu.calquesMargesPadding }, {
                vueRendu.calquesMargesPadding = it
            }),
            EntreeCalque(R.string.preview_calque_contraintes, { vueRendu.calquesContraintes }, {
                vueRendu.calquesContraintes = it
            }),
            EntreeCalque(
                R.string.preview_calque_problemes,
                { vueRendu.calquesProblemes },
                { vueRendu.calquesProblemes = it },
            ),
            EntreeCalque(R.string.preview_calque_bornes, { vueRendu.calquesBornes }, { vueRendu.calquesBornes = it }),
            EntreeCalque(R.string.preview_calque_grille, { vueRendu.grilleAffichee }, { vueRendu.grilleAffichee = it }),
        )

    /** UNE entrée du popover : titre, état lu à la volée, pose. */
    private data class EntreeCalque(
        val titreRes: Int,
        val estActif: () -> Boolean,
        val poser: (Boolean) -> Unit,
    )
}
