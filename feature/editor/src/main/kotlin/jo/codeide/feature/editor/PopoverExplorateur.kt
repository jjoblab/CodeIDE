package jo.codeide.feature.editor

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable

/**
 * Popover maison de l'explorateur (étape 31, § 10,
 * `docs/EXPLORATEUR_V2.md`) : **jamais de menu système** — coque
 * dessinée par l'application dans une [PopupWindow], ancrée à la
 * position exacte du doigt (appui long) ou du pointeur.
 *
 * Algorithme d'ancrage (§ 10.2, reproduit à l'identique) :
 *
 * - cible horizontale `x − 30 dp`, bornée dans l'écran avec 6 dp de
 *   marge ; `y + 16 dp` sous le doigt ;
 * - si le bas dépasserait l'écran → **retournement au-dessus** :
 *   `y − hauteur − 14 dp` ;
 * - la flèche 12 dp suit le point horizontalement, bornée entre 20 dp
 *   et `largeur − 20 dp` du bord gauche du popover ; l'origine du zoom
 *   d'apparition (`scale .94 → 1`, 0,15 s) suit la flèche ;
 * - fermeture par clic extérieur, touche retour (Échap), ou action
 *   exécutée — un seul popover à la fois.
 *
 * La vue racine des layouts de popover (`popover_actions_noeud`,
 * `popover_deplacer`, `popover_supprimer`, `popover_legende`) porte la
 * flèche `fleche_popover` et la coque `coque_popover` — cette classe
 * positionne l'une et anime l'autre.
 */
@Suppress("LongMethod") // Exemption detekt ciblée (règle 16) : montrer() porte l'algorithme § 10.2 d'un seul tenant.
internal class PopoverExplorateur(
    private val conteneur: ViewGroup,
) {
    /** Fenêtre courante — un seul popover à la fois (§ 10.5). */
    private var fenetre: PopupWindow? = null

    /** Le popover est-il ouvert ? */
    val estOuvert: Boolean get() = fenetre?.isShowing == true

    /**
     * Gonfle [layout], remplit le contenu via [remplir], mesure puis
     * affiche le popover ancré à ([x], [y]) — coordonnées **écran** du
     * point de contact (§ 10.2).
     */
    fun montrer(
        layout: Int,
        x: Int,
        y: Int,
        remplir: (View) -> Unit,
    ) {
        masquer()
        val contexte = conteneur.context
        val vue = LayoutInflater.from(contexte).inflate(layout, conteneur, false)
        remplir(vue)

        // Mesure explicite : la hauteur décide du retournement (§ 10.2).
        val dp = contexte.resources.displayMetrics.density
        val largeur = (LARGEUR_DP * dp).toInt()
        vue.measure(
            View.MeasureSpec.makeMeasureSpec(largeur, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        val hauteur = vue.measuredHeight
        val ecranHauteur = contexte.resources.displayMetrics.heightPixels
        val ecranLargeur = contexte.resources.displayMetrics.widthPixels

        // Cible horizontale : x − 30 dp, bornée (6 dp de marge).
        val marge = (MARGE_ECRAN_DP * dp).toInt()
        val decalage = (DECALAGE_CIBLE_DP * dp).toInt()
        val posX = (x - decalage).coerceIn(marge, (ecranLargeur - largeur - marge).coerceAtLeast(marge))

        // Position verticale : y + 16 dp, retournement si le bas dépasse.
        val sous = y + (DECALAGE_SOUS_DP * dp).toInt()
        val retourne = sous + hauteur > ecranHauteur - marge
        val posY =
            (if (retourne) y - hauteur - (RETOURNEMENT_DP * dp).toInt() else sous)
                .coerceAtLeast(marge)

        // Flèche : suit le point horizontalement, bornée 20 dp ↔ largeur−20.
        val fleche = vue.findViewById<View>(R.id.fleche_popover)
        val demiFleche = (DEMI_FLECHE_DP * dp).toInt()
        val xFleche =
            (x - posX).coerceIn(
                (BORNE_FLECHE_DP * dp).toInt(),
                (largeur - BORNE_FLECHE_DP * dp).toInt().coerceAtLeast((BORNE_FLECHE_DP * dp).toInt()),
            )
        if (fleche != null) {
            fleche.translationX = (xFleche - demiFleche).toFloat()
            fleche.translationY =
                if (retourne) {
                    (hauteur - demiFleche).toFloat()
                } else {
                    (-demiFleche).toFloat()
                }
        }

        // Fenêtre : focusable pour Échap/retour + clic extérieur.
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

        // Apparition : scale .94 → 1 + fondu, 0,15 s, origine = flèche.
        vue.pivotX = xFleche.toFloat()
        vue.pivotY = if (retourne) hauteur.toFloat() else 0f
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
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Referme le popover courant (action exécutée, Échap, bascule…). */
    fun masquer() {
        fenetre?.dismiss()
        fenetre = null
    }

    private companion object {
        /** Largeur de coque du popover (§ 10.1). */
        const val LARGEUR_DP = 258

        /** Marge d'écran autour du popover (§ 10.2). */
        const val MARGE_ECRAN_DP = 6

        /** Décalage horizontal de la cible sous le doigt (§ 10.2). */
        const val DECALAGE_CIBLE_DP = 30

        /** Décalage vertical sous le doigt (§ 10.2). */
        const val DECALAGE_SOUS_DP = 16

        /** Recul du retournement au-dessus du doigt (§ 10.2). */
        const val RETOURNEMENT_DP = 14

        /** Demi-flèche (12 dp, § 10.1). */
        const val DEMI_FLECHE_DP = 6

        /** Borne inférieure de la flèche depuis le bord gauche (§ 10.2). */
        const val BORNE_FLECHE_DP = 20

        /** Élévation de la coque (ombre § 10.1). */
        const val ELEVATION_DP = 12

        /** Échelle initiale de l'apparition (§ 10.1). */
        const val ECHELLE_APPARITION = 0.94f

        /** Durée de l'apparition (§ 16). */
        const val DUREE_APPARITION_MS = 150L
    }
}
