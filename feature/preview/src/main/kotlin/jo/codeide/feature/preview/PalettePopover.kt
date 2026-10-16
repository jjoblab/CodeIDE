package jo.codeide.feature.preview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import jo.codeide.feature.preview.databinding.ItemTuilePaletteBinding
import jo.codeide.feature.preview.databinding.PopoverPaletteBinding
import jo.codeide.preview.xml.CataloguePalette

/**
 * La PALETTE de widgets (PE7c, maquette `docs/mockups/apercu-xml.html` §
 * « Bulle, palette et popup de placement » — miroir `.pop.pal` +
 * `showPalette`/`fillGrid`) : une VUE de l'activité posée au-dessus de
 * l'aperçu (« la palette doit donc être une vue de l'activité, comme le
 * popover de l'explorateur (`fond_popover`), et non une fenêtre de
 * dialogue qui bloquerait le dépôt » — exigence PE7b).
 *
 * Contenu (miroir fidèle) :
 * - l'en-tête : titre, puce « Bulle fixe / Bulle flottante »
 *   ([auBulleFixe] — le MODE et la position de la bulle persistent dans
 *   `SettingsDataStore`), bouton fermer ;
 * - le MODE d'insertion : Glisser-déposer (tuile maintenue →
 *   `startDragAndDrop` natif, la palette SE MASQUE — miroir
 *   `.phone.dragging .bubble`) ou Placer par popup (tuile touchée →
 *   [auTuilePlace] ouvre le popup de placement) ;
 * - la recherche et les puces de catégories (« Tous » + [CataloguePalette.CATEGORIES]) ;
 * - la grille de tuiles (3 colonnes) : RÉCENTS (4 derniers — miroir
 *   `recents`, session courante) puis « Tous les widgets » ; chaque
 *   tuile porte son icône violette, son nom, sa classe et le TAG de
 *   dépendance (Material = contour ; « dépendance absente » = ambre —
 *   miroir `depTag`, « une tuile dont la dépendance manque est
 *   signalée »).
 *
 * Le filtrage ([CataloguePalette.rechercher]) reste en fonctions pures
 * du module `:preview:xml` — le popover ne porte que le chrome.
 *
 * Exemption detekt ciblée (règle 16) : MagicNumber — la mise en forme du
 * popover se fait EN CODE, les dimensions dp y sont littérales (patron
 * render* de la maquette).
 */
@Suppress("MagicNumber")
public class PalettePopover(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val liaison: PopoverPaletteBinding =
        PopoverPaletteBinding.inflate(LayoutInflater.from(context), this, true)

    /** Le tap sur une tuile en MODE POPUP → le placement s'ouvre. */
    public var auTuilePlace: ((CataloguePalette.EntreePalette) -> Unit)? = null

    /** Le long-appui sur une tuile en MODE DRAG → `startDragAndDrop` (le propriétaire masque la palette). */
    public var auGlisser: ((CataloguePalette.EntreePalette, View) -> Unit)? = null

    /** Le mode d'insertion a changé (persistance — miroir `save()`). */
    public var auModeChange: ((parPopup: Boolean) -> Unit)? = null

    /** La puce bulle a basculé fixe ↔ flottante (persistance). */
    public var auBulleFixe: ((fixe: Boolean) -> Unit)? = null

    /** La recherche a changé (filtrage — miroir `palQ`). */
    public var auRecherche: ((requete: String) -> Unit)? = null

    /** La catégorie a changé (miroir `palCat`). */
    public var auCategorie: ((categorie: String) -> Unit)? = null

    /** Le bouton fermer (miroir `hidePop`). */
    public var auFermer: (() -> Unit)? = null

    /** Le mode courant (miroir `palMode`) — l'état visuel suit [definirMode]. */
    public var parPopup: Boolean = false
        private set

    /** La catégorie courante (miroir `palCat`). */
    private var categorie: String = CataloguePalette.TOUTES

    /** Les identifiants RÉCENTS (miroir `recents`, session courante). */
    private var recents: List<String> = emptyList()

    /** La requête de recherche courante (miroir `palQ`). */
    private var requete: String = ""

    /** L'adaptateur de la grille (tuiles + sous-titres). */
    private val adaptateur =
        TuilesPaletteAdapter(
            auTuilePlace = { entree ->
                if (parPopup) auTuilePlace?.invoke(entree) else auTapIndique?.invoke(entree)
            },
            auGlisser = { entree, vue ->
                if (!parPopup) auGlisser?.invoke(entree, vue)
            },
        )

    /** Le tap en mode drag (la maquette guide : « Maintenez et glissez… »). */
    public var auTapIndique: ((CataloguePalette.EntreePalette) -> Unit)? = null

    init {
        // Le fond du popover vit sur CE conteneur (miroir `.pop`).
        background =
            androidx.core.content.ContextCompat
                .getDrawable(context, R.drawable.fond_popover_apercu)
        elevation = 8f * resources.displayMetrics.density

        liaison.boutonFermerPalette.setOnClickListener { auFermer?.invoke() }
        liaison.boutonBulle.setOnClickListener { auBulleFixe?.invoke(!bulleFixe) }

        liaison.modeDrag.isSelected = true
        liaison.modeDrag.setOnClickListener { definirMode(parPopup = false, notifier = true) }
        liaison.modePopup.setOnClickListener { definirMode(parPopup = true, notifier = true) }

        liaison.champRecherche.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    a: Int,
                    b: Int,
                    c: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    a: Int,
                    b: Int,
                    c: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    requete = s?.toString() ?: ""
                    auRecherche?.invoke(requete)
                    remplirGrille()
                }
            },
        )

        construireCategories()
        liaison.grilleTuiles.adapter = adaptateur
        val gestionnaire = GridLayoutManager(context, NB_COLONNES)
        gestionnaire.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (adaptateur.estSousTitre(position)) NB_COLONNES else 1
            }
        liaison.grilleTuiles.layoutManager = gestionnaire
    }

    /** La bulle est-elle fixe (état de la puce d'en-tête) ? */
    public var bulleFixe: Boolean = false
        private set

    /** Pose l'état de la puce bulle (miroir `pinUp`). */
    public fun definirBulleFixe(fixe: Boolean) {
        bulleFixe = fixe
        liaison.boutonBulle.text =
            context.getString(if (fixe) R.string.preview_bulle_fixe else R.string.preview_bulle_flottante)
        liaison.boutonBulle.isSelected = fixe
    }

    /** Pose le mode d'insertion (visuel + notification au propriétaire). */
    public fun definirMode(
        parPopup: Boolean,
        notifier: Boolean,
    ) {
        this.parPopup = parPopup
        liaison.modeDrag.isSelected = !parPopup
        liaison.modePopup.isSelected = parPopup
        if (notifier) auModeChange?.invoke(parPopup)
    }

    /** Pose les récents (miroir `S.recents`) et re-remplit la grille. */
    public fun definirRecents(ids: List<String>) {
        recents = ids
        remplirGrille()
    }

    /** Pose la catégorie (miroir le clic d'une puce `.cats`). */
    public fun definirCategorie(nouvelle: String) {
        categorie = nouvelle
        majPucesCategories()
        remplirGrille()
    }

    /** Les puces de catégories (« Tous » + les 6 du catalogue). */
    private fun construireCategories() {
        val categories = listOf(CataloguePalette.TOUTES) + CataloguePalette.CATEGORIES
        categories.forEach { nom ->
            val puce =
                MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = nom
                    isAllCaps = false
                    minWidth = 0
                    insetLeft = 0
                    insetRight = 0
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener {
                        definirCategorie(nom)
                        auCategorie?.invoke(nom)
                    }
                }
            liaison.rangeeCategories.addView(puce)
            (puce.layoutParams as? MarginLayoutParams)?.marginEnd = dp(6)
        }
        majPucesCategories()
    }

    /** L'état visuel des puces (miroir `aria-pressed`). */
    private fun majPucesCategories() {
        for (indice in 0 until liaison.rangeeCategories.childCount) {
            val puce = liaison.rangeeCategories.getChildAt(indice) as? MaterialButton ?: continue
            puce.isSelected = (puce.text == categorie)
        }
    }

    /** La grille (miroir `fillGrid` : Récents — hors recherche/catégorie — puis tous). */
    private fun remplirGrille() {
        val items = mutableListOf<ItemTuile>()
        val recentsSansRecherche =
            if (requete.isBlank() && categorie == CataloguePalette.TOUTES) {
                recents.mapNotNull(CataloguePalette::parId).take(RECENTS_MAX)
            } else {
                emptyList()
            }
        if (recentsSansRecherche.isNotEmpty()) {
            items.add(ItemTuile.SousTitre(context.getString(R.string.preview_palette_recents)))
            recentsSansRecherche.forEach { items.add(ItemTuile.Tuile(it)) }
            items.add(ItemTuile.SousTitre(context.getString(R.string.preview_palette_tous_widgets)))
        }
        val liste = CataloguePalette.rechercher(categorie, requete)
        if (liste.isEmpty()) {
            items.add(ItemTuile.Vide(context.getString(R.string.preview_palette_vide, requete)))
        }
        liste.forEach { items.add(ItemTuile.Tuile(it)) }
        adaptateur.submitList(items)
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    private companion object {
        /** Colonnes de la grille (miroir `repeat(3, …)` de `.tiles`). */
        private const val NB_COLONNES: Int = 3

        /** Taille de la liste des récents (miroir `slice(0, 4)`). */
        private const val RECENTS_MAX: Int = 4
    }
}

/** UNE ligne de la grille : sous-titre de section, tuile, ou vide. */
public sealed class ItemTuile {
    /** Le sous-titre de section (« Récents », « Tous les widgets »). */
    public data class SousTitre(
        val titre: String,
    ) : ItemTuile()

    /** Une tuile de widget. */
    public data class Tuile(
        val entree: CataloguePalette.EntreePalette,
    ) : ItemTuile()

    /** Le message « aucun widget ne correspond ». */
    public data class Vide(
        val message: String,
    ) : ItemTuile()
}

/**
 * L'adaptateur de la grille (miroir `tile`/`fillGrid`) : le long-appui
 * lance le DRAG (mode Glisser-déposer), le tap OUVERT le placement (mode
 * popup) — les callbacks du popover restent la seule voie.
 *
 * Exemption detekt ciblée (règle 16) : MagicNumber — mêmes motifs que
 * [PalettePopover] (dimensions dp littérales du chrome, patron render*).
 */
@Suppress("MagicNumber")
public class TuilesPaletteAdapter(
    private val auTuilePlace: (CataloguePalette.EntreePalette) -> Unit,
    private val auGlisser: (CataloguePalette.EntreePalette, View) -> Unit,
) : ListAdapter<ItemTuile, RecyclerView.ViewHolder>(ComparateurTuiles) {
    public override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val gonfleur = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SOUS_TITRE -> {
                val texte = android.widget.TextView(parent.context)
                texte.setPadding(dp(parent, 2), dp(parent, 4), dp(parent, 2), dp(parent, 4))
                texte.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                texte.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        parent.context,
                        com.google.android.material.R.color.material_on_surface_emphasis_medium,
                    ),
                )
                object : RecyclerView.ViewHolder(texte) {}
            }

            TYPE_VIDE -> {
                val texte = android.widget.TextView(parent.context)
                texte.setPadding(dp(parent, 2), dp(parent, 8), dp(parent, 2), dp(parent, 8))
                texte.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                object : RecyclerView.ViewHolder(texte) {}
            }

            else -> {
                TuileVue(
                    ItemTuilePaletteBinding.inflate(gonfleur, parent, false),
                    auTuilePlace,
                    auGlisser,
                )
            }
        }
    }

    public override fun getItemCount(): Int = currentList.size

    public override fun getItemViewType(position: Int): Int =
        when (currentList[position]) {
            is ItemTuile.SousTitre -> TYPE_SOUS_TITRE
            is ItemTuile.Vide -> TYPE_VIDE
            is ItemTuile.Tuile -> TYPE_TUILE
        }

    public override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = currentList[position]) {
            is ItemTuile.SousTitre -> (holder.itemView as android.widget.TextView).text = item.titre
            is ItemTuile.Vide -> (holder.itemView as android.widget.TextView).text = item.message
            is ItemTuile.Tuile -> (holder as TuileVue).poser(item.entree)
        }
    }

    /** Le sous-titre s'étale sur la grille entière (SpanSizeLookup du popover). */
    public fun estSousTitre(position: Int): Boolean = currentList.getOrNull(position) is ItemTuile.SousTitre

    /** Le conteneur d'une tuile (icône, nom, classe, tag de dépendance). */
    @SuppressLint("DiscouragedApi") // Les icônes suivent la CONVENTION ic_tuile_<id> du
    // catalogue — une table de correspondance dupliquerait CataloguePalette.
    private class TuileVue(
        private val liaison: ItemTuilePaletteBinding,
        private val auTuilePlace: (CataloguePalette.EntreePalette) -> Unit,
        private val auGlisser: (CataloguePalette.EntreePalette, View) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun poser(entree: CataloguePalette.EntreePalette) {
            val contexte = liaison.root.context
            val iconeRes =
                contexte.resources.getIdentifier(
                    "ic_tuile_" + entree.id,
                    "drawable",
                    contexte.packageName,
                )
            if (iconeRes != 0) liaison.iconeTuile.setImageResource(iconeRes)
            liaison.nomTuile.text = entree.nomAffiche
            liaison.classeTuile.text = entree.classe
            val tag = liaison.tagTuile
            when {
                // Miroir `missing: true` de la maquette : la dépendance
                // RecyclerView est signalée ABSENTE (ambre — `.tag.wr`,
                // « une tuile dont la dépendance manque est signalée »).
                entree.id == "rv" -> {
                    tag.isVisible = true
                    tag.text = contexte.getString(R.string.preview_palette_dependance_absente)
                    tag.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            contexte,
                            R.color.preview_probleme_avertissement,
                        ),
                    )
                    tag.background =
                        androidx.core.content.ContextCompat.getDrawable(
                            contexte,
                            R.drawable.fond_tag_ambre,
                        )
                }

                // Miroir `depTag` : la coordonnée requise en tag de contour.
                entree.dependanceRequise != null -> {
                    tag.isVisible = true
                    tag.text =
                        if (entree.dependanceRequise == CataloguePalette.DEPENDANCE_MATERIAL) {
                            "Material"
                        } else {
                            entree.dependanceRequise
                        }
                    tag.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            contexte,
                            R.color.preview_probleme_neutre,
                        ),
                    )
                    tag.background =
                        androidx.core.content.ContextCompat.getDrawable(
                            contexte,
                            R.drawable.fond_tag_contour,
                        )
                }

                else -> {
                    tag.isVisible = false
                }
            }
            liaison.root.setOnClickListener { auTuilePlace(entree) }
            liaison.root.setOnLongClickListener { vue ->
                auGlisser(entree, vue)
                true
            }
        }
    }

    /** Le comparateur (tuiles stables par identifiant). */
    private object ComparateurTuiles : DiffUtil.ItemCallback<ItemTuile>() {
        override fun areItemsTheSame(
            ancienne: ItemTuile,
            nouvelle: ItemTuile,
        ): Boolean = ancienne == nouvelle

        override fun areContentsTheSame(
            ancienne: ItemTuile,
            nouvelle: ItemTuile,
        ): Boolean = ancienne == nouvelle
    }

    private fun dp(
        parent: ViewGroup,
        valeur: Int,
    ): Int = (valeur * parent.resources.displayMetrics.density).toInt()

    private companion object {
        private const val TYPE_SOUS_TITRE: Int = 0
        private const val TYPE_TUILE: Int = 1
        private const val TYPE_VIDE: Int = 2
    }
}
