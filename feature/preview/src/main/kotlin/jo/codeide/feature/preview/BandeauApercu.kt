package jo.codeide.feature.preview

import android.widget.TextView
import androidx.core.view.isVisible
import jo.codeide.feature.preview.databinding.ActivityPreviewBinding
import jo.codeide.preview.xml.EtatPreviewXml

/**
 * Le BANDEAU de l'écran d'aperçu (PE11i, D6 — miroir `.banner`, ADR 0134)
 * : une VUE en haut du conteneur de rendu — message UNE ligne, bouton
 * « Ligne n » (aller à la source, si la ligne est connue) ou « Réessayer »
 * (processus `:preview` arrêté), et « Détails » ↔ « Réduire » (déplie le
 * message) ; la BARRE D'ÉDITION descend de 64 dp sous un bandeau visible
 * (miroir `.edit.shift`), le dernier rendu valide reste affiché dessous.
 *
 * Correctif PE11k (bug rapporté par le propriétaire : « j'ai appuyé sur
 * réessayer, réduire, rien ne fonctionne ») : le bandeau du processus
 * arrêté est le miroir EXACT de la maquette (`renderStatus`, cas crash) —
 * message COMPLET sur deux lignes (pas de clip) et « Réessayer » SEUL ;
 * « Détails »/« Réduire » n'existe que pour l'erreur de rendu (le pliage
 * du message d'arrêt paraissait « ne rien faire » : le message tient en
 * une ligne, seul le libellé basculait).
 *
 * Chrome PUR (patron [PopoverMenuApercu]) : aucun état métier — l'activité
 * pose l'état du moteur, le bandeau l'affiche.
 */
internal class BandeauApercu(
    binding: ActivityPreviewBinding,
) {
    private val racine = binding.banniereRendu
    private val message = binding.banniereMessage
    private val bouton = binding.banniereLigne
    private val details = binding.banniereDetails
    private val barreEdition = binding.barreEdition
    private val ressources = binding.root.resources
    private val decalageEdition = ressources.getDimension(R.dimen.apercu_edition_decalage)
    private var deplie = false
    private var actionBouton: (() -> Unit)? = null

    init {
        details.setOnClickListener { basculerDetails() }
        bouton.setOnClickListener { actionBouton?.invoke() }
    }

    /** L'erreur de RENDU : message + « Ligne n » (si la ligne est connue) + « Détails ». */
    fun montrerErreur(
        erreur: EtatPreviewXml.ErreurRendu,
        allerALigne: (ligne: Int) -> Unit,
    ) {
        val ligne = erreur.ligne
        montrer(
            texte = erreur.message,
            libelleBouton = ligne?.let { ressources.getString(R.string.preview_bandeau_ligne, it) },
            actionBouton = ligne?.let { l -> { allerALigne(l) } },
            pliable = true,
        )
    }

    /** Le processus `:preview` ARRÊTÉ : message honnête COMPLET (miroir
     * `renderStatus` cas crash : deux lignes, AUCUN pliage) + « Réessayer »
     * SEUL — pas de « Détails »/« Réduire » sur ce bandeau (la maquette ne
     * les porte que sur l'erreur de rendu). */
    fun montrerProcessusArrete(
        texte: CharSequence,
        reessayer: () -> Unit,
    ) {
        montrer(
            texte = texte,
            libelleBouton = ressources.getString(R.string.preview_bandeau_reessayer),
            actionBouton = reessayer,
            pliable = false,
        )
    }

    /** Masque le bandeau et REMONTE la barre d'édition. */
    fun masquer() {
        deplie = false
        actionBouton = null
        racine.isVisible = false
        barreEdition.translationY = REPLI_EDITION
    }

    /** Pose le bandeau visible et descend la barre d'édition (miroir `.edit.shift`).
     *
     * @param pliable vrai (erreur de rendu) : message replié à UNE ligne +
     *        bouton « Détails »/« Réduire » ; faux (processus arrêté) :
     *        message complet, AUCUN bouton de pliage (miroir la maquette). */
    private fun montrer(
        texte: CharSequence,
        libelleBouton: String?,
        actionBouton: (() -> Unit)?,
        pliable: Boolean,
    ) {
        message.text = texte
        this.actionBouton = actionBouton
        bouton.isVisible = libelleBouton != null
        bouton.text = libelleBouton.orEmpty()
        details.isVisible = pliable
        if (pliable) {
            appliquerPliage()
        } else {
            deplie = false
            message.maxLines = LIGNES_DEPLIE
        }
        racine.isVisible = true
        barreEdition.translationY = decalageEdition
    }

    /** « Détails » ↔ « Réduire » : déplie ou replie le message (une ligne par défaut). */
    private fun basculerDetails() {
        deplie = !deplie
        appliquerPliage()
    }

    /** Le pliage courant : une ligne (replié) ou message complet (déplié). */
    private fun appliquerPliage() {
        message.maxLines = if (deplie) LIGNES_DEPLIE else LIGNES_REPLIE
        details.setText(
            if (deplie) R.string.preview_bandeau_reduire else R.string.preview_bandeau_details,
        )
    }

    private companion object {
        /** Lignes du message REPLIÉ (une ligne, miroir `.banner span`). */
        private const val LIGNES_REPLIE: Int = 1

        /** Lignes du message DÉPLIÉ (message complet). */
        private const val LIGNES_DEPLIE: Int = 6

        /** Translation de repli de la barre d'édition (pas de bandeau). */
        private const val REPLI_EDITION: Float = 0f
    }
}

/**
 * Le TOAST de l'aperçu (PE11i, D6 — miroir `.toast`) : la VUE
 * `toast_apercu` en haut-centre (fond inversé) remplace TOUT Toast
 * système — fondu 200 ms, reste 2,4 s puis s'efface en fondu.
 */
internal class ToastApercu(
    private val vue: TextView,
) {
    /** Affiche un message (remplace et repouille le toast précédent). */
    fun montrer(texte: CharSequence) {
        vue.removeCallbacks(effacement)
        vue.animate().cancel()
        vue.text = texte
        vue.isVisible = true
        vue.alpha = ALPHA_INVISIBLE
        vue
            .animate()
            .alpha(ALPHA_OPAQUE)
            .setDuration(DUREE_FONDU_MS)
            .start()
        vue.postDelayed(effacement, DUREE_AFFICHEE_MS)
    }

    /** L'effacement en fondu (repouillé à chaque nouveau toast). */
    private val effacement: Runnable =
        Runnable {
            vue
                .animate()
                .alpha(ALPHA_INVISIBLE)
                .setDuration(DUREE_FONDU_MS)
                .withEndAction { vue.isVisible = false }
                .start()
        }

    private companion object {
        /** Fondu d'apparition/disparition, en ms (miroir 0,2 s). */
        private const val DUREE_FONDU_MS: Long = 200L

        /** Durée d'affichage, en ms (miroir 2,4 s). */
        private const val DUREE_AFFICHEE_MS: Long = 2400L

        /** Opacité opaque. */
        private const val ALPHA_OPAQUE: Float = 1f

        /** Opacité invisible. */
        private const val ALPHA_INVISIBLE: Float = 0f
    }
}
