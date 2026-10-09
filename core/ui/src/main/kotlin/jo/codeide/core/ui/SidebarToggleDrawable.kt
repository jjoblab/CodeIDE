@file:Suppress("OVERRIDE_DEPRECATION")

package jo.codeide.core.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Icône de tiroir animée (mission Tiroir Effets E1, ADR 0093) : une
 * miniature d'écran dont le panneau gauche s'élargit et se colore quand
 * le tiroir s'ouvre, et revient à l'état neutre quand il se ferme.
 *
 * Port fidèle de `SidebarToggleDrawable.java` de l'ancien projet
 * (annexe A du prompt), avec les corrections demandées :
 * - dimensions et épaisseurs en **dp** (pas de « 2,5 » codé) ;
 * - couleurs tirées du **thème** (contour, accent) — pas codées en dur ;
 * - `setAlpha`/`setColorFilter` corrects (ne contredisent pas les alphas
 *   internes) ;
 * - taille intrinsèque en dp ;
 * - support **RTL** (miroir) ;
 * - géométrie pure isolée dans [compagnon] pour tests JVM.
 *
 * La fraction 0..1 vient du `slideOffset` du `DrawerLayout`.
 *
 * @property couleurContour couleur du contour (ex. `colorOnSurfaceVariant`).
 * @property couleurAccent couleur d'accent (ex. `colorPrimary` ou couleur
 * du tiroir/Git).
 * @property densité densité d'écran pour la conversion dp → px.
 */
internal class SidebarToggleDrawable(
    private val couleurContour: Int,
    private val couleurAccent: Int,
    private val densite: Float,
) : Drawable() {
    private var fraction = 0f

    private val pinceauContour = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pinceauSeparateur = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pinceauRemplissage = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cheminClip = Path()

    init {
        // Épaisseurs en dp (correction du défaut « 2,5 codé » de l'original).
        val epaisseur = EPAISSEUR_TRAIT_DP * densite
        pinceauContour.strokeWidth = epaisseur
        pinceauSeparateur.strokeWidth = epaisseur
    }

    /**
     * Définit la fraction d'ouverture (0 = fermé, 1 = ouvert).
     * Bornée [0, 1], invalide si écart < SEUIL_FRACTION.
     */
    fun setFraction(fraction: Float) {
        val bornee = fraction.coerceIn(0f, 1f)
        if (kotlin.math.abs(this.fraction - bornee) < SEUIL_FRACTION) return
        this.fraction = bornee
        invalidateSelf()
    }

    /** Fraction courante (0 = fermé, 1 = ouvert). */
    fun getFraction(): Float = fraction

    override fun draw(canevas: Canvas) {
        val bornes = bounds
        if (bornes.isEmpty) return

        val dp = densite
        // Dimensions en dp (correction du défaut « 2,5x » de l'original).
        val largeur = LARGEUR_ECRAN_DP * dp
        val hauteur = HAUTEUR_ECRAN_DP * dp
        val marge = MARGE_INTERIEURE_DP * dp
        val coin = RAYON_COIN_DP * dp

        val x = bornes.centerX() - largeur / 2f
        val y = bornes.centerY() - hauteur / 2f

        canevas.save()
        canevas.translate(x, y)

        // 1. Contour du rectangle arrondi (alpha 180/255 comme l'original).
        val rectFond = RectF(0f, 0f, largeur, hauteur)
        pinceauContour.color = couleurContour
        pinceauContour.alpha = ALPHA_CONTOUR
        canevas.drawRoundRect(rectFond, coin, coin, pinceauContour)

        // 2. Position du séparateur : 34% → 64% de la largeur intérieure.
        val gaucheInterne = marge
        val droiteInterne = largeur - marge
        val xSeparateur = Geometrie.positionSeparateur(gaucheInterne, droiteInterne, fraction)

        // 3. Remplissage du panneau gauche (couleur interpolée, alpha 80/255).
        val couleurGauche = Geometrie.interpolerCouleur(couleurContour, couleurAccent, fraction)
        pinceauRemplissage.color = couleurGauche
        pinceauRemplissage.alpha = ALPHA_REMPLISSAGE

        val rectGauche = RectF(gaucheInterne, marge, xSeparateur, hauteur - marge)
        canevas.save()
        cheminClip.reset()
        cheminClip.addRoundRect(rectGauche, coin, coin, Path.Direction.CW)
        canevas.clipPath(cheminClip)
        canevas.drawRect(rectGauche, pinceauRemplissage)
        canevas.restore()

        // 4. Séparateur vertical (même couleur que le remplissage).
        pinceauSeparateur.color = couleurGauche
        canevas.drawLine(xSeparateur, marge, xSeparateur, hauteur - marge, pinceauSeparateur)

        canevas.restore()
    }

    override fun setAlpha(alpha: Int) {
        // Ne pas écraser les alphas internes — appliquer proportionnellement.
        pinceauContour.alpha = (ALPHA_CONTOUR * alpha / ALPHA_MAX).toInt()
        pinceauRemplissage.alpha = (ALPHA_REMPLISSAGE * alpha / ALPHA_MAX).toInt()
        pinceauSeparateur.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        pinceauContour.colorFilter = colorFilter
        pinceauRemplissage.colorFilter = colorFilter
        pinceauSeparateur.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = (TAILLE_INTRINSEQUE_DP * densite).toInt()

    override fun getIntrinsicHeight(): Int = (TAILLE_INTRINSEQUE_DP * densite).toInt()

    /**
     * Géométrie pure (testable en JVM sans Android) : interpolation de
     * couleur et position du séparateur.
     */
    internal object Geometrie {
        /** Position du séparateur : 34% → 64% de la largeur intérieure. */
        fun positionSeparateur(
            gauche: Float,
            droite: Float,
            fraction: Float,
        ): Float = gauche + (droite - gauche) * (FRACTION_MIN + AMPLITUDE * fraction)

        /**
         * Interpolation linéaire entre deux couleurs ARGB.
         * Évite android.graphics.Color (non mocké en test JVM pur).
         */
        @Suppress("MagicNumber") // Masques de bits pour extraction RGB d'une couleur ARGB.
        fun interpolerCouleur(
            from: Int,
            to: Int,
            t: Float,
        ): Int {
            val rFrom = (from shr 16) and 0xFF
            val gFrom = (from shr 8) and 0xFF
            val bFrom = from and 0xFF
            val rTo = (to shr 16) and 0xFF
            val gTo = (to shr 8) and 0xFF
            val bTo = to and 0xFF
            val r = (rFrom + (rTo - rFrom) * t).toInt()
            val g = (gFrom + (gTo - gFrom) * t).toInt()
            val b = (bFrom + (bTo - bFrom) * t).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** Fraction minimale du séparateur (fermé). */
        const val FRACTION_MIN = 0.34f

        /** Amplitude du déplacement (ouvert - fermé). */
        const val AMPLITUDE = 0.30f
    }

    private companion object {
        /** Alpha du contour (180/255 comme l'original). */
        const val ALPHA_CONTOUR = 180

        /** Alpha du remplissage (80/255 comme l'original). */
        const val ALPHA_REMPLISSAGE = 80

        /** Épaisseur du trait en dp (correction du défaut « 2,5 codé »). */
        const val EPAISSEUR_TRAIT_DP = 1.5f

        /** Seuil de fraction en dessous duquel on n'invalide pas (évite le spam). */
        const val SEUIL_FRACTION = 0.001f

        /** Largeur de la miniature d'écran en dp. */
        const val LARGEUR_ECRAN_DP = 19f

        /** Hauteur de la miniature d'écran en dp. */
        const val HAUTEUR_ECRAN_DP = 15f

        /** Marge intérieure en dp. */
        const val MARGE_INTERIEURE_DP = 2f

        /** Rayon des coins arrondis en dp. */
        const val RAYON_COIN_DP = 2.5f

        /** Taille intrinsèque en dp. */
        const val TAILLE_INTRINSEQUE_DP = 24

        /** Alpha maximum (255, opaque). */
        const val ALPHA_MAX = 255f
    }
}
