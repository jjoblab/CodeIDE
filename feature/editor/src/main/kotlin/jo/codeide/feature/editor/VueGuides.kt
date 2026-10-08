package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import jo.codeide.core.ui.couleurBordureDiscrete

/**
 * Guides de l'arborescence (étape 31, § 6.2, spécification
 * `docs/EXPLORATEUR_V2.md`) : traits fins verticaux et coude horizontal
 * reliant chaque nœud à son parent — dessin programmatique, **aucune vue
 * imbriquée** (point d'attention § 19 : performance du RecyclerView).
 *
 * Géométrie reprise de la maquette (une imbrication = marge 10 + padding
 * 12, soit 22 dp par niveau) :
 *
 * - le contenu d'une ligne de profondeur *p* commence à `22 × p` dp ;
 * - pour chaque niveau *k* de 1 à *p*, un trait vertical 1 dp `guide`
 *   à `22 × k − 10` dp, pleine hauteur — raccourci à 17 dp pour le
 *   **dernier enfant** (notation `└`) ;
 * - un coude horizontal 1 dp `guide` de 10 dp de large à 16 dp du haut,
 *   reliant le trait vertical au début de la ligne.
 * - **B1** : un dossier déplié qui a des enfants dessine un trait
 *   vertical supplémentaire au niveau `profondeur + 1`, de sous le
 *   chevron (16 dp du haut) jusqu'au bas de la ligne — pour rejoindre
 *   sans coupure le trait du premier enfant.
 *
 * Le masque [programmer] (bit *k−1* = l'ancêtre de profondeur *k* est un
 * dernier enfant) masque les traits des niveaux ancestraux finis — le
 * trait d'un dernier enfant s'arrête 17 dp après son coude et ne
 * traverse pas son sous-arbre.
 *
 * @see VuePointEtat pour le point d'état des fichiers.
 */
internal class VueGuides
    @JvmOverloads
    constructor(
        contexte: Context,
        attributs: AttributeSet? = null,
    ) : View(contexte, attributs) {
        /** Profondeur de la ligne (0 = racine, aucun guide). */
        private var profondeur: Int = 0

        /** Bit *k−1* = ancêtre de profondeur *k* dernier enfant (trait masqué). */
        private var masqueAncetresDerniers: Int = 0

        /** La ligne est-elle le dernier enfant de son parent ? */
        private var dernierEnfant: Boolean = false

        /** B1 : la ligne est-elle un dossier déplié avec enfants ? */
        private var deplieAvecEnfants: Boolean = false

        private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
        private val couleurGuide = contexte.couleurBordureDiscrete()
        private val dp = contexte.resources.displayMetrics.density

        /**
         * Programme les guides de la ligne : [nouvelleProfondeur] de la
         * ligne, [nouveauDernier] raccourcit son trait propre à 17 dp,
         * [nouveauMasque] masque les traits ancestraux finis,
         * [nouveauDeplieAvecEnfants] (B1) trace un trait au niveau
         * `profondeur + 1` de sous le chevron au bas — pour relier le
         * dossier déplié au trait de son premier enfant.
         */
        fun programmer(
            nouvelleProfondeur: Int,
            nouveauDernier: Boolean,
            nouveauMasque: Int,
            nouveauDeplieAvecEnfants: Boolean = false,
        ) {
            profondeur = nouvelleProfondeur
            dernierEnfant = nouveauDernier
            masqueAncetresDerniers = nouveauMasque
            deplieAvecEnfants = nouveauDeplieAvecEnfants
            invalidate()
        }

        override fun onDraw(canevas: Canvas) {
            if (profondeur < 1 && !deplieAvecEnfants) return
            val dp = this.dp
            val trait = 1f * dp
            val haut = trait / 2f
            val bas = height.toFloat() - trait / 2f
            pinceau.color = couleurGuide

            // Niveau propre (k = profondeur) : pleine hauteur, ou 17 dp
            // pour un dernier enfant. (Pas dessiné pour la racine p=0.)
            if (profondeur >= 1) {
                val finPropre = if (dernierEnfant) haut + HAUTEUR_DERNIER_DP * dp else bas
                dessinerNiveau(canevas, profondeur, haut, minOf(finPropre, bas), trait)
            }

            // Niveaux ancestraux : pleine hauteur sauf ancêtre dernier
            // enfant (le bit correspondant est posé).
            for (k in 1 until profondeur) {
                if (masqueAncetresDerniers and (1 shl (k - 1)) != 0) continue
                dessinerNiveau(canevas, k, haut, bas, trait)
            }

            // B1 : dossier déplié avec enfants — trait vertical au niveau
            // profondeur + 1, de sous le chevron (16 dp) au bas, pour
            // rejoindre le trait du premier enfant sans coupure.
            if (deplieAvecEnfants) {
                val x = (INDENTATION_PAR_NIVEAU * (profondeur + 1) - DECALAGE_LIGNE) * dp
                val yDepart = HAUTEUR_COUDE * dp
                if (bas > yDepart) {
                    pinceau.strokeWidth = trait
                    canevas.drawLine(x, yDepart, x, bas, pinceau)
                }
            }
        }

        /** Dessine le trait vertical (haut → bas) et le coude du niveau [k]. */
        private fun dessinerNiveau(
            canevas: Canvas,
            k: Int,
            haut: Float,
            bas: Float,
            trait: Float,
        ) {
            val x = (INDENTATION_PAR_NIVEAU * k - DECALAGE_LIGNE) * dp
            if (bas > haut) {
                pinceau.strokeWidth = trait
                canevas.drawLine(x, haut, x, bas, pinceau)
            }
            // Coude horizontal (1 dp de haut, 10 dp de large) à 16 dp.
            val y = HAUTEUR_COUDE * dp
            canevas.drawLine(x, y, x + DECALAGE_LIGNE * dp, y, pinceau)
        }

        private companion object {
            /** Une imbrication = marge 10 + padding 12 (maquette § 6.2). */
            const val INDENTATION_PAR_NIVEAU = 22f

            /** Raccourci du trait d'un dernier enfant (§ 6.2 : 17 dp). */
            const val HAUTEUR_DERNIER_DP = 17f

            /** Distance du trait vertical au début de la ligne (10 dp). */
            const val DECALAGE_LIGNE = 10f

            /** Hauteur du coude depuis le haut de la ligne (16 dp). */
            const val HAUTEUR_COUDE = 16f
        }
    }
