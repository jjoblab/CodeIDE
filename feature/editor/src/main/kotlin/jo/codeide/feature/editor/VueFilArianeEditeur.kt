package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as RAppcompat
import com.google.android.material.R as RMaterial

/**
 * Fil d'Ariane de l'éditeur (v0.32.3, ADR 0054) — « dossier › … ›
 * fichier › classe › fonction », au-dessus de la zone d'édition.
 *
 * Transposition de la `BreadcrumbBar` de la bibliothèque code-editor
 * (`view/chrome/BreadcrumbBar.java`) : barre de 28 dp, texte monospace
 * 12 sp, chevrons « › », dernier segment en gras. Deux écarts assumés :
 * les couleurs suivent le **thème Material** de l'application (jour et
 * nuit) au lieu de constantes sombres, et la vue se mesure à la largeur
 * de son contenu pour défiler dans un `HorizontalScrollView` hôte (le
 * fil d'un vrai projet dépasse vite l'écran).
 *
 * Les segments viennent de [SymbolesEnglobants] et du chemin relatif
 * de l'onglet actif ; la vue ne sait rien du caret ni des sessions —
 * son hôte appelle [definirSegments].
 */
internal class VueFilArianeEditeur
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** Rôle d'un segment : pilote sa couleur (le dernier gagne
         *  toujours l'accent). */
        internal enum class Role {
            CHEMIN,
            FICHIER,
            SYMBOLE,
        }

        /** Un segment affichable. */
        internal data class Segment(
            val texte: String,
            val role: Role,
        )

        /** Segments affichés (chemin + fichier + symboles englobants). */
        private var segments: List<Segment> = emptyList()

        private val densite = resources.displayMetrics.density

        private val peintureTexte =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize =
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        TAILLE_TEXTE_SP,
                        resources.displayMetrics,
                    )
                typeface = Typeface.MONOSPACE
            }
        private val peintureChevron =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize =
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        TAILLE_CHEVRON_SP,
                        resources.displayMetrics,
                    )
                typeface = Typeface.MONOSPACE
            }
        private val peintureFond = Paint()
        private val peintureFilet = Paint()

        /** Segments à afficher ; déclenche re-mesure et redessin. */
        fun definirSegments(nouveaux: List<Segment>) {
            val propres = nouveaux.filter { it.texte.isNotBlank() }
            if (propres == segments) return
            segments = propres
            requestLayout()
            invalidate()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val hauteur = (HAUTEUR_DP * densite).toInt()
            val largeurContenu = mesurerLargeur().toInt()
            val largeur =
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                    MeasureSpec.getSize(widthMeasureSpec)
                } else {
                    largeurContenu
                }
            setMeasuredDimension(
                largeur.coerceAtLeast(largeurContenu),
                resolveSize(hauteur, heightMeasureSpec),
            )
        }

        /** Largeur de la chaîne complète, chevrons et marges compris. */
        private fun mesurerLargeur(): Float {
            var largeur = MARGE_HORIZONTALE_DP * densite
            segments.forEachIndexed { index, segment ->
                largeur += peintureTexte.measureText(segment.texte)
                if (index > 0) {
                    largeur += peintureChevron.measureText(CHEVRON) + MARGE_CHEVRON_DP * densite
                }
            }
            return largeur + MARGE_HORIZONTALE_DP * densite
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Fond discret + filet bas : la barre se lit comme un chrome
            // de l'éditeur, pas comme un panneau.
            peintureFond.color = MaterialColors.getColor(this, RMaterial.attr.colorSurfaceVariant)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), peintureFond)
            peintureFilet.color = MaterialColors.getColor(this, RMaterial.attr.colorOutlineVariant)
            peintureFilet.strokeWidth = EPAISSEUR_FILET_DP * densite
            canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, peintureFilet)

            if (segments.isEmpty()) return
            val couleurAccent = MaterialColors.getColor(this, RAppcompat.attr.colorPrimary)
            val couleurFichier = MaterialColors.getColor(this, RMaterial.attr.colorOnSurface)
            val couleurChemin = MaterialColors.getColor(this, RMaterial.attr.colorOnSurfaceVariant)
            val couleurChevron = MaterialColors.getColor(this, RMaterial.attr.colorOutline)

            val indexDernier = segments.lastIndex
            var x = MARGE_HORIZONTALE_DP * densite
            val ligneBase = baselineCentree()
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    peintureChevron.color = couleurChevron
                    canvas.drawText(CHEVRON, x, ligneBase, peintureChevron)
                    x += peintureChevron.measureText(CHEVRON) + MARGE_CHEVRON_DP * densite
                }
                val estDernier = index == indexDernier
                peintureTexte.color =
                    when {
                        estDernier -> couleurAccent
                        segment.role == Role.FICHIER -> couleurFichier
                        else -> couleurChemin
                    }
                peintureTexte.typeface =
                    if (estDernier) {
                        Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    } else {
                        Typeface.MONOSPACE
                    }
                canvas.drawText(segment.texte, x, ligneBase, peintureTexte)
                x += peintureTexte.measureText(segment.texte)
            }
        }

        /** Ligne de base du texte, centrée verticalement dans la barre. */
        private fun baselineCentree(): Float {
            val milieu = height / 2f
            return milieu - (peintureTexte.ascent() + peintureTexte.descent()) / 2f
        }

        private companion object {
            /** Hauteur de la barre (BreadCrumbBar de la bibliothèque : 28 dp). */
            const val HAUTEUR_DP = 28

            /** Taille du texte des segments (bibliothèque : 12 sp). */
            const val TAILLE_TEXTE_SP = 12f

            /** Taille des chevrons « › » (bibliothèque : 10 sp). */
            const val TAILLE_CHEVRON_SP = 10f

            /** Séparateur de segments. */
            const val CHEVRON = "\u203A"

            /** Marge horizontale de début et de fin (bibliothèque : 12 dp). */
            const val MARGE_HORIZONTALE_DP = 12

            /** Respiration entre un chevron et son segment. */
            const val MARGE_CHEVRON_DP = 6

            /** Épaisseur du filet bas. */
            const val EPAISSEUR_FILET_DP = 1f
        }
    }
