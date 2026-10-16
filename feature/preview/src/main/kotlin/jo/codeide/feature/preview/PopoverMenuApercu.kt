package jo.codeide.feature.preview

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable

/**
 * Le contrôleur des POPPOVERS MENUS de l'aperçu (PE9c, patron
 * [jo.codeide.feature.editor.PopoverExplorateur] rendu module-local) :
 * une [PopupWindow] ANCRÉE portant un layout RÉEL gonflé par le
 * propriétaire — coque TRANSLUCIDE colorée (`fond_menu_apercu`), mesurée
 * puis posée sous l'ancre (retournement au-dessus si le bas dépasse),
 * clic extérieur = fermeture.
 *
 * Un seul menu à la fois (miroir § 10.5 : `montrer` masque le courant).
 *
 * Exemption detekt ciblée (règle 16) : LongMethod — `montrer` porte
 * l'algorithme d'ancrage d'un seul tenant (mesure, bornes,
 * retournement), le fractionner en cacherait la géométrie.
 */
@Suppress("LongMethod")
internal class PopoverMenuApercu(
    private val conteneur: ViewGroup,
) {
    /** Fenêtre courante — un seul menu à la fois. */
    private var fenetre: PopupWindow? = null

    /** Le menu est-il ouvert ? */
    public val estOuvert: Boolean
        get() = fenetre?.isShowing == true

    /** Ferme le menu (idempotent). */
    public fun masquer() {
        fenetre?.dismiss()
        fenetre = null
    }

    /**
     * Gonfle [layout], remplit le contenu via [remplir], mesure puis
     * affiche le menu ancré SOUS [ancre] — borné aux bords de l'écran,
     * retourné AU-DESSUS si le bas dépasse. La largeur suit
     * [largeurDp] (le contenu est posé EXACTLY).
     */
    public fun montrer(
        layout: Int,
        ancre: View,
        largeurDp: Int = LARGEUR_DEFAUT_DP,
        remplir: (View) -> Unit,
    ) {
        masquer()
        val contexte = conteneur.context
        val vue = LayoutInflater.from(contexte).inflate(layout, conteneur, false)
        remplir(vue)

        // Mesure explicite : la hauteur décide du retournement.
        val dp = contexte.resources.displayMetrics.density
        val largeur = (largeurDp * dp).toInt()
        vue.measure(
            android.view.View.MeasureSpec
                .makeMeasureSpec(largeur, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.UNSPECIFIED,
        )
        val hauteur = vue.measuredHeight
        val ecranHauteur = contexte.resources.displayMetrics.heightPixels
        val ecranLargeur = contexte.resources.displayMetrics.widthPixels

        // Position ÉCRAN de l'ancre (la PopupWindow se pose en écran).
        val position = IntArray(2)
        ancre.getLocationOnScreen(position)
        val marge = (MARGE_ECRAN_DP * dp).toInt()
        val posX = position[0].coerceIn(marge, (ecranLargeur - largeur - marge).coerceAtLeast(marge))
        val sous = position[1] + ancre.height + (DECALAGE_SOUS_DP * dp).toInt()
        val posY =
            (
                if (sous + hauteur > ecranHauteur - marge) {
                    position[1] - hauteur - (RETOURNEMENT_DP * dp).toInt()
                } else {
                    sous
                }
            ).coerceAtLeast(marge)

        // Fenêtre : focusable pour Échap/retour, clic extérieur = fermeture.
        val popup =
            PopupWindow(vue, largeur, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                elevation = (ELEVATION_DP * dp).toFloat()
                @Suppress("ClickableViewAccessibility") // Intercepteur : aucun clic ici, fermeture extérieure.
                setTouchInterceptor { _, evenement ->
                    if (evenement.action == MotionEvent.ACTION_OUTSIDE) {
                        masquer()
                        true
                    } else {
                        false
                    }
                }
            }
        fenetre = popup

        // Apparition : léger fondu-échelle vers le bas de l'ancre.
        vue.pivotY = 0f
        vue.scaleX = ECHELLE_APPARITION
        vue.scaleY = ECHELLE_APPARITION
        vue.alpha = 0f
        popup.showAtLocation(conteneur, Gravity.NO_GRAVITY, posX, posY)
        vue
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(DUREE_APPARITION_MS)
            .start()
    }

    private companion object {
        /** Largeur par défaut du menu, en dp (miroir les popovers de l'éditeur). */
        private const val LARGEUR_DEFAUT_DP: Int = 264

        /** Marge aux bords de l'écran, en dp. */
        private const val MARGE_ECRAN_DP: Int = 8

        /** Retrait sous l'ancre, en dp. */
        private const val DECALAGE_SOUS_DP: Int = 4

        /** Retrait au-dessus de l'ancre (retournement), en dp. */
        private const val RETOURNEMENT_DP: Int = 8

        /** Élévation de la fenêtre (miroir `.pop` — ombre de la PopupWindow). */
        private const val ELEVATION_DP: Int = 8

        /** Échelle de départ de l'apparition. */
        private const val ECHELLE_APPARITION: Float = 0.94f

        /** Durée de l'apparition, en ms (miroir 0,15 s de l'explorateur). */
        private const val DUREE_APPARITION_MS: Long = 150
    }
}
