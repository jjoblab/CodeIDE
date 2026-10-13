@file:Suppress("MagicNumber")

package jo.codeide.feature.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import jo.codeide.core.ui.couleurConteneurPrimaire
import jo.codeide.core.ui.couleurPrimaire
import jo.codeide.core.ui.couleurSurSurfaceDiscret
import jo.codeide.core.ui.R as RUi

/**
 * Pastille d'indexation de la toolbar (Phase B prompt 2, ADR 0117 §4.4 —
 * lot B5). Vue custom légère qui remplace la ligne `barre_etat_lsp` (qui
 * consommait de la hauteur dans la colonne centrale — incident v0.100.2).
 *
 * États (miroir de [PresentationIndexation.EtatPastilleIndexation]) :
 *
 * | État | Rendu |
 * |---|---|
 * | [Etat.INITIAL] | invisible (pas de pastille) |
 * | [Etat.CONSTRUCTION] | anneau accent en rotation + pourcentage |
 * | [Etat.PRET] | coche verte discrète |
 * | [Etat.PARTIEL] | triangle d'avertissement ambre |
 * | [Etat.ERREUR] | croix rouge |
 *
 * La pastille est CLIQUABLE : un appui ouvre le panneau de détail (bottom
 * sheet, lot B6). La cible tactile est de 48 dp (règle d'accessibilité).
 *
 * Patron : [VuePointEtat] — vue custom sans surcharge de `onMeasure`
 * (taille fixe), dessin via `Canvas` + `Paint`, animation via
 * `ValueAnimator`.
 */
internal class VuePointIndexation
    @JvmOverloads
    constructor(
        contexte: Context,
        attributs: AttributeSet? = null,
    ) : View(contexte, attributs) {
        /** État rendu (miroir de [PresentationIndexation.EtatPastilleIndexation]). */
        internal enum class Etat {
            /** Avant tout sync : pas de pastille. */
            INITIAL,

            /** Construction en cours : anneau accent en rotation. */
            CONSTRUCTION,

            /** Index à jour : coche verte discrète. */
            PRET,

            /** Index partiel : triangle d'avertissement ambre. */
            PARTIEL,

            /** Index en erreur : croix rouge. */
            ERREUR,
        }

        private var etat = Etat.INITIAL

        /** Pourcentage de progression (0-100) pendant la construction. */
        private var pourcentage: Int? = null

        /** Angle courant de l'anneau en rotation (construction). */
        private var angleRotation = 0f

        /** Animation de rotation de l'anneau (construction). */
        private val animationRotation: ValueAnimator =
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = DUREE_ROTATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    angleRotation = animation.animatedValue as Float
                    invalidate()
                }
            }

        // Pinceaux de dessin (initialisés paresseusement).
        private val peintureAnneau = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val peintureFondAnneau = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val peintureCoche = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val peintureTriangle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE }
        private val peintureCroix = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rectArc = RectF()
        private val pathCoche = Path()
        private val pathTriangle = Path()

        init {
            // Cible tactile 48 dp (règle d'accessibilité — cible ≥ 48 dp).
            val densite = resources.displayMetrics.density
            val taille = (TAILLE_CIBLE_TACTUELLE_DP * densite).toInt()
            layoutParams = android.view.ViewGroup.LayoutParams(taille, taille)
            isClickable = true
            isFocusable = true
            contentDescription = contexte.getString(R.string.editor_indexation_pastille_cd_pret)
        }

        /**
         * Programme l'état de la pastille.
         *
         * @param etat état visuel.
         * @param pourcentage pourcentage de progression (0-100) pendant la
         *        construction, `null` sinon.
         */
        internal fun programmer(
            etat: Etat,
            pourcentage: Int? = null,
        ) {
            if (this.etat == etat && this.pourcentage == pourcentage) return
            this.etat = etat
            this.pourcentage = pourcentage
            visibility = if (etat == Etat.INITIAL) GONE else VISIBLE
            contentDescription = libelleContentDescription(etat, pourcentage)
            if (etat == Etat.CONSTRUCTION) {
                animationRotation.start()
            } else {
                animationRotation.cancel()
                angleRotation = 0f
            }
            invalidate()
        }

        private fun libelleContentDescription(
            etat: Etat,
            pourcentage: Int?,
        ): String =
            when (etat) {
                Etat.INITIAL -> {
                    ""
                }

                Etat.CONSTRUCTION -> {
                    if (pourcentage != null) {
                        context.getString(R.string.editor_indexation_pastille_cd_construction, pourcentage)
                    } else {
                        context.getString(R.string.editor_indexation_construction_titre)
                    }
                }

                Etat.PRET -> {
                    context.getString(R.string.editor_indexation_pastille_cd_pret)
                }

                Etat.PARTIEL -> {
                    context.getString(R.string.editor_indexation_pastille_cd_partiel)
                }

                Etat.ERREUR -> {
                    context.getString(R.string.editor_indexation_pastille_cd_erreur)
                }
            }

        override fun onDraw(canvas: Canvas) {
            if (etat == Etat.INITIAL) return
            val cx = width / 2f
            val cy = height / 2f
            val rayon = minOf(width, height) / 2f - MARGE_PINCEAU_PX
            when (etat) {
                Etat.CONSTRUCTION -> {
                    dessinerAnneau(canvas, cx, cy, rayon)
                }

                Etat.PRET -> {
                    dessinerCoche(canvas, cx, cy, rayon)
                }

                Etat.PARTIEL -> {
                    dessinerTriangle(canvas, cx, cy, rayon)
                }

                Etat.ERREUR -> {
                    dessinerCroix(canvas, cx, cy, rayon)
                }

                Etat.INITIAL -> {}
            }
        }

        private fun dessinerAnneau(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            rayon: Float,
        ) {
            val couleurAccent = context.couleurPrimaire()
            val couleurFond = context.couleurSurSurfaceDiscret()
            peintureAnneau.color = couleurAccent
            peintureAnneau.strokeWidth = EPAISSEUR_ANNEAU_PX
            peintureFondAnneau.color = couleurFond
            peintureFondAnneau.strokeWidth = EPAISSEUR_ANNEAU_PX
            rectArc.set(cx - rayon, cy - rayon, cx + rayon, cy + rayon)
            // Anneau de fond (gris discret).
            canvas.drawArc(rectArc, 0f, 360f, false, peintureFondAnneau)
            // Arc accent en rotation (couvre 270° sur 360°).
            canvas.drawArc(rectArc, angleRotation, ARC_CONSTRUCTION_DEGRES, false, peintureAnneau)
        }

        private fun dessinerCoche(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            rayon: Float,
        ) {
            val couleurSucces = ContextCompat.getColor(context, jo.codeide.core.ui.R.color.codeide_succes)
            peintureCoche.color = couleurSucces
            peintureCoche.strokeWidth = EPAISSEUR_ICONE_PX
            peintureCoche.strokeCap = Paint.Cap.ROUND
            peintureCoche.strokeJoin = Paint.Join.ROUND
            pathCoche.reset()
            // Coche : trois points formant un V allongé.
            val decalage = rayon * RATIO_COCHE
            pathCoche.moveTo(cx - decalage, cy + decalage * 0.1f)
            pathCoche.lineTo(cx - decalage * 0.2f, cy + decalage)
            pathCoche.lineTo(cx + decalage, cy - decalage * 0.8f)
            canvas.drawPath(pathCoche, peintureCoche)
        }

        private fun dessinerTriangle(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            rayon: Float,
        ) {
            val couleurWarn = ContextCompat.getColor(context, jo.codeide.core.ui.R.color.codeide_journal_warn)
            peintureTriangle.color = couleurWarn
            peintureTriangle.strokeWidth = EPAISSEUR_ICONE_PX
            peintureTriangle.strokeJoin = Paint.Join.ROUND
            pathTriangle.reset()
            val decalage = rayon * RATIO_TRIANGLE
            pathTriangle.moveTo(cx, cy - decalage)
            pathTriangle.lineTo(cx + decalage, cy + decalage * 0.7f)
            pathTriangle.lineTo(cx - decalage, cy + decalage * 0.7f)
            pathTriangle.close()
            canvas.drawPath(pathTriangle, peintureTriangle)
            // Point d'exclamation.
            peintureTriangle.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy - decalage * 0.1f, EPAISSEUR_ICONE_PX * 0.6f, peintureTriangle)
            canvas.drawCircle(cx, cy + decalage * 0.4f, EPAISSEUR_ICONE_PX * 0.6f, peintureTriangle)
        }

        private fun dessinerCroix(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            rayon: Float,
        ) {
            val couleurErreur = ContextCompat.getColor(context, jo.codeide.core.ui.R.color.codeide_error)
            peintureCroix.color = couleurErreur
            peintureCroix.strokeWidth = EPAISSEUR_ICONE_PX
            peintureCroix.strokeCap = Paint.Cap.ROUND
            val decalage = rayon * RATIO_CROIX
            canvas.drawLine(cx - decalage, cy - decalage, cx + decalage, cy + decalage, peintureCroix)
            canvas.drawLine(cx + decalage, cy - decalage, cx - decalage, cy + decalage, peintureCroix)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animationRotation.cancel()
        }

        private companion object {
            /** Durée d'une rotation complète de l'anneau (ms). */
            private const val DUREE_ROTATION_MS: Long = 1_200L

            /** Taille de la cible tactile (dp) — règle d'accessibilité ≥ 48 dp. */
            private const val TAILLE_CIBLE_TACTUELLE_DP: Int = 48

            /** Épaisseur de l'anneau (px). */
            private const val EPAISSEUR_ANNEAU_PX: Float = 6f

            /** Épaisseur des icônes (coche, triangle, croix) en px. */
            private const val EPAISSEUR_ICONE_PX: Float = 5f

            /** Arc couvert par l'anneau en rotation (degrés sur 360°). */
            private const val ARC_CONSTRUCTION_DEGRES: Float = 270f

            /** Marge entre le bord de la vue et le dessin (px). */
            private const val MARGE_PINCEAU_PX: Float = 8f

            /** Ratio de la taille de la coche par rapport au rayon. */
            private const val RATIO_COCHE: Float = 0.55f

            /** Ratio de la taille du triangle par rapport au rayon. */
            private const val RATIO_TRIANGLE: Float = 0.7f

            /** Ratio de la taille de la croix par rapport au rayon. */
            private const val RATIO_CROIX: Float = 0.5f
        }
    }
