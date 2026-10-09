package jo.codeide.feature.editor

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.view.children
import androidx.drawerlayout.widget.DrawerLayout

/**
 * Tiroir qui pousse la zone centrale (mission Tiroir Effets E2, ADR 0093).
 *
 * Sous-classe de [DrawerLayout] qui translate le conteneur de contenu
 * désigné (par [idContenu]) quand le tiroir glisse. Voile transparent.
 *
 * Comportement « FULL » (facteur 0,95) : un liseré du contenu reste
 * visible et sert de zone pour refermer.
 *
 * En mode permanent (grand écran, ADR 0026), **aucune translation** —
 * il suffit de passer `facteur = 0f` via [setFacteur].
 *
 * Réécriture (pas copie) inspirée du comportement d'AndroidIDE
 * `ContentTranslatingDrawerLayout` (GPL-3.0).
 *
 * La poignée de redimensionnement ne vit PAS ici (v0.80.3, ADR 0100) :
 * son calque (`conteneur_poignee`) est un **sibling** superposé dans le
 * FrameLayout racine du layout. Un enfant de contenu plein écran dans
 * ce DrawerLayout ferait intercepter TOUTES les touches du tiroir par
 * `onInterceptTouchEvent` (tiroir ouvert, `findTopChildUnder` +
 * `isContentView` → `interceptForTap`).
 */
internal class TiroirPoussantLayout
    @JvmOverloads
    constructor(
        contexte: Context,
        attributs: AttributeSet? = null,
    ) : DrawerLayout(contexte, attributs) {
        /** Id de la vue à translater quand le tiroir s'ouvre. */
        var idContenu: Int = 0

        /** Facteur de translation (0 = désactivé, 0,95 = FULL). */
        private var facteur: Float = FACTEUR_DEFAUT

        /** Active ou désactive la translation (mode permanent = 0f). */
        fun setFacteur(facteur: Float) {
            this.facteur = facteur
            if (facteur <= 0f) {
                findViewById<View>(idContenu)?.translationX = 0f
            }
        }

        /**
         * Recalcule la translation du contenu pour la largeur COURANTE du
         * tiroir (v0.80.1, retour utilisateur) : un redimensionnement par
         * la poignée ⋮ alors que le tiroir est OUVERT ne passe PAS par
         * [onDrawerSlide] — la zone centrale restait poussée à l'ANCIENNE
         * largeur, son bord dérivait sous le tiroir (ou découvrait une
         * bande vide). Appelée à chaque trame du glissement et de
         * l'animation d'aimant, elle applique exactement la formule de
         * [onDrawerSlide] : largeur du tiroir × facteur.
         */
        fun reevaluerTranslation() {
            val contenu =
                if (idContenu == 0) {
                    null
                } else {
                    findViewById<View>(idContenu)
                } ?: return
            val vueTiroir = trouverTiroir()
            if (vueTiroir == null || facteur <= 0f || !isDrawerOpen(vueTiroir)) {
                contenu.translationX = 0f
                return
            }
            val translation = vueTiroir.width * facteur
            val lp = vueTiroir.layoutParams as LayoutParams
            val graviteStart = lp.gravity == android.view.Gravity.START
            contenu.translationX = if (graviteStart) translation else -translation
        }

        /** Le tiroir (enfant de gravité START ou END) de ce layout. */
        private fun trouverTiroir(): View? =
            children.firstOrNull { vue ->
                (vue.layoutParams as? LayoutParams)?.gravity?.let { gravite ->
                    gravite == android.view.Gravity.START || gravite == android.view.Gravity.END
                } == true
            }

        init {
            addDrawerListener(
                object : DrawerLayout.DrawerListener {
                    override fun onDrawerSlide(
                        vueTiroir: View,
                        slideOffset: Float,
                    ) {
                        if (facteur <= 0f || idContenu == 0) return
                        val contenu = findViewById<View>(idContenu) ?: return
                        val largeurTiroir = vueTiroir.width.toFloat()
                        val translation = largeurTiroir * slideOffset * facteur
                        val lp = vueTiroir.layoutParams as LayoutParams
                        val graviteStart = lp.gravity == android.view.Gravity.START
                        contenu.translationX = if (graviteStart) translation else -translation
                    }

                    override fun onDrawerOpened(vueTiroir: View) = Unit

                    override fun onDrawerClosed(vueTiroir: View) {
                        if (idContenu == 0) return
                        findViewById<View>(idContenu)?.translationX = 0f
                    }

                    override fun onDrawerStateChanged(nouvelEtat: Int) = Unit
                },
            )
        }

        private companion object {
            /** Facteur FULL par défaut (un liseré reste visible pour refermer). */
            const val FACTEUR_DEFAUT = 0.95f
        }
    }
