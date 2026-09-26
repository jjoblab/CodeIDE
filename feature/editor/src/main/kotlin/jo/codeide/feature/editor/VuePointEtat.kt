package jo.codeide.feature.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Point d'état des fichiers de l'arbre (étape 31, § 7, spécification
 * `docs/EXPLORATEUR_V2.md`) : disque de 7 dp, bordure 2 dp, dans la zone
 * du chevron des dossiers (20 dp).
 *
 * États par précédence croissante :
 *
 * | État | Rendu |
 * |---|---|
 * | [EtatPoint.DEFAUT] | bordure grise, fond transparent |
 * | [EtatPoint.OUVERT] | bordure verte, fond transparent (onglet inactif) |
 * | [EtatPoint.ACTIF] | bordure et fond verts + halo (onglet actif) |
 * | [EtatPoint.SELECTIONNE] | bordure et fond accent |
 * | [EtatPoint.SELECTION_ACTIF] | bordure accent, fond vert, anneau externe accent doux |
 *
 * La sélection **prime sur l'onglet pour la bordure**, mais le fond reste
 * vert si le fichier est celui de l'onglet actif (seul cas de fond vert
 * avec bordure bleue). Le changement d'état est animé (0,16 s, toutes
 * propriétés — interpolation de couleurs ici).
 */
internal class VuePointEtat
    @JvmOverloads
    constructor(
        contexte: Context,
        attributs: AttributeSet? = null,
    ) : View(contexte, attributs) {
        /** État rendu (§ 7). */
        internal enum class EtatPoint {
            /** Fichier non ouvert : bordure grise, fond transparent. */
            DEFAUT,

            /** Ouvert dans un onglet inactif : bordure verte, fond transparent. */
            OUVERT,

            /** Fichier de l'onglet actif : bordure + fond verts + halo. */
            ACTIF,

            /** Nœud sélectionné : bordure + fond accent. */
            SELECTIONNE,

            /** Sélectionné ET onglet actif : bordure accent, fond vert, anneau. */
            SELECTION_ACTIF,
        }

        private var etat = EtatPoint.DEFAUT

        /** Couleurs interpolées pendant la transition (0,16 s). */
        private var bordureCourante = couleur(EtatPoint.DEFAUT, Role.BORDURE)
        private var fondCourant = couleur(EtatPoint.DEFAUT, Role.FOND)

        private companion object {
            /** Rayon extérieur du disque (§ 7 : 7 dp de diamètre). */
            const val RAYON_EXTERIEUR_DP = 3.5f

            /** Épaisseur de la bordure (§ 7 : 2 dp). */
            const val EPAISSEUR_BORDURE_DP = 2f

            /** Épaisseur de l'anneau externe (sélection + actif, § 7 : 3 dp). */
            const val EPAISSEUR_ANNEAU_DP = 3f

            /** Débordement du halo de l'état actif (§ 7). */
            const val DEBORDEMENT_HALO_DP = 3.5f

            /** Épaisseur du trait du halo (§ 7). */
            const val EPAISSEUR_HALO_DP = 2f

            /** Durée du changement d'état (§ 16 : 0,16 s). */
            const val DUREE_CHANGEMENT_MS = 160L
        }

        private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dp = contexte.resources.displayMetrics.density
        private var animation: ValueAnimator? = null

        /** Programme l'état du point (animation 0,16 s au changement). */
        fun programmer(nouvelEtat: EtatPoint) {
            if (nouvelEtat == etat) return
            val ancien = etat
            etat = nouvelEtat
            animation?.cancel()
            animation =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = DUREE_CHANGEMENT_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animateur ->
                        val t = animateur.animatedValue as Float
                        bordureCourante =
                            androidx.core.graphics.ColorUtils.blendARGB(
                                couleur(ancien, Role.BORDURE),
                                couleur(nouvelEtat, Role.BORDURE),
                                t,
                            )
                        fondCourant =
                            androidx.core.graphics.ColorUtils.blendARGB(
                                couleur(ancien, Role.FOND),
                                couleur(nouvelEtat, Role.FOND),
                                t,
                            )
                        invalidate()
                    }
                    start()
                }
        }

        override fun onDraw(canevas: Canvas) {
            // Centre de la zone (20 dp), disque extérieur 7 dp + bordure.
            val cx = width / 2f
            val cy = height / 2f
            val rayonExterieur = RAYON_EXTERIEUR_DP * dp
            val bordure = EPAISSEUR_BORDURE_DP * dp

            // Anneau externe (sélection + actif) : 3 dp d'accent doux.
            if (etat == EtatPoint.SELECTION_ACTIF) {
                pinceau.style = Paint.Style.FILL
                pinceau.color =
                    ContextCompat.getColor(
                        context,
                        jo.codeide.core.ui.R.color.codeide_explorateur_accent_doux,
                    )
                canevas.drawCircle(cx, cy, rayonExterieur + EPAISSEUR_ANNEAU_DP * dp, pinceau)
            }

            // Disque : fond (rempli pour les états pleins).
            pinceau.style = Paint.Style.FILL
            pinceau.color = fondCourant
            canevas.drawCircle(cx, cy, rayonExterieur - bordure / 2f, pinceau)

            // Bordure 2 dp.
            pinceau.style = Paint.Style.STROKE
            pinceau.strokeWidth = bordure
            pinceau.color = bordureCourante
            canevas.drawCircle(cx, cy, rayonExterieur - bordure / 2f, pinceau)

            // Halo de l'état actif (débordement doux de 7 dp).
            if (etat == EtatPoint.ACTIF) {
                pinceau.style = Paint.Style.STROKE
                pinceau.strokeWidth = EPAISSEUR_HALO_DP * dp
                pinceau.color =
                    ContextCompat.getColor(
                        context,
                        jo.codeide.core.ui.R.color.codeide_explorateur_vert_fond_onglet,
                    )
                canevas.drawCircle(cx, cy, rayonExterieur + DEBORDEMENT_HALO_DP * dp, pinceau)
            }
        }

        /** Couleur stable d'un état (interpolation initiale). */
        private fun couleur(
            etat: EtatPoint,
            role: Role,
        ): Int =
            when (role) {
                Role.BORDURE -> {
                    when (etat) {
                        EtatPoint.DEFAUT -> {
                            jo.codeide.core.ui.R.color.codeide_explorateur_point_defaut
                        }

                        EtatPoint.OUVERT, EtatPoint.ACTIF -> {
                            jo.codeide.core.ui.R.color.codeide_explorateur_vert
                        }

                        EtatPoint.SELECTIONNE, EtatPoint.SELECTION_ACTIF -> {
                            jo.codeide.core.ui.R.color.codeide_explorateur_accent
                        }
                    }
                }

                Role.FOND -> {
                    when (etat) {
                        EtatPoint.DEFAUT, EtatPoint.OUVERT -> android.R.color.transparent
                        EtatPoint.ACTIF -> jo.codeide.core.ui.R.color.codeide_explorateur_vert
                        EtatPoint.SELECTIONNE -> jo.codeide.core.ui.R.color.codeide_explorateur_accent
                        EtatPoint.SELECTION_ACTIF -> jo.codeide.core.ui.R.color.codeide_explorateur_vert
                    }
                }
            }.let { ContextCompat.getColor(context, it) }

        /** Rôle de couleur demandé par l'interpolation. */
        private enum class Role {
            BORDURE,
            FOND,
        }
    }
