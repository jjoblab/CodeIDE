package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as RAppcompat
import com.google.android.material.R as RMaterial

/**
 * Barre de symboles au-dessus du clavier (v0.32.3, ADR 0054) —
 * transposition de la `SymbolBarView` de la bibliothèque code-editor
 * (`view/chrome/SymbolBarView.java`, « à la CodeAssist »).
 *
 * Touches épinglées (Tab, //, ↑, ↓, Dup) + rangée défilante de
 * symboles de code : saisir une accolade ou déplacer une ligne sans
 * fermer l'IME. La barre vit sous l'en-tête du panneau inférieur et
 * **n'apparaît que quand le clavier virtuel est visible** (l'hôte la
 * pilote).
 *
 * Détail décisif repris de la bibliothèque : les touches utilisent
 * `onTouchEvent` brut (PAS `setOnClickListener`) — une vue cliquable
 * prendrait le focus, l'éditeur le perdrait et l'IME se fermerait.
 * Le toucher retourne `true` dès `ACTION_DOWN`, `performClick()` est
 * appelé sur `ACTION_UP` pour l'accessibilité, et l'écouteur agit.
 */
internal class BarreSymbolesEditeur
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : HorizontalScrollView(context, attrs) {
        /** Réactions de l'hôte : insertion d'un symbole ou action nommée. */
        internal interface Ecouteur {
            fun surSymbole(symbole: String)

            fun surAction(identifiant: String)
        }

        private var ecouteur: Ecouteur? = null
        private val densite = resources.displayMetrics.density
        private val rangee =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        init {
            isHorizontalScrollBarEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.barre_symboles_cd)
            addView(
                rangee,
                LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
            construireTouches()
        }

        /** Branche les réactions de l'hôte (insertion / actions). */
        fun definirEcouteur(nouveau: Ecouteur?) {
            ecouteur = nouveau
        }

        /** Touches épinglées puis symboles défilants, séparés d'un filet. */
        private fun construireTouches() {
            TOUCHES_EPINGLEES.forEachIndexed { index, libelle ->
                rangee.addView(Touche(context, libelle, ACTIONS_EPINGLEES[index], epinglee = true))
            }
            rangee.addView(Filet(context))
            SYMBOLES.forEach { symbole ->
                rangee.addView(Touche(context, symbole, symbole, epinglee = false))
            }
        }

        /** Une touche : libellé, identifiant d'action, rendu et toucher brut. */
        private inner class Touche(
            contexte: Context,
            private val libelle: String,
            private val identifiant: String,
            private val epinglee: Boolean,
        ) : View(contexte) {
            private val peinture =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize =
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            TAILLE_TEXTE_SP,
                            resources.displayMetrics,
                        )
                    textAlign = Paint.Align.CENTER
                }
            private val peintureFond = Paint()
            private var enfoncee = false

            init {
                minimumWidth = (LARGEUR_TOUCHE_DP * densite).toInt()
                contentDescription = libelle
                layoutParams =
                    LinearLayout.LayoutParams(
                        (LARGEUR_TOUCHE_DP * densite).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (enfoncee) {
                    peintureFond.color =
                        ColorUtils.setAlphaComponent(
                            MaterialColors.getColor(this, RAppcompat.attr.colorPrimary),
                            ALPHA_ENFONCEE,
                        )
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), peintureFond)
                }
                peinture.color =
                    if (epinglee) {
                        MaterialColors.getColor(this, RAppcompat.attr.colorPrimary)
                    } else {
                        MaterialColors.getColor(this, RMaterial.attr.colorOnSurfaceVariant)
                    }
                val x = width / 2f
                val y = height / 2f - (peinture.ascent() + peinture.descent()) / 2f
                canvas.drawText(libelle, x, y, peinture)
            }

            override fun onTouchEvent(evenement: MotionEvent): Boolean =
                when (evenement.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        enfoncee = true
                        invalidate()
                        // Consommé SANS réclamer le focus : l'éditeur garde
                        // le sien, l'IME reste ouvert.
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        enfoncee = false
                        invalidate()
                        performClick()
                        val hote = ecouteur
                        if (hote != null) {
                            if (epinglee) hote.surAction(identifiant) else hote.surSymbole(identifiant)
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        enfoncee = false
                        invalidate()
                        true
                    }

                    else -> {
                        super.onTouchEvent(evenement)
                    }
                }

            /** Accessibilité et lint : le clic est bien déclenché sur UP. */
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }

        /** Filet vertical entre les touches épinglées et les symboles. */
        private class Filet(
            contexte: Context,
        ) : View(contexte) {
            private val peinture = Paint()
            private val densite = contexte.resources.displayMetrics.density

            init {
                layoutParams =
                    LinearLayout.LayoutParams(
                        (LARGEUR_FILET_DP * densite).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
                setPadding(
                    (ESPACEMENT_FILET_DP * densite).toInt(),
                    0,
                    (ESPACEMENT_FILET_DP * densite).toInt(),
                    0,
                )
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                peinture.color = MaterialColors.getColor(this, RMaterial.attr.colorOutlineVariant)
                peinture.strokeWidth = EPAISSEUR_FILET_DP * densite
                val milieu = width / 2f
                val marge = HAUTEUR_FILET_MARGE_DP * densite
                canvas.drawLine(milieu, marge, milieu, height - marge, peinture)
            }
        }

        private companion object {
            /** Touches épinglées (SymbolBarView de la bibliothèque : même jeu). */
            val TOUCHES_EPINGLEES = listOf("Tab", "//", "↑", "↓", "Dup")

            /** Actions des touches épinglées. */
            val ACTIONS_EPINGLEES = listOf("tab", "comment", "move_up", "move_down", "duplicate")

            /** Symboles défilants (jeu complet de la bibliothèque). */
            val SYMBOLES =
                listOf(
                    "{",
                    "}",
                    "(",
                    ")",
                    ";",
                    "=",
                    ".",
                    ",",
                    "\"",
                    "'",
                    ":",
                    "<",
                    ">",
                    "/",
                    "*",
                    "[",
                    "]",
                    "+",
                    "-",
                    "&",
                    "|",
                    "!",
                    "?",
                    "@",
                    "#",
                    "_",
                    "%",
                    "\\",
                )

            /** Hauteur de la barre (bibliothèque : 38 dp — 44 ici pour
             *  la cible tactile minimale). */
            const val HAUTEUR_DP = 44

            /** Largeur minimale d'une touche. */
            const val LARGEUR_TOUCHE_DP = 44

            /** Taille du libellé des touches. */
            const val TAILLE_TEXTE_SP = 14f

            /** Alpha du fond d'une touche enfoncée (accent 12 %). */
            const val ALPHA_ENFONCEE = 31

            /** Largeur du filet séparateur. */
            const val LARGEUR_FILET_DP = 1

            /** Épaisseur du trait du filet. */
            const val EPAISSEUR_FILET_DP = 1f

            /** Respiration horizontale autour du filet. */
            const val ESPACEMENT_FILET_DP = 8

            /** Marge verticale du trait du filet. */
            const val HAUTEUR_FILET_MARGE_DP = 10
        }
    }
