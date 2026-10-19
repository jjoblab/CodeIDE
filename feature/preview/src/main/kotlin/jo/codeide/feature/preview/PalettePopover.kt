package jo.codeide.feature.preview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import jo.codeide.feature.preview.databinding.ItemPuceCategorieBinding
import jo.codeide.feature.preview.databinding.ItemSousTitrePaletteBinding
import jo.codeide.feature.preview.databinding.ItemTuilePaletteBinding
import jo.codeide.feature.preview.databinding.ItemVidePaletteBinding
import jo.codeide.feature.preview.databinding.PopoverPaletteBinding
import jo.codeide.preview.xml.CataloguePalette
import kotlin.math.min

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
 * - la grille de tuiles (3 colonnes, espacement [R.dimen.apercu_tuiles_gap] en
 *   [EspacementGrilleTuiles]) : RÉCENTS (4 derniers — miroir `recents`,
 *   session courante) puis « Tous les widgets » ; chaque tuile porte son
 *   icône violette, son nom, sa classe et le TAG de dépendance (Material =
 *   contour ; « dépendance absente » = ambre — miroir `depTag`, « une tuile
 *   dont la dépendance manque est signalée »).
 *
 * PE11j (ADR 0134 D9) : la COQUE vient du layout (style
 * `Widget.CodeIDE.Apercu.Popover` — plus aucun fond ni élévation en code) ;
 * la HAUTEUR est bornée à `min(600 dp, disponible)` par [bornerHauteur],
 * imposée par le code (le `LinearLayout` enveloppe son contenu : sans
 * borne la grille déborde de l'écran).
 *
 * Le filtrage ([CataloguePalette.rechercher]) reste en fonctions pures
 * du module `:preview:xml` — le popover ne porte que le chrome.
 */
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
        liaison.grilleTuiles.addItemDecoration(EspacementGrilleTuiles(context))
    }

    /**
     * BORNE la hauteur du popover (PE11j, ADR 0134 D9 — miroir
     * `max-height: min(600px, 100% - 134px)` de la maquette) :
     * `LayoutParams.height = min(600 dp, conteneur − marges)`, IMPOSÉ par
     * le code — le `LinearLayout` enveloppe son contenu, sans borne la
     * grille déborde de l'écran. [hauteurConteneur] = hauteur du conteneur
     * plein écran de l'activité.
     */
    public fun bornerHauteur(hauteurConteneur: Int) {
        val hauteurCalculee =
            HauteurPalettePopover.calcule(
                hauteurConteneur = hauteurConteneur,
                margeHaut = resources.getDimensionPixelSize(R.dimen.apercu_popover_haut),
                margeBas = resources.getDimensionPixelSize(R.dimen.apercu_popover_marge_bas),
                hauteurMax = resources.getDimensionPixelSize(R.dimen.apercu_palette_hauteur_max),
            )
        val parametres = layoutParams ?: FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, hauteurCalculee)
        parametres.height = hauteurCalculee
        layoutParams = parametres
    }

    /** La bulle est-elle fixe (état de la puce d'en-tête) ? */
    public var bulleFixe: Boolean = false
        private set

    /** Pose l'état de la puce bulle (miroir `pinUp` : cochée = fixe). */
    public fun definirBulleFixe(fixe: Boolean) {
        bulleFixe = fixe
        liaison.boutonBulle.text =
            context.getString(if (fixe) R.string.preview_bulle_fixe else R.string.preview_bulle_flottante)
        // La sélection visuelle de la puce suit state_checked (sélecteur
        // apercu_categorie_fond — miroir aria-pressed).
        liaison.boutonBulle.isChecked = fixe
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

    /** Les puces de catégories (« Tous » + les 6 du catalogue) — item
     * layout réel (item_puce_categorie.xml), la sélection reste
     * `isChecked` posé par [majPucesCategories]. */
    private fun construireCategories() {
        val categories = listOf(CataloguePalette.TOUTES) + CataloguePalette.CATEGORIES
        categories.forEach { nom ->
            val puce = ItemPuceCategorieBinding.inflate(LayoutInflater.from(context), liaison.rangeeCategories, false)
            puce.puceCategorie.text = nom
            puce.puceCategorie.setOnClickListener {
                definirCategorie(nom)
                auCategorie?.invoke(nom)
            }
            liaison.rangeeCategories.addView(puce.puceCategorie)
        }
        majPucesCategories()
    }

    /** L'état visuel des puces (miroir `aria-pressed` : la sélection est state_checked). */
    private fun majPucesCategories() {
        for (indice in 0 until liaison.rangeeCategories.childCount) {
            val puce = liaison.rangeeCategories.getChildAt(indice) as? Chip ?: continue
            puce.isChecked = puce.text.toString() == categorie
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

    private companion object {
        /** Colonnes de la grille (miroir `repeat(3, …)` de `.tiles`). */
        private const val NB_COLONNES: Int = 3

        /** Taille de la liste des récents (miroir `slice(0, 4)`). */
        private const val RECENTS_MAX: Int = 4
    }
}

/**
 * La hauteur BORNÉE du popover de palette (PE11j, ADR 0134 D9) — fonction
 * PURE : `min(600 dp, conteneur − marge haute − marge basse)`, jamais
 * négative. Testée sans vue (patron machine d'états PE7b).
 */
internal object HauteurPalettePopover {
    /** Le calcul du `LayoutParams.height` (miroir `min(600px, 100% - 134px)`). */
    public fun calcule(
        hauteurConteneur: Int,
        margeHaut: Int,
        margeBas: Int,
        hauteurMax: Int,
    ): Int = min(hauteurMax, hauteurConteneur - margeHaut - margeBas).coerceAtLeast(0)
}

/**
 * L'ESPACEMENT de la grille de tuiles (PE11j, ADR 0134 D9 — miroir
 * `.tiles{display:grid;gap:8px}`) : [RecyclerView.ItemDecoration] de
 * `apercu_tuiles_gap` ENTRE les colonnes et les rangées, rien sur les
 * bords. Les tuiles étant égales (`GridLayoutManager`), chaque colonne
 * perd la même largeur totale (perte = espacement × (colonnes − 1) /
 * colonnes) pour que les TROIS tuiles restent identiques ; l'écart entre
 * deux colonnes vaut l'espacement à UN dp près (8/3 ne tombe pas juste).
 * Les sous-titres (pleine largeur) ne portent que l'écart de rangée.
 */
internal class EspacementGrilleTuiles(
    contexte: Context,
) : RecyclerView.ItemDecoration() {
    /** L'écart entre deux tuiles (miroir `.tiles` gap), en px. */
    private val espacement: Int = contexte.resources.getDimensionPixelSize(R.dimen.apercu_tuiles_gap)

    override fun getItemOffsets(
        rect: android.graphics.Rect,
        vue: View,
        parent: RecyclerView,
        etat: RecyclerView.State,
    ) {
        val gestionnaire = parent.layoutManager as? GridLayoutManager ?: return
        val position = parent.getChildAdapterPosition(vue)
        if (position == RecyclerView.NO_POSITION) return
        val nbColonnes = gestionnaire.spanCount
        val etendue = gestionnaire.spanSizeLookup.getSpanSize(position)
        // Rangée : écart de 8 entre deux rangées, rien au-dessus de la
        // première (le marginTop du RecyclerView tient déjà son rôle).
        rect.top = if (position == 0) 0 else espacement
        // Sous-titre pleine largeur : pas d'écart de colonne.
        if (etendue < nbColonnes) {
            val colonne = gestionnaire.spanSizeLookup.getSpanIndex(position, nbColonnes)
            // Perte ÉGALE par colonne : left croît, right complète (voir KDoc).
            val perte = espacement * (nbColonnes - 1) / nbColonnes
            rect.left = colonne * perte / (nbColonnes - 1)
            rect.right = perte - rect.left
        }
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
 */
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
                object : RecyclerView.ViewHolder(
                    ItemSousTitrePaletteBinding.inflate(gonfleur, parent, false).sousTitrePalette,
                ) {}
            }

            TYPE_VIDE -> {
                object : RecyclerView.ViewHolder(
                    ItemVidePaletteBinding.inflate(gonfleur, parent, false).messageVidePalette,
                ) {}
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
                    // .tag.wr : le VARIANT averti du style Tag (fond ambre,
                    // texte apercuAvert) — posé au liage (donnée de la tuile).
                    tag.background = ContextCompat.getDrawable(contexte, R.drawable.apercu_tag_avert_fond)
                    tag.setTextColor(StyleVolet.couleur(contexte, R.attr.apercuAvert))
                }

                // Miroir `depTag` : la coordonnée requise en tag de contour
                // (.tag.old : surface 2 + trait — l'état par défaut du style).
                entree.dependanceRequise != null -> {
                    tag.isVisible = true
                    tag.text =
                        if (entree.dependanceRequise == CataloguePalette.DEPENDANCE_MATERIAL) {
                            "Material"
                        } else {
                            entree.dependanceRequise
                        }
                    tag.background = ContextCompat.getDrawable(contexte, R.drawable.apercu_tag_ancien_fond)
                    tag.setTextColor(StyleVolet.couleur(contexte, R.attr.apercuTexteDoux))
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

    private companion object {
        private const val TYPE_SOUS_TITRE: Int = 0
        private const val TYPE_TUILE: Int = 1
        private const val TYPE_VIDE: Int = 2
    }
}
