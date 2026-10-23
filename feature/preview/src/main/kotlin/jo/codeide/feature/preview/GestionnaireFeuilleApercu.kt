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
 * visible pose problème » ; v0.173.0 — retour du même jour : « deux
 * niveaux pour expand à moitié puis total sous le appbar », « le canva
 * ne doit pas glisser pour disparaitre », « feuille expand + édition :
 * le rendu ne doit plus retomber dézoomé ») : l'activité lui DÈLÈGUE la
 * géométrie de la feuille — montage du comportement, insets (navigation
 * + CLAVIER, `adjustResize` au manifeste, miroir ADR 0118 de l'éditeur),
 * hauteur des volets et RETRAIT BORNÉ du canvas au bord courant de la
 * feuille.
 *
 * Décisions ([GeometrieFeuilleApercu] porte les formules pures) :
 * - DEUX NIVEAUX (v0.173.0) : `fitToContents` FAUX dès le montage —
 *   sinon le GLISSEMENT saute le demi-niveau (source Material 1.14.0,
 *   `onViewReleased` : `fitToContents` → `STATE_EXPANDED` direct) ; le
 *   niveau TOTAL est l'`expandedOffset` = bas de l'EN-TÊTE (posé quand
 *   ses mesures arrivent), le niveau MOITIÉ le `halfExpandedRatio` ;
 * - LE CANVAS SE RETIRE : le conteneur du rendu se termine au bord
 *   courant de la feuille + la marge du HUD — le rendu, le zoom
 *   (bas-droite) et la pastille « Rendu · N vues » (bas-gauche) ne
 *   passent JAMAIS sous la feuille, repliée comprise (l'ancienne
 *   `translationY` ne couvrait ni l'état half-expanded ni la barre de
 *   gestes) ; la même valeur porte la réserve de la bulle (miroir le
 *   `sh` unique de la maquette) ;
 * - LE CANVAS NE DISPARAIT JAMAIS (v0.173.0) : le retrait est BORNÉ à
 *   la hauteur du conteneur moins la bande minimale ([GeometrieFeuille
 *   Apercu.BANDE_CANVAS_MIN_DP]) — au niveau TOTAL la vue du rendu
 *   reste MESURÉE derrière la feuille (opaque) : éditer un attribut
 *   feuille déployée ne pulvérise PLUS le zoom, replier le fait
 *   réapparaître le rendu INTACT ;
 * - CLAVIER au niveau MOITIÉ (v0.173.0) : l'IME ouvre la feuille au
 *   TOTAL (les volets perdent l'IME, le bord BAS remonte au-dessus du
 *   clavier — prolongement v0.166.5) et le refermer rend la MOITIÉ ;
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

    /**
     * La feuille était-elle au niveau MOITIÉ quand le CLAVIER s'est ouvert
     * (v0.173.0) : fermer l'IME lui rend la MOITIÉ — la feuille ouverte au
     * TOTAL par le CLAVIER ne s'y accroche pas sans le vouloir.
     */
    private var moitieAvantClavier = false

    /** L'animation du retrait du canvas (une seule à la fois). */
    private var animateurRetrait: ValueAnimator? = null

    /**
     * Monte la feuille (fragment + comportement + callbacks) : le retrait
     * du canvas se pose aux états POSÉS (collapsed, half, expanded,
     * hidden) — pendant le glissement la feuille passe PAR-DESSUS le
     * canvas comme dans la maquette, puis le canvas rétrécit à l'arrivée
     * (miroir `applySheet` sur les toggles). La pose initiale suit le
     * premier layout (le bord de la feuille n'est posé qu'alors).
     *
     * v0.173.0 : `fitToContents` FAUX ICI, AVANT tout changement d'état
     * (la source Material convertit un demi-existant en total au passage
     * fitToContents → faux) ; l'`expandedOffset` attend les mesures de
     * l'en-tête ([majGeometrie]).
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
        // DEUX NIVEAUX : fitToContents FAUX, sinon le glissement vise le
        // TOTAL direct (Material 1.14.0, onViewReleased) et le demi-
        // niveau ne vit qu'aux changements d'état programmatiques.
        comportement.isFitToContents = false
        comportement.halfExpandedRatio = GeometrieFeuilleApercu.RATIO_DEMI_EXPANSION
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
     *
     * v0.173.0 — CLAVIER × NIVEAUX : l'IME qui s'OUVRE au niveau MOITIÉ
     * monte la feuille au TOTAL (les volets perdant l'IME, le bord BAS
     * remonte au-dessus du clavier — la moitié fixée par ratio ne le
     * connaît pas) ; l'IME qui se FERME rend la MOITIÉ si c'est bien lui
     * qui avait monté la feuille ([moitieAvantClavier]).
     */
    internal fun poserInsets(
        bas: Int,
        clavier: Int,
    ) {
        insetBas = bas
        val clavierSOuvre = clavier > 0 && insetClavier == 0
        val clavierSeFerme = clavier == 0 && insetClavier > 0
        insetClavier = clavier
        if (clavierSOuvre) {
            moitieAvantClavier =
                comportement?.state == BottomSheetBehavior.STATE_HALF_EXPANDED
            if (moitieAvantClavier) {
                comportement?.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        if (clavierSeFerme && moitieAvantClavier) {
            moitieAvantClavier = false
            if (comportement?.state == BottomSheetBehavior.STATE_EXPANDED) {
                comportement?.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }
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
        // v0.173.0 — le niveau TOTAL vise le BAS DE L'EN-TÊTE :
        // `expandedOffset` ne vit que fitToContents FAUX (posé au
        // montage) ; la source Material exige un offset ≤ demi-niveau —
        // l'en-tête (≈ 200 px) est toujours au-dessus de la moitié
        // d'écran (halfExpandedOffset = 0,5 × écran).
        comportement.expandedOffset = entete
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
     *
     * v0.173.0 — BORNÉ ([GeometrieFeuilleApercu.retraitCanvasPx]) : le
     * niveau TOTAL (feuille sous l'appbar) ne réduit plus le canvas à
     * néant — il garde sa bande minimale MESURÉE derrière la feuille : le
     * zoom survit aux éditions faites feuille déployée (« le canva ne
     * doit pas glisser pour disparaitre »).
     */
    private fun majRetrait() {
        val feuille = liaison.conteneurFeuille.getChildAt(0)
        val hauteurVisible = liaison.racinePreview.height - liaison.conteneurFeuille.top
        if (feuille == null || hauteurVisible <= 0 || feuille.height <= 0) return
        val cible =
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = hauteurVisible,
                margeHudPx = liaison.root.resources.getDimensionPixelSize(R.dimen.apercu_hud_marge),
                hauteurConteneurPx = liaison.conteneurRendu.height,
                bandeCanvasMinPx =
                    GeometrieFeuilleApercu.bandeCanvasMinPx(
                        densite = liaison.root.resources.displayMetrics.density,
                    ),
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
