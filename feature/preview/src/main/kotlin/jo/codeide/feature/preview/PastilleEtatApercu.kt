package jo.codeide.feature.preview

import android.content.res.ColorStateList
import androidx.core.view.isVisible
import jo.codeide.feature.preview.databinding.ActivityPreviewBinding
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.ProfilPeripherique

/**
 * La PASTILLE D'ÉTAT de l'écran d'aperçu (PE10a, miroir `renderStatus` de
 * la maquette — extraction PE12f, règle 8 LargeClass de
 * [PreviewActivity], même patron que [BandeauApercu] en PE11i et
 * [PopoverCalquesApercu] en PE11k) : point coloré + « Rendu · X ms ·
 * N vues · W×H dp » (rendu valide), ambre « XML en cours de frappe »
 * (miroir `.status.edit`), rouge « Dernier rendu valide conservé »
 * (miroir `.status.err`), masquée sans document (état inactif).
 *
 * Chrome PUR : aucun état métier — l'activité pose l'état du moteur, les
 * dimensions viennent du PROFIL et de l'orientation par closures.
 */
internal class PastilleEtatApercu(
    binding: ActivityPreviewBinding,
    /** Le profil de rendu courant (dimensions du libellé du rendu valide). */
    private val profil: () -> ProfilPeripherique,
    /** L'orientation paysage courante (dimensions permutées). */
    private val paysage: () -> Boolean,
) {
    private val racine = binding.pastilleEtat
    private val texte = binding.pastilleEtatTexte
    private val point = binding.pastillePoint
    private val contexte = binding.root.context

    /** La pastille d'un ÉTAT du moteur (miroir `renderStatus`). */
    internal fun poser(etat: EtatPreviewXml) {
        when (etat) {
            is EtatPreviewXml.RenduValide -> {
                val appareil = profil()
                val dimensions =
                    contexte.getString(
                        R.string.preview_pastille_dimensions,
                        if (paysage()) appareil.hauteurDp else appareil.largeurDp,
                        if (paysage()) appareil.largeurDp else appareil.hauteurDp,
                    )
                texte.text =
                    contexte.resources.getQuantityString(
                        R.plurals.preview_pastille_rendu,
                        etat.nombreVues,
                        etat.nombreVues,
                        etat.dureeMs,
                        dimensions,
                    )
                teinter(R.attr.apercuSucces)
            }

            is EtatPreviewXml.ErreurRendu -> {
                texte.setText(R.string.preview_pastille_erreur)
                teinter(R.attr.apercuErreur)
            }

            EtatPreviewXml.EnEdition -> {
                texte.setText(R.string.preview_pastille_edition)
                // L'ambre de la maquette (`.status.edit` — jeton apercuAvert).
                teinter(R.attr.apercuAvert)
            }

            EtatPreviewXml.Inactif -> {
                racine.isVisible = false
            }
        }
    }

    /** La COULEUR du point de la pastille (jeton sémantique du thème). */
    private fun teinter(attribut: Int) {
        point.backgroundTintList = ColorStateList.valueOf(StyleVolet.couleur(contexte, attribut))
        racine.isVisible = true
    }
}
