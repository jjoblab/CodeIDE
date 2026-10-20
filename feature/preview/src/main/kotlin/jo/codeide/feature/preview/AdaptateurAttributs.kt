package jo.codeide.feature.preview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.preview.databinding.BarreOutilsAttributsBinding
import jo.codeide.feature.preview.databinding.BoutonAutresAttributsBinding
import jo.codeide.feature.preview.databinding.CarteSectionBinding
import jo.codeide.feature.preview.databinding.EnteteEditeurBinding
import jo.codeide.feature.preview.databinding.ItemAttributBinding
import jo.codeide.feature.preview.databinding.ItemAttributGroupeBinding
import jo.codeide.feature.preview.databinding.ItemCorrectifBinding
import jo.codeide.feature.preview.databinding.ItemLectureSeuleBinding
import jo.codeide.feature.preview.databinding.SeparateurEditeurBinding
import jo.codeide.feature.preview.databinding.VideVoletBinding
import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.EditionAttributsXml

/**
 * L'adaptateur du volet ATTRIBUTS (PE11b, ADR 0132) : UN type d'item =
 * UN layout (PREVIEW_UI.md § 9). Les cartes ne gonflent leur CORPS qu'à
 * l'ouverture et le détruisent à la fermeture (état mémorisé par le
 * volet, miroir `S.so` de la maquette) ; la soumission passe par un
 * [DiffUtil] SOUS LE CAPOT (ADR 0132 § 4) — les cartes sont peu
 * nombreuses, seules les dérivées re-lient.
 *
 * Le LIAGE des contrôles reste au volet ([Evenements.lierCorpsCarte]) —
 * la mécanique d'édition (ViewModel, ports, [CatalogueAttributs]) est
 * conservée telle quelle.
 */
internal class AdaptateurAttributs(
    private val evenements: Evenements,
) : ListAdapter<ItemAttributs, AdaptateurAttributs.Detenteur>(DiffItems) {
    /** Le contrat volet ↔ adaptateur : le corps des cartes et la BARRE restent au volet. */
    internal interface Evenements {
        /** Gonfle les contrôles (FILTRÉS par la recherche, PE11f) d'une section OUVERTE. */
        fun lierCorpsCarte(
            carte: CarteSectionBinding,
            section: CatalogueAttributs.SectionAttributs,
            controles: List<CatalogueAttributs.ControleAttribut>,
            attrs: List<EditionAttributsXml.AttributSource>,
        )

        /** Branche la BARRE d'outils (recherche + bascule, PE11f) à chaque liage. */
        fun lierBarre(barre: BarreOutilsAttributsBinding)

        /** L'état ouvert/fermé d'une section (mémorisé par titre). */
        fun sectionOuverte(
            section: CatalogueAttributs.SectionAttributs,
            position: Int,
        ): Boolean

        /** Un tap sur l'en-tête : MÉMORISE le nouvel état (par titre). */
        fun basculerSection(
            section: CatalogueAttributs.SectionAttributs,
            ouverte: Boolean,
        )

        /** Le tap sur « Afficher les N autres attributs » passe en mode TOUS. */
        fun auBoutonAutres()

        /** Le tap sur le BOUTON d'une carte de correction (PE11g — l'action part au volet). */
        fun auCorrectif(action: ActionCorrectif)
    }

    /** Soumet une nouvelle liste (miroir majAttributs : le diff re-lie les dérivées). */
    fun soumettre(nouveaux: List<ItemAttributs>) {
        submitList(nouveaux)
    }

    override fun getItemCount(): Int = super.getItemCount()

    override fun getItemViewType(position: Int): Int =
        when (val item = getItem(position)) {
            is ItemAttributs.Titre -> VUE_TITRE
            ItemAttributs.BarreOutils -> VUE_BARRE
            is ItemAttributs.Correctif -> VUE_CORRECTIF
            is ItemAttributs.Carte -> VUE_CARTE
            ItemAttributs.Separateur -> VUE_SEPARATEUR
            is ItemAttributs.LectureSeule -> VUE_LECTURE_SEULE
            is ItemAttributs.TitreGroupe -> VUE_TITRE_GROUPE
            is ItemAttributs.Attribut -> VUE_ATTRIBUT
            is ItemAttributs.Message -> VUE_VIDE
            is ItemAttributs.BoutonAutres -> VUE_BOUTON_AUTRES
            ItemAttributs.Vide -> VUE_VIDE
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Detenteur {
        val gonfleur = LayoutInflater.from(parent.context)
        val vue =
            when (viewType) {
                VUE_TITRE -> EnteteEditeurBinding.inflate(gonfleur, parent, false).root
                VUE_BARRE -> BarreOutilsAttributsBinding.inflate(gonfleur, parent, false).root
                VUE_CORRECTIF -> ItemCorrectifBinding.inflate(gonfleur, parent, false).root
                VUE_CARTE -> CarteSectionBinding.inflate(gonfleur, parent, false).root
                VUE_SEPARATEUR -> SeparateurEditeurBinding.inflate(gonfleur, parent, false).root
                VUE_LECTURE_SEULE -> ItemLectureSeuleBinding.inflate(gonfleur, parent, false).root
                VUE_TITRE_GROUPE -> ItemAttributGroupeBinding.inflate(gonfleur, parent, false).root
                VUE_ATTRIBUT -> ItemAttributBinding.inflate(gonfleur, parent, false).root
                VUE_BOUTON_AUTRES -> BoutonAutresAttributsBinding.inflate(gonfleur, parent, false).root
                else -> VideVoletBinding.inflate(gonfleur, parent, false).root
            }
        return Detenteur(vue)
    }

    override fun onBindViewHolder(
        detenteur: Detenteur,
        position: Int,
    ) {
        when (val item = getItem(position)) {
            is ItemAttributs.Titre -> {
                EnteteEditeurBinding.bind(detenteur.itemView).titreEditeur.text = item.texte
            }

            ItemAttributs.BarreOutils -> {
                evenements.lierBarre(BarreOutilsAttributsBinding.bind(detenteur.itemView))
            }

            is ItemAttributs.Correctif -> {
                val carte = ItemCorrectifBinding.bind(detenteur.itemView)
                carte.titreCorrectif.text = item.titre
                carte.corpsCorrectif.text = item.message
                carte.boutonCorrectif.text = item.bouton
                carte.boutonCorrectif.setOnClickListener { evenements.auCorrectif(item.action) }
            }

            is ItemAttributs.Carte -> {
                lierCarte(detenteur, item)
            }

            is ItemAttributs.LectureSeule -> {
                ItemLectureSeuleBinding.bind(detenteur.itemView).valeurLectureSeule.text = item.texte
            }

            is ItemAttributs.TitreGroupe -> {
                ItemAttributGroupeBinding.bind(detenteur.itemView).titreGroupeAttributs.text = item.titre
            }

            is ItemAttributs.Attribut -> {
                val rangee = ItemAttributBinding.bind(detenteur.itemView)
                rangee.cleAttribut.text = item.cle
                rangee.valeurAttribut.text = item.valeur
            }

            ItemAttributs.BarreOutils,
            ItemAttributs.Separateur,
            ItemAttributs.Vide,
            -> {
                Unit
            }

            is ItemAttributs.Message -> {
                VideVoletBinding.bind(detenteur.itemView).texteVolet.text = item.texte
            }

            is ItemAttributs.BoutonAutres -> {
                val bouton = BoutonAutresAttributsBinding.bind(detenteur.itemView).boutonAutresAttributs
                bouton.text = item.texte
                bouton.setOnClickListener { evenements.auBoutonAutres() }
            }
        }
    }

    /**
     * UNE CARTE de section (miroir carteSection) : l'en-tête porte le
     * titre, le badge « n définis » (caché à zéro) et la flèche ; le CORPS
     * n'est gonflé qu'à l'ouverture — un tap bascule l'état, le corps est
     * détruit à la fermeture, et seul l'item change (pas la liste).
     */
    private fun lierCarte(
        detenteur: Detenteur,
        item: ItemAttributs.Carte,
    ) {
        val carte = CarteSectionBinding.bind(detenteur.itemView)
        val ressources = detenteur.itemView.resources
        carte.titreSection.text = item.titre
        carte.badgeDefinis.isVisible = item.definis > 0
        if (item.definis > 0) {
            carte.badgeDefinis.text =
                ressources.getQuantityString(R.plurals.preview_section_definis, item.definis, item.definis)
        }
        val ouverte = evenements.sectionOuverte(item.section, item.position)
        carte.flecheSection.text =
            ressources.getString(if (ouverte) R.string.preview_section_ouverte else R.string.preview_section_fermee)
        lierCorps(carte, item, ouverte)
        carte.enteteSection.setOnClickListener {
            val nouvelle = !evenements.sectionOuverte(item.section, item.position)
            evenements.basculerSection(item.section, nouvelle)
            val position = detenteur.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
        }
    }

    /** Le corps d'une carte : DÉTRUIT à la fermeture, gonflé à l'ouverture. */
    private fun lierCorps(
        carte: CarteSectionBinding,
        item: ItemAttributs.Carte,
        ouverte: Boolean,
    ) {
        carte.corpsSection.removeAllViews()
        carte.corpsSection.isVisible = ouverte
        if (ouverte) evenements.lierCorpsCarte(carte, item.section, item.controles, item.attrs)
    }

    /** Le détenteur générique (le liage re-bind par le type de vue). */
    internal class Detenteur(
        vue: View,
    ) : RecyclerView.ViewHolder(vue)

    /**
     * Le diff des items (ADR 0132 § 4) : les items sont des data class
     * immuables — l'égalité de contenu suffit ; une carte reste « la
     * même » par le TITRE de sa section (miroir l'état par titre).
     */
    private object DiffItems : DiffUtil.ItemCallback<ItemAttributs>() {
        override fun areItemsTheSame(
            ancien: ItemAttributs,
            nouveau: ItemAttributs,
        ): Boolean =
            when {
                ancien is ItemAttributs.Carte && nouveau is ItemAttributs.Carte -> {
                    ancien.section.titre == nouveau.section.titre
                }

                else -> {
                    ancien == nouveau
                }
            }

        override fun areContentsTheSame(
            ancien: ItemAttributs,
            nouveau: ItemAttributs,
        ): Boolean = ancien == nouveau
    }

    internal companion object {
        /** Les types de vues (UN item = UN layout — PREVIEW_UI.md § 9). */
        internal const val VUE_TITRE: Int = 0
        internal const val VUE_BARRE: Int = 1
        internal const val VUE_CARTE: Int = 2
        internal const val VUE_SEPARATEUR: Int = 3
        internal const val VUE_LECTURE_SEULE: Int = 4
        internal const val VUE_TITRE_GROUPE: Int = 5
        internal const val VUE_ATTRIBUT: Int = 6
        internal const val VUE_VIDE: Int = 7
        internal const val VUE_BOUTON_AUTRES: Int = 8
        internal const val VUE_CORRECTIF: Int = 9
    }
}
