package jo.codeide.feature.preview

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withClip

/**
 * DAMIER DE LA PASTILLE COULEUR (PE11e — miroir du `DamierDrawable`
 * que la référence nomme dans son commentaire de `controle_couleur.xml` :
 * « damier (DamierDrawable, tuile 10 dp, apercu_damier_a/b) » ; CSS
 * `repeating-conic-gradient(#bbb 0 25%, #fff 0 50%) 0 0/10px 10px`).
 *
 * La transparence se lit à TRAVERS : la pastille superpose le damier
 * (cette couche), la TEINTE (`apercu_pastille_teinte` tintée au liage)
 * puis le CADRE (`apercu_pastille_couleur_cadre`) — une couleur
 * translucide ou `transparent` laisse voir le damier seul (miroir
 * `linear-gradient(var(--c))` posé SUR `repeating-conic-gradient`).
 *
 * Ce n'est PAS un fond construit par GradientDrawable (ADR 0127-0129 :
 * ce motif-là est irréalisable en XML — la tuile répétée exige un
 * dessin par case, comme le damier 8 dp du moteur
 * [jo.codeide.preview.xml.VuePreviewXml], même idiome).
 *
 * Clippé au RAYON de la pastille (miroir `border-radius: 8px` de
 * `.sw2` : le damier ne sort pas des coins arrondis).
 */
internal class DamierPastille(resources: Resources) : Drawable() {
    /** La tuile en pixels écran (`apercu_damier_pastille` — 10 dp). */
    private val cote: Int = resources.getDimensionPixelSize(R.dimen.apercu_damier_pastille)

    /** La case A (miroir `#bbb` — `apercu_damier_a`). */
    @ColorInt
    private val couleurA: Int = ResourcesCompat.getColor(resources, R.color.apercu_damier_a, null)

    /** La case B (miroir `#fff` — `apercu_damier_b`). */
    @ColorInt
    private val couleurB: Int = ResourcesCompat.getColor(resources, R.color.apercu_damier_b, null)

    /** Le rayon du clip (miroir `.sw2` — `apercu_pastille_couleur_rayon`). */
    private val rayon: Float = resources.getDimension(R.dimen.apercu_pastille_couleur_rayon)

    /** Le chemin du clip arrondi, recalculé aux bornes. */
    private val clip = Path()

    /** La peinture des cases (pleine, sans antialias). */
    private val peinture =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }

    override fun draw(canevas: Canvas) {
        if (cote <= 0 || bounds.isEmpty) return
        val zone = RectF(bounds)
        clip.reset()
        clip.addRoundRect(zone, rayon, rayon, Path.Direction.CW)
        canevas.withClip(clip) {
            peinture.color = couleurA
            canevas.drawRect(zone, peinture)
            peinture.color = couleurB
            val pas = cote.toFloat()
            var rangee = 0
            var haut = zone.top
            while (haut < zone.bottom) {
                var colonne = rangee
                var gauche = zone.left
                while (gauche < zone.right) {
                    if (colonne % PAIR == 1) {
                        canevas.drawRect(
                            gauche,
                            haut,
                            minOf(gauche + pas, zone.right),
                            minOf(haut + pas, zone.bottom),
                            peinture,
                        )
                    }
                    gauche += pas
                    colonne++
                }
                haut += pas
                rangee++
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        // Le damier est TOUJOURS opaque (les deux cases couvrent tout) :
        // la translucidité vit sur la COUCHE TEINTE au-dessus.
    }

    override fun setColorFilter(filtre: ColorFilter?) {
        // Sans objet : les cases portent leurs propres jetons.
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        /** Parité de la case (une case sur deux en couleur B). */
        private const val PAIR: Int = 2
    }
}
