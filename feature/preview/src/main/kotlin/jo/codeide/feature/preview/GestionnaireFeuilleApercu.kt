package jo.codeide.feature.preview

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import jo.codeide.feature.preview.databinding.ActivityPreviewBinding

/**
 * Le GESTIONNAIRE de la FEUILLE inférieure de l'aperçu (v0.166.5 — retour
 * propriétaire du 2026-10-22 : « l'expansion doit aussi arriver jusqu'au
 * appbar » ; « le bottomsheet même en collapse ne cache plus aussi la
 * partie bottom du preview » ; « la feuille expanded avec le clavier
 * visible pose problème ») : l'activité lui DÈLÈGUE la géométrie de la
 * feuille — montage du comportement, insets (navigation + CLAVIER,
 * `adjustResize` au manifeste, miroir ADR 0118 de l'éditeur), hauteur des
 * volets et RETRAIT DU CANVAS au bord courant de la feuille.
 *
 * Décisions ([GeometrieFeuilleApercu] porte les formules pures) :
 * - DÉPLOYÉE JUSQU'À L'APPBAR : les volets remplissent l'espace sous
 *   l'en-tête moins le clavier — la feuille `wrap_content` égalant cet
 *   espace, l'état `expanded` (fit-to-contents) cale son bord haut SUR le
 *   bas de l'en-tête, clavier exclu ;
 * - LE CANVAS SE RETIRE : le conteneur du rendu se termine au bord
 *   courant de la feuille + la marge du HUD — le rendu, le zoom
 *   (bas-droite) et la pastille « Rendu · N vues » (bas-gauche) ne
 *   passent JAMAIS sous la feuille, repliée comprise (l'ancienne
 *   `translationY` ne couvrait ni l'état half-expanded ni la barre de
 *   gestes) ; la même valeur porte la réserve de la bulle (miroir le
 *   `sh` unique de la maquette) ;
 * - la pose s'ANIME 250 ms (miroir `transition: bottom .25s ease`).
 */
internal class GestionnaireFeuilleApercu(
    private val liaison: ActivityPreviewBinding,
    private val gestion: FragmentManager,
) {
    /** Le comportement de la feuille (posé au montage). */
    private var comportement: BottomSheetBehavior<*>? = null

    /** Le dernier inset BAS reçu (barre de navigation). */
    private var insetBas = 0

    /** Le dernier inset du CLAVIER reçu (ime — 0 fermé). */
    private var insetClavier = 0

    /** L'animation du retrait du canvas (une seule à la fois). */
    private var animateurRetrait: ValueAnimator? = null

    /**
     * Monte la feuille (fragment + comportement + callbacks) : le retrait
     * du canvas se pose aux états POSÉS (collapsed, expanded, half,
     * hidden) — pendant le glissement la feuille passe PAR-DESSUS le
     * canvas comme dans la maquette, puis le canvas rétrécit à l'arrivée
     * (miroir `applySheet` sur les toggles). La pose initiale suit le
     * premier layout (le bord de la feuille n'est posé qu'alors).
     */
    internal fun monter() {
        gestion
            .beginTransaction()
            .replace(R.id.conteneur_feuille, FeuilleApercuFragment())
            .commit()
        gestion.executePendingTransactions()
        // Le comportement vit sur le CONTENEUR (enfant direct du
        // CoordinatorLayout, app:layout_behavior du XML) — PAS sur la
        // vue du fragment (enfant du conteneur : from() planterait).
        val comportement = BottomSheetBehavior.from(liaison.conteneurFeuille)
        this.comportement = comportement
        comportement.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    vue: View,
                    etat: Int,
                ) {
                    if (etat != BottomSheetBehavior.STATE_DRAGGING &&
                        etat != BottomSheetBehavior.STATE_SETTLING
                    ) {
                        majRetrait()
                    }
                }

                override fun onSlide(
                    vue: View,
                    decalage: Float,
                ) = Unit
            },
        )
        liaison.racinePreview.post { majRetrait() }
    }

    /**
     * Les insets reçus par l'activité (UN SEUL écouteur d'insets à
     * l'écran) : la barre de navigation et le CLAVIER redéclenchent la
     * géométrie — le peek couvre les gestes, la hauteur des volets suit
     * l'IME.
     */
    internal fun poserInsets(
        bas: Int,
        clavier: Int,
    ) {
        insetBas = bas
        insetClavier = clavier
        majGeometrie()
    }

    /**
     * La GÉOMÉTRIE de la feuille : le peek (repliée VISIBLE au-dessus de
     * la barre de navigation), le padding bas du contenu de la feuille
     * (poignée et onglets jamais sous les gestes) et la HAUTEUR DES
     * VOLETS qui remplit l'espace sous l'en-tête moins le clavier. Les
     * hauteurs ne se mesurent qu'après le premier layout : la passe se
     * re-poste une fois alors.
     */
    private fun majGeometrie() {
        val comportement = comportement
        val feuille = liaison.conteneurFeuille.getChildAt(0)
        if (comportement == null || feuille == null) return
        comportement.peekHeight =
            GeometrieFeuilleApercu.peekPx(
                densite = liaison.root.resources.displayMetrics.density,
                insetNavigationPx = insetBas,
            )
        feuille.updatePadding(bottom = insetBas)
        val entete = liaison.entetePreview.height
        val grab = feuille.findViewById<View>(R.id.grab_feuille)?.height ?: 0
        val onglets = feuille.findViewById<View>(R.id.onglets_feuille)?.height ?: 0
        val volets = feuille.findViewById<View>(R.id.conteneur_volets)
        if (!mesuresPretes(volets, entete, grab + onglets)) {
            // Premier passage (avant le layout) : rejouer après les mesures.
            liaison.racinePreview.post { majGeometrie() }
            return
        }
        volets?.updateLayoutParams {
            height =
                GeometrieFeuilleApercu.hauteurVoletsPx(
                    hauteurEcranPx = liaison.racinePreview.height,
                    hauteurEntetePx = entete,
                    insetImePx = insetClavier,
                    chromeFeuillePx = grab + onglets,
                    insetNavigationPx = insetBas,
                )
        }
        // La feuille s'est déplacée (volets retaillés, IME ouvert/fermé) :
        // le bord lu au layout suivant alimente le retrait du canvas.
        liaison.racinePreview.post { majRetrait() }
    }

    /**
     * Les mesures nécessaires à la géométrie sont-elles posées ? (L'écran,
     * l'en-tête et le chrome de la feuille ne se mesurent qu'après le
     * premier layout.)
     */
    private fun mesuresPretes(
        volets: View?,
        entetePx: Int,
        chromeFeuillePx: Int,
    ): Boolean {
        val ecranMesure = liaison.racinePreview.height > 0 && entetePx > 0
        return volets != null && chromeFeuillePx > 0 && ecranMesure
    }

    /**
     * Le RETRAIT BAS du canvas : le conteneur du rendu se termine au bord
     * courant de la feuille + la marge du HUD ; la même valeur porte la
     * réserve de la bulle (miroir le `sh` de la maquette). Inchangé, la
     * pose est immédiate ; sinon elle s'anime.
     */
    private fun majRetrait() {
        val feuille = liaison.conteneurFeuille.getChildAt(0)
        val hauteurVisible = liaison.racinePreview.height - liaison.conteneurFeuille.top
        if (feuille == null || hauteurVisible <= 0 || feuille.height <= 0) return
        val cible =
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = hauteurVisible,
                margeHudPx = liaison.root.resources.getDimensionPixelSize(R.dimen.apercu_hud_marge),
            )
        if (liaison.conteneurRendu.paddingBottom == cible) {
            liaison.bullePalette.reserveBas = cible
            liaison.bullePalette.reposer()
        } else {
            animerRetrait(cible)
        }
    }

    /** La pose ANIMÉE du retrait (250 ms, décélération — miroir `.25s ease`). */
    private fun animerRetrait(cible: Int) {
        animateurRetrait?.cancel()
        animateurRetrait =
            ValueAnimator.ofInt(liaison.conteneurRendu.paddingBottom, cible).apply {
                duration = DUREE_RETRAIT_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    val retrait = animation.animatedValue as Int
                    liaison.conteneurRendu.updatePadding(bottom = retrait)
                    // La bulle suit la même réserve (miroir `sh` = `h`).
                    liaison.bullePalette.reserveBas = retrait
                    liaison.bullePalette.reposer()
                }
                start()
            }
    }

    private companion object {
        /** Durée du retrait (miroir `transition: bottom .25s ease`). */
        private const val DUREE_RETRAIT_MS: Long = 250
    }
}
