package jo.codeide.feature.preview

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import jo.codeide.feature.preview.databinding.ItemRangeePlacementBinding
import jo.codeide.feature.preview.databinding.PopoverPlacementBinding
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.ZonesDepot
import kotlin.math.min

/**
 * Le POPUP de placement (PE7c, maquette `docs/mockups/apercu-xml.html` §
 * « Bulle, palette et popup de placement » — miroir `.pop.bot` +
 * `showPlace`/`drawPlace`) : « le popup de placement affiche l'arbre des
 * vues rendues, les positions Avant, Dedans, Après, et dessine le repère
 * en direct sur l'aperçu ».
 *
 * Contenu (miroir fidèle) :
 * - l'en-tête : « Où placer X ? » + fermer ;
 * - l'ARBRE des vues rendues (référence surlignée — miroir `.mini`, les
 *   rangées reprennent le patron de l'arbre PE6d :
 *   [FeuilleApercu.entreesArbre], hauteur bornée à 150 dp par le code) ;
 * - les positions AVANT / DEDANS / APRÈS (miroir `.pos` : DEDANS
 *   désactivé hors conteneur, AVANT/APRÈS désactivés sur la racine —
 *   [PlacementPopup.positionsAutorisees], indisponible = opacité .35) ;
 * - le résumé « Dans X, position n sur total » (miroir `.res`, les valeurs
 *   en `ResumeFort` — [PlacementPopup.resume]) ;
 * - le bouton violet « Placer ici » (miroir `.prim` → [auConfirmer]).
 *
 * PE11j (ADR 0134) : la COQUE vient du layout (style
 * `Widget.CodeIDE.Apercu.Popover` — plus aucun fond ni élévation en
 * code) ; la référence du mini-arbre est `isSelected` (accent doux du
 * sélecteur `apercu_rangee_fond`, PAS en violet) ; la hauteur du popup
 * est bornée à [R.dimen.apercu_placement_hauteur_max] par le code.
 *
 * Le REPÈRE EN DIRECT reste au propriétaire : chaque changement de
 * référence/position notifie [auReference]/[auPosition], l'activité pose
 * la cible sur la vue d'aperçu (zones de dépôt PE7b — un seul calque).
 */
public class PopupPlacement(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val liaison: PopoverPlacementBinding =
        PopoverPlacementBinding.inflate(LayoutInflater.from(context), this, true)

    /** La référence a changé (tap sur une rangée d'arbre — miroir `setPlaceRef`). */
    public var auReference: ((indice: Int) -> Unit)? = null

    /** La position a changé (miroir `p.pos = m`). */
    public var auPosition: ((position: PlacementPopup.Position) -> Unit)? = null

    /** « Placer ici » (miroir `ok.onclick` → `insertAt`). */
    public var auConfirmer: (() -> Unit)? = null

    /** Fermeture (miroir `hidePop`). */
    public var auFermer: (() -> Unit)? = null

    init {
        liaison.boutonFermerPlacement.setOnClickListener { auFermer?.invoke() }
        liaison.boutonPlacer.setOnClickListener { auConfirmer?.invoke() }
        liaison.positionAvant.setOnClickListener { auPosition?.invoke(PlacementPopup.Position.AVANT) }
        liaison.positionDedans.setOnClickListener { auPosition?.invoke(PlacementPopup.Position.DEDANS) }
        liaison.positionApres.setOnClickListener { auPosition?.invoke(PlacementPopup.Position.APRES) }
    }

    /** Le titre « Où placer X ? » (miroir `hd b`). */
    public fun definirTitre(nomWidget: String) {
        liaison.titrePlacement.text = context.getString(R.string.preview_placement_titre, nomWidget)
    }

    /**
     * Re-marche TOUT le popup (miroir `drawPlace`) : l'arbre, les
     * positions autorisées, le résumé. Les vues + la référence courante
     * + la position courante viennent du propriétaire (source de vérité
     * unique). Les bornes de hauteur (mini-arbre 150 dp, coque 340 dp)
     * suivent — imposées par le code (miroir `max-height`).
     */
    public fun afficher(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
        position: PlacementPopup.Position,
        cible: ZonesDepot.CibleDepot?,
    ) {
        majArbre(vues, indiceReference)
        majPositions(vues, indiceReference, position)
        majResume(vues, cible)
        bornerHauteurs()
    }

    /** L'arbre des vues rendues (miroir `.mini` — rangées d'arbre PE6d). */
    private fun majArbre(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
    ) {
        liaison.lignesArbrePlacement.removeAllViews()
        if (vues.isEmpty()) return
        val entrees = FeuilleApercu.entreesArbre(vues, problemes = emptyList(), densiteDpi = 0)
        val retraitBase = resources.getDimensionPixelSize(R.dimen.apercu_rangee_pad_h)
        val retraitNiveau = resources.getDimensionPixelSize(R.dimen.apercu_rangee_indent)
        for (entree in entrees) {
            // PE11j : la rangée vient de l'item layout réel (miroir `.mini`,
            // MÊMES rangées que l'arbre — monogramme, classe, id).
            val rangee =
                ItemRangeePlacementBinding.inflate(
                    LayoutInflater.from(context),
                    liaison.lignesArbrePlacement,
                    false,
                )
            // La référence surlignée = isSelected → accent DOUX du sélecteur
            // (miroir `.row.on` — PE11j retire le violet posé en code).
            rangee.rangeePlacement.isSelected = entree.indice == indiceReference
            // L'indentation de profondeur suit l'arbre (8 + profondeur × 16 —
            // miroir `style.paddingLeft`, le reste du padding vient du style).
            rangee.rangeePlacement.setPadding(
                retraitBase + entree.indentation * retraitNiveau,
                rangee.rangeePlacement.paddingTop,
                rangee.rangeePlacement.paddingEnd,
                rangee.rangeePlacement.paddingBottom,
            )
            rangee.monogrammePlacement.text = entree.monogramme
            rangee.libelleClassePlacement.text = entree.classe
            rangee.libelleIdPlacement.isVisible = entree.idNom.isNotEmpty()
            rangee.libelleIdPlacement.text = context.getString(R.string.preview_prefixe_id, entree.idNom)
            rangee.rangeePlacement.setOnClickListener { auReference?.invoke(entree.indice) }
            liaison.lignesArbrePlacement.addView(rangee.rangeePlacement)
        }
        defilerVersSelection(entrees.size, indiceReference)
    }

    /** Le défilement vers la rangée sélectionnée (miroir `mini.scrollTop`). */
    private fun defilerVersSelection(
        total: Int,
        indiceReference: Int,
    ) {
        liaison.defileArbrePlacement.post {
            val selectionnee =
                liaison.lignesArbrePlacement.indexOfChild(
                    liaison.lignesArbrePlacement.getChildAt(indiceReference.coerceAtMost(total - 1)),
                )
            if (selectionnee >= 0) {
                val vue = liaison.lignesArbrePlacement.getChildAt(selectionnee)
                liaison.defileArbrePlacement.scrollTo(0, vue.top - liaison.defileArbrePlacement.height / 2)
            }
        }
    }

    /** Les trois positions (miroir `.pos` : cochée + désactivée). */
    private fun majPositions(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
        position: PlacementPopup.Position,
    ) {
        val autorisees = PlacementPopup.positionsAutorisees(vues, indiceReference)
        poserPosition(liaison.positionAvant, PlacementPopup.Position.AVANT, autorisees, position)
        poserPosition(liaison.positionDedans, PlacementPopup.Position.DEDANS, autorisees, position)
        poserPosition(liaison.positionApres, PlacementPopup.Position.APRES, autorisees, position)
    }

    private fun poserPosition(
        bouton: TextView,
        position: PlacementPopup.Position,
        autorisees: Set<PlacementPopup.Position>,
        courante: PlacementPopup.Position,
    ) {
        bouton.isEnabled = position in autorisees
        // Miroir `.pos button:disabled` : opacité .35, la sélection reste lisible.
        bouton.alpha = if (position in autorisees) ALPHA_ACTIVE else ALPHA_INDISPONIBLE
        bouton.isSelected = position == courante
    }

    /**
     * Le résumé « Dans X, position n sur total » (miroir `.res` : mono 12,
     * les valeurs `<b>` en ResumeFort — violet, [majFort]).
     */
    private fun majResume(
        vues: List<InfoVueRendu>,
        cible: ZonesDepot.CibleDepot?,
    ) {
        val resume = PlacementPopup.resume(vues, cible)
        if (resume == null) {
            liaison.resumePlacement.text = ""
            liaison.boutonPlacer.isEnabled = false
            return
        }
        val corps = SpannableStringBuilder()
        corps.append(context.getString(R.string.preview_placement_resume_dans))
        majFort(corps, resume.libelleParent)
        corps.append(context.getString(R.string.preview_placement_resume_position))
        majFort(corps, resume.position.toString())
        corps.append(context.getString(R.string.preview_placement_resume_sur))
        corps.append(resume.total.toString())
        liaison.resumePlacement.text = corps
        liaison.boutonPlacer.isEnabled = true
    }

    /** Pose le morceau FORT (ResumeFort — miroir `<b>` de `.res`) à la fin de [corps]. */
    private fun majFort(
        corps: SpannableStringBuilder,
        morceau: String,
    ) {
        val debut = corps.length
        corps.append(morceau)
        corps.setSpan(
            TextAppearanceSpan(context, R.style.TextAppearance_CodeIDE_Apercu_ResumeFort),
            debut,
            corps.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * Les BORNES de hauteur (miroir `max-height`) : le mini-arbre à
     * [R.dimen.apercu_mini_hauteur_max], la coque à
     * [R.dimen.apercu_placement_hauteur_max] — le code les impose après
     * la passe de mesure (le ScrollView n'a pas de maxHeight).
     */
    private fun bornerHauteurs() {
        liaison.defileArbrePlacement.post {
            val miniMax = resources.getDimensionPixelSize(R.dimen.apercu_mini_hauteur_max)
            val hauteurMini = liaison.lignesArbrePlacement.measuredHeight
            if (hauteurMini > 0) {
                liaison.defileArbrePlacement.layoutParams.height = min(miniMax, hauteurMini)
            }
            val coqueMax = resources.getDimensionPixelSize(R.dimen.apercu_placement_hauteur_max)
            val hauteurCoque = measuredHeight
            if (hauteurCoque > 0 && hauteurCoque > coqueMax) {
                layoutParams.height = coqueMax
            }
        }
    }

    private companion object {
        /** Opacité d'une position INDISPONIBLE (miroir `.pos button:disabled`). */
        private const val ALPHA_INDISPONIBLE: Float = 0.35f

        /** Opacité d'une position disponible. */
        private const val ALPHA_ACTIVE: Float = 1f
    }
}
