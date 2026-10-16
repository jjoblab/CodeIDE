package jo.codeide.feature.preview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import jo.codeide.feature.preview.databinding.PopoverPlacementBinding
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.ZonesDepot

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
 *   [FeuilleApercu.entreesArbre]) ;
 * - les positions AVANT / DEDANS / APRÈS (miroir `.pos` : DEDANS
 *   désactivé hors conteneur, AVANT/APRÈS désactivés sur la racine —
 *   [PlacementPopup.positionsAutorisees]) ;
 * - le résumé « Dans X, position n sur total » (miroir `.res`,
 *   [PlacementPopup.resume]) ;
 * - le bouton violet « Placer ici » (miroir `.prim` → [auConfirmer]).
 *
 * Le REPÈRE EN DIRECT reste au propriétaire : chaque changement de
 * référence/position notifie [auCibleChange], l'activité pose la cible
 * sur la vue d'aperçu (zones de dépôt PE7b — un seul calque).
 *
 * Exemption detekt ciblée (règle 16) : MagicNumber — la mise en forme des
 * rangées se fait EN CODE, les dimensions dp y sont littérales (patron
 * render* de la maquette, même exemption que [FeuilleApercuFragment]).
 */
@Suppress("MagicNumber")
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
        background = ContextCompat.getDrawable(context, R.drawable.fond_popover_apercu)
        elevation = 8f * resources.displayMetrics.density
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
     * unique).
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
    }

    /** L'arbre des vues rendues (miroir `.mini` — rangées d'arbre PE6d). */
    private fun majArbre(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
    ) {
        liaison.lignesArbrePlacement.removeAllViews()
        if (vues.isEmpty()) return
        val entrees = FeuilleApercu.entreesArbre(vues, problemes = emptyList(), densiteDpi = 0)
        for (entree in entrees) {
            val rangee =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(entree.indentation + 8), dp(6), dp(8), dp(6))
                    isSelected = entree.indice == indiceReference
                    setBackgroundColor(
                        if (entree.indice == indiceReference) {
                            // Miroir `.row.on` : le fond doux de la sélection.
                            ContextCompat.getColor(context, R.color.preview_bulle_halo)
                        } else {
                            Color.TRANSPARENT
                        },
                    )
                    val monogramme = TextView(context)
                    monogramme.text = entree.monogramme
                    monogramme.typeface = Typeface.MONOSPACE
                    monogramme.textSize = 10f
                    monogramme.gravity = Gravity.CENTER
                    monogramme.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    val fondMono = GradientDrawable()
                    fondMono.shape = GradientDrawable.RECTANGLE
                    fondMono.cornerRadius = dp(5).toFloat()
                    fondMono.setColor(ContextCompat.getColor(context, R.color.preview_bulle_fond))
                    monogramme.background = fondMono
                    addView(
                        monogramme,
                        LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) },
                    )
                    val classeVue = TextView(context)
                    classeVue.text = entree.classe
                    classeVue.textSize = 12.5f
                    addView(classeVue)
                    if (entree.idNom.isNotEmpty()) {
                        val idVue = TextView(context)
                        idVue.text = context.getString(R.string.preview_prefixe_id, entree.idNom)
                        idVue.typeface = Typeface.MONOSPACE
                        idVue.textSize = 10f
                        idVue.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                        addView(idVue, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
                    }
                    setOnClickListener { auReference?.invoke(entree.indice) }
                }
            liaison.lignesArbrePlacement.addView(rangee)
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
        bouton: MaterialButton,
        position: PlacementPopup.Position,
        autorisees: Set<PlacementPopup.Position>,
        courante: PlacementPopup.Position,
    ) {
        bouton.isEnabled = position in autorisees
        bouton.isSelected = position == courante
    }

    /** Le résumé « Dans X, position n sur total » (miroir `.res`). */
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
        liaison.resumePlacement.text =
            context.getString(
                R.string.preview_placement_resume,
                resume.libelleParent,
                resume.position,
                resume.total,
            )
        liaison.boutonPlacer.isEnabled = true
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    private companion object {
        private const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
