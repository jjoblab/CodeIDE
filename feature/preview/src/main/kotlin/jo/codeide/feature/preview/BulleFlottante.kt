package jo.codeide.feature.preview

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import jo.codeide.core.model.AppSettings
import jo.codeide.feature.preview.databinding.BullePaletteBinding
import kotlin.math.abs

/**
 * La BULLE de palette (PE7c, maquette `docs/mockups/apercu-xml.html` §
 * « Bulle, palette et popup de placement » — miroir `.bubble` +
 * `bindBubble`/`placeBubble`) : un bouton violet de 50 dp, coins 17 dp,
 * qui OUvre la palette (tap) et se DÉPLACE (glisser) avec AIMANTATION
 * aux bords gauche et droit — « son mode et sa position sont enregistrés
 * dans `SettingsDataStore` » (le mode fixe se prend depuis l'en-tête de
 * la palette, la puce « Bulle fixe / flottante »).
 *
 * La position est NORMALISÉE ([yNormalise] : 0 = haut du conteneur
 * réservé, 1 = bas) et le CÔTÉ ([aDroite]) est le bord le plus proche au
 * relâchement — les deux valeurs repartent dans [auDeplace] pour la
 * persistance (miroir `save()` de la maquette). Une bulle FIXE se pose
 * à la hauteur par défaut du côté gauche (miroir `bub.fixed → y = .42`,
 * pas de drag).
 *
 * La logique de placement est une fonction PURE ([placementPour]) —
 * testée sans [MotionEvent] (patron machine d'états PE7b).
 *
 * PE9e : la bulle est un layout GONFLÉ (`bulle_palette.xml` — l'icône
 * vectorielle centrée vient du XML).
 *
 * PE11i (ADR 0134 D4) : la vue fait **58 dp** (disque violet 50 dp +
 * halo `new-soft` 4 dp de chaque côté, dessiné par le layer-list
 * `apercu_bulle_fond` posé par le LAYOUT de l'activité — plus aucun
 * fond ni élévation en code) ; OUVERTE (palette visible) :
 * `apercu_bulle_fond_ouverte` + l'icône se teinte `apercuFond`.
 */
public class BulleFlottante : FrameLayout {
    public constructor(context: Context) : this(context, null)

    public constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        liaisonBulle = BullePaletteBinding.inflate(LayoutInflater.from(context), this)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
    }

    /** Le liage du contenu (icône — teinte posée par [ouverte]). */
    private lateinit var liaisonBulle: BullePaletteBinding

    /**
     * La bulle est OUVERTE (la palette est visible — miroir
     * `.bubble[aria-expanded=true]`) : fond `apercu_bulle_fond_ouverte`,
     * icône teintée `apercuFond`.
     */
    public var ouverte: Boolean = false
        set(valeur) {
            field = valeur
            background =
                ContextCompat.getDrawable(
                    context,
                    if (valeur) R.drawable.apercu_bulle_fond_ouverte else R.drawable.apercu_bulle_fond,
                )
            liaisonBulle.iconeBulle.imageTintList =
                ColorStateList.valueOf(
                    StyleVolet.couleur(context, if (valeur) R.attr.apercuFond else R.attr.apercuSurNouveau),
                )
        }

    /**
     * Le tap ouvre la palette (miroir `showPalette` — un glisser n'est
     * PAS un tap : seuil de déplacement).
     */
    public var auTap: (() -> Unit)? = null

    /**
     * Le relâchement d'un glisser : le côté aimanté et la position
     * normalisée à PERSISTÉS ([SettingsDataStore] — miroir `save()`).
     */
    public var auDeplace: ((aDroite: Boolean, yNormalise: Float) -> Unit)? = null

    /** La bulle est FIXE (miroir `bub.fixed`) : pas de drag, tap direct. */
    public var fixe: Boolean = false
        set(valeur) {
            field = valeur
            // Le curseur sémantique suit (miroir `.bubble.fixed`).
            if (valeur) {
                contentDescription =
                    context.getString(R.string.preview_bulle_cd) + " — " +
                    context.getString(R.string.preview_bulle_fixe)
            }
        }

    /** Le côté courant (persisté — miroir `bub.side`). */
    public var aDroite: Boolean = false

    /** La position verticale normalisée (persisté — miroir `bub.y`, 0..1). */
    public var yNormalise: Float = AppSettings.BULLE_Y_DEFAUT

    /** Appui initial (drag en cours : état de départ). */
    private var appui: PointDepart? = null

    private class PointDepart(
        val x: Float,
        val y: Float,
        val gaucheVue: Int,
        val hautVue: Int,
    )

    /**
     * Le TAP ouvre la palette, le GLISSER aimante (miroir `bindBubble`) :
     * chaque branche gestuelle a SA sortie (tap, glisser, relâchement).
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties sont
     * les branches DISTINCTES du geste, pas des refus.
     */
    @Suppress("ReturnCount")
    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        when (evenement.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!fixe) {
                    appui = PointDepart(evenement.rawX, evenement.rawY, left, top)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val depart = appui ?: return false
                val dx = evenement.rawX - depart.x
                val dy = evenement.rawY - depart.y
                // Seuil de glissement (miroir « Math.hypot > 6 ») : en
                // dessous, le geste reste un tap potentiel.
                if (abs(dx) > SEUIL_DEPLACEMENT || abs(dy) > SEUIL_DEPLACEMENT) {
                    translationX = depart.gaucheVue + dx - left
                    translationY = depart.hautVue + dy - top
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val depart = appui
                appui = null
                if (depart == null || fixe) {
                    if (evenement.actionMasked == MotionEvent.ACTION_UP) {
                        // Le TAP passe par performClick (accessibilité — lint).
                        performClick()
                    }
                    return true
                }
                val dx = evenement.rawX - depart.x
                val dy = evenement.rawY - depart.y
                if (abs(dx) <= SEUIL_DEPLACEMENT && abs(dy) <= SEUIL_DEPLACEMENT) {
                    // Un TAP : la palette s'ouvre (miroir `showPalette`).
                    performClick()
                    return true
                }
                aimanter()
            }
        }
        return true
    }

    /**
     * AIMANTE la bulle au bord le plus proche (miroir la fin de
     * `bindBubble` : `side = offsetLeft + 25 < cw/2 ? 'l' : 'r'`, la
     * position Y se re-normalise, le placement suit, la persistance
     * est notifiée).
     */
    private fun aimanter() {
        val conteneur = parent as? View ?: return
        val centre = left + translationX.toInt() + width / 2
        aDroite = centre > conteneur.width / 2
        yNormalise =
            PlacementBulle.normaliserY(
                top + translationY.toInt(),
                conteneur.height,
                reserveHauteur(),
            )
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        reposer()
        auDeplace?.invoke(aDroite, yNormalise)
    }

    /**
     * Le bas RÉSERVÉ du conteneur (les HUD de zoom + la feuille déployée
     * vivent en bas) : la bulle ne s'y aventure pas (miroir le `hi` de
     * `placeBubble`, calculé par le propriétaire — repli : la moitié
     * basse de l'écran de rendu reste accessible).
     */
    public var reserveBas: Int = 0

    /** Le bas effectif pour la normalisation (repli : conteneur entier). */
    private fun reserveHauteur(): Int = if (reserveBas > 0) reserveBas else (parent as? View)?.height ?: 0

    /**
     * REPOSE la bulle au côté et à la hauteur normalisée persistés
     * (miroir `placeBubble`) — appelé à l'ouverture de l'écran, à chaque
     * changement du conteneur ([onSizeChanged]) et à l'aimantation.
     */
    public fun reposer() {
        val conteneur = parent as? View ?: return
        if (conteneur.width <= 0 || conteneur.height <= 0) return
        // PE11i : la marge latérale est celle de la VUE de 58 dp (halo
        // compris — `apercu_bulle_marge` = 10 − 4), posée par ressource.
        val (gauche, haut) =
            PlacementBulle.placementPour(
                largeurConteneur = conteneur.width,
                hauteurConteneur = conteneur.height,
                reserveBas = reserveHauteur(),
                largeurVue = width,
                hauteurVue = height,
                aDroite = aDroite,
                yNormalise = if (fixe) AppSettings.BULLE_Y_DEFAUT else yNormalise,
                margeCotePx = resources.getDimensionPixelSize(R.dimen.apercu_bulle_marge),
            )
        translationX = (gauche - left).toFloat()
        translationY = (haut - top).toFloat()
        invalidate()
    }

    override fun onSizeChanged(
        largeur: Int,
        hauteur: Int,
        ancienneLargeur: Int,
        ancienneHauteur: Int,
    ) {
        super.onSizeChanged(largeur, hauteur, ancienneLargeur, ancienneHauteur)
        if (largeur > 0) reposer()
    }

    /** L'accessibilité pose le clic (patron ClickableViewAccessibility). */
    override fun performClick(): Boolean {
        auTap?.invoke()
        return super.performClick()
    }

    private companion object {
        /** Seuil du glisser (px — miroir `> 6` de la maquette). */
        private const val SEUIL_DEPLACEMENT: Float = 12f
    }
}

/**
 * La GÉOMÉTRIE de la bulle (PE7c — fonctions PURES, miroir `placeBubble`
 * et la fin de `bindBubble` de la maquette) : bornes verticales, côté
 * aimanté, position normalisée.
 */
public object PlacementBulle {
    /** Marge du bord latéral, en dp (miroir `left: 10px / right: 10px`). */
    public const val MARGE_COTE_DP: Int = 10

    /**
     * La position normalisée (0..1) d'un haut en pixels (miroir
     * `S.bub.y = (b.offsetTop - lo) / (hi - lo)`, bornée).
     */
    public fun normaliserY(
        hautPx: Int,
        hauteurConteneur: Int,
        reserveBas: Int,
    ): Float {
        val haut = hautDu(hauteurConteneur, reserveBas, yNormalise = 0f)
        val bas = hautDu(hauteurConteneur, reserveBas, yNormalise = 1f)
        val plage = (bas - haut).coerceAtLeast(1)
        return ((hautPx - haut).toFloat() / plage).coerceIn(0f, 1f)
    }

    /**
     * Le HAUT (px) de la bulle pour une position normalisée (miroir
     * `b.style.top = lo + y * (hi - lo)`) : les bornes suivent la
     * réserve du bas (miroir `hi = ch - sh - (side === 'l' ? 90 : 170)`,
     * portée par [reserveBas] — le propriétaire mesure ses HUD).
     */
    public fun hautDu(
        hauteurConteneur: Int,
        reserveBas: Int,
        yNormalise: Float,
    ): Int {
        val hautLibre = (hauteurConteneur - reserveBas).coerceAtLeast(0)
        return (hautLibre * yNormalise.coerceIn(0f, 1f)).toInt()
    }

    /**
     * Le placement COMPLET (miroir `placeBubble`) : côté aimanté + haut
     * calculé, en PIXELS (la marge latérale dp est convertie par
     * [margeCotePx]).
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — la
     * signature MIROIR de `placeBubble` (conteneur, vue, état persisté) ;
     * les regrouper masquerait la géométrie.
     */
    @Suppress("LongParameterList")
    public fun placementPour(
        largeurConteneur: Int,
        hauteurConteneur: Int,
        reserveBas: Int,
        largeurVue: Int,
        hauteurVue: Int,
        aDroite: Boolean,
        yNormalise: Float,
        margeCotePx: Int,
    ): Pair<Int, Int> {
        val gauche =
            if (aDroite) {
                (largeurConteneur - largeurVue - margeCotePx).coerceAtLeast(margeCotePx)
            } else {
                margeCotePx
            }
        val haut = hautDu(hauteurConteneur, reserveBas, yNormalise)
        return gauche to haut.coerceIn(0, (hauteurConteneur - hauteurVue).coerceAtLeast(0))
    }

    /** La marge latérale en pixels (miroir `left/right: 10px`). */
    public fun margeCotePx(densite: Float): Int = (MARGE_COTE_DP * densite).toInt()

    /** Le côté AIMANTÉ d'un centre x (miroir `offsetLeft + 25 < cw/2`). */
    public fun coteParCentre(
        centreX: Int,
        largeurConteneur: Int,
    ): Boolean = centreX > largeurConteneur / 2
}
