package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.view.children
import androidx.core.view.isVisible
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
 */
internal class TiroirPoussantLayout
    @JvmOverloads
    constructor(
        contexte: Context,
        attributs: AttributeSet? = null,
    ) : DrawerLayout(contexte, attributs) {
        /** Id de la vue à translater quand le tiroir s'ouvre. */
        var idContenu: Int = 0

        /** Conteneur de la poignée de redimensionnement (v0.80.2) : dessiné
         *  HORS du rognage « contenu » de [DrawerLayout] — il chevauche
         *  volontairement le bord du tiroir (moitié tiroir, moitié zone
         *  centrale). */
        private var conteneurPoignee: View? = null

        /** Enregistre le conteneur de la poignée (enfant de contenu, plein
         *  écran, DERNIER enfant du layout — l'ordre des enfants le dessine
         *  au-dessus du tiroir et l'itération inverse des touches le sert
         *  avant lui, à condition que l'élévation du tiroir soit 0). */
        fun setConteneurPoignee(vue: View) {
            conteneurPoignee = vue
        }

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

        /**
         * Rognage volontairement BYPASSÉ pour le conteneur de la poignée
         * (v0.80.2) : [DrawerLayout.drawChild] rogne chaque enfant de
         * contenu à la frontière du tiroir opaque (`clipRect` — un contenu
         * ne dessine jamais SOUS un tiroir) ; la poignée de
         * redimensionnement est justement À CHEVAL sur cette frontière
         * (moitié sur le tiroir, moitié sur la zone centrale). On la
         * dessine donc directement, sans rognage ni nœud de rendu
         * intermédiaire (l'élévation du tiroir est posée à 0 par
         * l'activité : l'ordre des enfants suffit à la placer au-dessus).
         */
        override fun drawChild(
            canvas: Canvas,
            child: View,
            drawingTime: Long,
        ): Boolean {
            val poignee = conteneurPoignee
            if (poignee != null && child === poignee) {
                if (child.isVisible) {
                    child.draw(canvas)
                }
                return true
            }
            return super.drawChild(canvas, child, drawingTime)
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
