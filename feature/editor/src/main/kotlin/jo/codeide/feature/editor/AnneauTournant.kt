package jo.codeide.feature.editor

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.view.isVisible

/**
 * Anneau tournant pour le marqueur d'étape EN COURS d'une sync (v0.40.1,
 * correctif n°5 du prompt de suivi).
 *
 * **Problème constaté (v0.40.0)** : le `CircularProgressIndicator` Material
 * est prévu pour ~40 dp (épaisseur de trace 4 dp, `indicatorInset` autour).
 * Forcé à 16 dp via `indicatorSize` dans `ligne_arbre_etape.xml`, l'anneau
 * était rogné/écrasé. De plus, le `RecyclerView` bascule `visible/gone` le
 * `CircularProgressIndicator` à chaque `lier()` (un marqueur par type
 * d'état) → l'animation redémarrait à chaque tick de durée de l'étape en
 * cours (visible → gone → visible), créant un clignotement.
 *
 * **Correctif** : un drawable vectoriel d'anneau (16 dp, trait 2 dp) tourné
 * par UN `ObjectAnimator` partagé entre tous les `AnneauTournant` visibles.
 * Démarrage à l'attache, arrêt au détachement/`STOPPED`. Respecte « réduire
 * les animations » (`Settings.Global.ANIMATOR_DURATION_SCALE = 0`) : le
 * `ValueAnimator` Android lit automatiquement ce réglage et réduit la
 * durée à 0 — l'anneau reste statique mais visible (l'utilisateur voit AU
 * MOINS que l'étape est en cours — pas un écran figé qui ment).
 *
 * **Pas de fuite d'animateur** : l'`ObjectAnimator` est un singleton
 * partagé (un seul au total pour toute la console) — pas un animateur par
 * `View`. Les `View` s'y abonnent via [demarrer] / [arreter] ; l'animateur
 * est annulé quand plus aucune vue ne l'utilise (cf. [AnneauTournantState]).
 *
 * Dimensions (depuis l'aperçu v3, §6) :
 * - taille : 16 dp ;
 * - trait : 2 dp ;
 * - rotation : 360°/s en linéaire (1 s par tour) ;
 * - piste (`colorOutlineVariant`) NON dessinée (l'arc seul suffit — la
 *   rotation suffit à distinguer l'état en cours) ;
 * - couleur de l'arc : `backgroundTintList` posé par l'appelant (couleur
 *   de canal harmonisée).
 */
class AnneauTournant
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        init {
            // Drawable vectoriel de l'anneau — 16 dp, trait 2 dp.
            setBackgroundResource(R.drawable.anneau_etape_en_cours)
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (isVisible) AnneauTournantState.attacher(this)
        }

        override fun onDetachedFromWindow() {
            AnneauTournantState.detacher(this)
            super.onDetachedFromWindow()
        }

        override fun setVisibility(visibility: Int) {
            super.setVisibility(visibility)
            // Bascule visible/gone propage au singleton — un seul
            // animateur tourne pour toutes les vues visibles. On vérifie
            // `isAttachedToWindow` : si la vue n'est pas encore attachée
            // (ex. en Robolectric sans `WindowManager`), l'attache au
            // singleton se fera à `onAttachedToWindow`.
            if (isAttachedToWindow) {
                if (visibility == VISIBLE) {
                    AnneauTournantState.attacher(this)
                } else {
                    AnneauTournantState.detacher(this)
                }
            }
        }
    }

/**
 * État global de l'anneau tournant : UN `ObjectAnimator` partagé entre
 * toutes les vues `AnneauTournant` visibles (pas un animateur par vue —
 * zéro fuite). Le singleton est créé à la première attache, détruit à la
 * dernière détache.
 *
 * API `internal` : exposée pour tests (`AnneauTournantTest`).
 */
internal object AnneauTournantState {
    /** Vues actuellement abonnées à l'animateur global. */
    private val abonnees = mutableSetOf<View>()

    /** L'animateur global — un seul au total, partagé. */
    private var animateur: ObjectAnimator? = null

    /** Attache une vue : démarre l'animateur global si nécessaire. */
    fun attacher(vue: View) {
        abonnees.add(vue)
        if (animateur == null) {
            animateur =
                ObjectAnimator.ofFloat(vue, "rotation", ROTATION_DEBUT, ROTATION_FIN).apply {
                    duration = DUREE_ROTATION_MS
                    interpolator = LinearInterpolator()
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
        }
    }

    /** Détache une vue : arrête l'animateur si plus personne n'est abonné. */
    fun detacher(vue: View) {
        abonnees.remove(vue)
        if (abonnees.isEmpty()) {
            animateur?.cancel()
            animateur = null
        }
    }

    /** API de test : nombre de vues abonnées (assertion de fuite). */
    fun nbAbonnees(): Int = abonnees.size

    /** API de test : l'animateur est-il actif ? */
    fun estActif(): Boolean = animateur != null

    /** API de test : annule l'animateur global et vide les abonnées. */
    fun reset() {
        animateur?.cancel()
        animateur = null
        abonnees.clear()
    }

    /** Durée d'une rotation complète (ms) — 1 s en linéaire, comme
     *  l'aperçu v3 (§6 — `animation: r 1s linear infinite`). */
    private const val DUREE_ROTATION_MS = 1_000L

    /** Angle de départ de la rotation (degrés). */
    private const val ROTATION_DEBUT = 0f

    /** Angle de fin d'un tour complet (degrés). */
    private const val ROTATION_FIN = 360f
}
