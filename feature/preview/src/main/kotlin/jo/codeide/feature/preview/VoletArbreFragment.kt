package jo.codeide.feature.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.FragmentVoletArbreBinding
import jo.codeide.feature.preview.databinding.ItemArbreBinding
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.ProblemesPreview

/**
 * Le volet ARBRE de la feuille (PE9a, miroir `renderTree` — refonte
 * PE11k à l'identique de `fragment_volet_arbre`/`item_arbre` de la
 * référence, ADR 0134) : le pré-ordre de l'arbre rendu dans UN
 * RecyclerView réel ([AdaptateurArbre]) — indentation posée au liage,
 * rangée 34 dp à TROIS états (sélectionnée = `isSelected` → accent
 * doux, appuyée = surface 2 — le sélecteur `apercu_rangee_fond`),
 * badge « ! n » sur fond AMBRE (avertissement) sinon les dimensions
 * sur fond neutre, monogramme 20 dp. La SÉLECTION vit dans les
 * entrées (rebind DiffUtil des seules rangées changées).
 */
@AndroidEntryPoint
internal class VoletArbreFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_ARBRE

    private lateinit var liaison: FragmentVoletArbreBinding

    /** La liste (rangées recyclées), le tap sélectionne. */
    private val adaptateur = AdaptateurArbre { indice -> viewModel.selectionner(indice) }

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentVoletArbreBinding.inflate(inflater, conteneur, false)
        liaison.listeArbre.adapter = adaptateur
        liaison.listeArbre.layoutManager = LinearLayoutManager(requireContext())
        return liaison.root
    }

    override fun marcher() {
        val vues = renduCourant?.vues
        // Miroir renderTree : arbre VIDE → liste vide (la maquette
        // n'affiche AUCUN message dans ce volet — le fil d'Ariane de la
        // feuille porte déjà « Sans sélection »).
        if (vues.isNullOrEmpty()) {
            adaptateur.submitList(emptyList())
            return
        }
        val densite = viewModel.moteur.profil.densiteDpi
        val problemes = ProblemesPreview.evaluer(vues, densite)
        adaptateur.submitList(
            FeuilleApercu.entreesArbre(vues, problemes, densite).mapIndexed { indice, entree ->
                EntreeArbreUi(entree = entree, selectionnee = indice == selectionCourante)
            },
        )
    }
}

/** UNE entrée de l'onglet Arbre : la rangée du pré-ordre et son état de sélection. */
internal data class EntreeArbreUi(
    /** La rangée du pré-ordre (données pures du modèle [FeuilleApercu]). */
    val entree: FeuilleApercu.EntreeArbre,
    /** La rangée est-elle la SÉLECTION courante (état `isSelected` au liage). */
    val selectionnee: Boolean,
)

/**
 * L'adaptateur de l'onglet Arbre (miroir `renderTree`) : rangée = item
 * layout RÉEL (`item_arbre.xml` — monogramme, classe, id, badge), les
 * SEULES poses au liage sont l'indentation de profondeur (donnée du
 * modèle), l'état `isSelected` de la rangée et le fond/couleur du badge
 * (neutre ↔ avertissement — miroir `.badge`/`.badge.w`).
 */
internal class AdaptateurArbre(
    private val auLigne: (Int) -> Unit,
) : ListAdapter<EntreeArbreUi, AdaptateurArbre.LigneArbreVue>(ComparateurArbre) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LigneArbreVue {
        val gonfleur = LayoutInflater.from(parent.context)
        return LigneArbreVue(ItemArbreBinding.inflate(gonfleur, parent, false), auLigne)
    }

    override fun getItemCount(): Int = currentList.size

    override fun onBindViewHolder(
        holder: LigneArbreVue,
        position: Int,
    ) {
        holder.poser(currentList[position])
    }

    /** Le conteneur d'une rangée (item_arbre.xml — tout le reste est en layout). */
    internal class LigneArbreVue(
        private val liaison: ItemArbreBinding,
        auLigne: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var indice: Int = -1

        init {
            liaison.root.setOnClickListener { auLigne(indice) }
        }

        fun poser(entree: EntreeArbreUi) {
            indice = entree.entree.indice
            val contexte = liaison.root.context
            // Sélection = isSelected : le sélecteur apercu_rangee_fond
            // peint l'accent doux (miroir `.row.on` de la maquette).
            liaison.root.isSelected = entree.selectionnee
            // L'indentation de profondeur : 8 + profondeur × 16 dp, la
            // SEULE dimension au code (donnée du modèle, miroir
            // `paddingLeft = (8 + x.d * 16)`).
            liaison.root.setPadding(
                StyleVolet.dp(contexte, entree.entree.indentation),
                liaison.root.paddingTop,
                liaison.root.paddingEnd,
                liaison.root.paddingBottom,
            )
            liaison.pillMonogramme.text = entree.entree.monogramme
            liaison.libelleClasse.text = entree.entree.classe
            liaison.libelleId.text = contexte.getString(R.string.preview_feuille_id, entree.entree.idNom)
            liaison.libelleId.isVisible = entree.entree.idNom.isNotEmpty()
            // Badge : « ! n » sur fond AMBRE + texte « fond » (miroir
            // `.badge.w`), sinon les dimensions sur fond NEUTRE + texte
            // doux ; PAS de badge sans contenu (miroir : rien ajouté).
            liaison.badgeArbre.text = entree.entree.badge
            liaison.badgeArbre.isVisible = entree.entree.badge.isNotEmpty()
            liaison.badgeArbre.setBackgroundResource(
                if (entree.entree.aProblemes) {
                    R.drawable.apercu_badge_rangee_avert_fond
                } else {
                    R.drawable.apercu_badge_rangee_fond
                },
            )
            liaison.badgeArbre.setTextColor(
                StyleVolet.couleur(
                    contexte,
                    if (entree.entree.aProblemes) R.attr.apercuFond else R.attr.apercuTexteDoux,
                ),
            )
        }
    }

    /** Le comparateur (rangées stables par INDICE dans l'arbre). */
    private object ComparateurArbre : DiffUtil.ItemCallback<EntreeArbreUi>() {
        override fun areItemsTheSame(
            ancienne: EntreeArbreUi,
            nouvelle: EntreeArbreUi,
        ): Boolean = ancienne.entree.indice == nouvelle.entree.indice

        override fun areContentsTheSame(
            ancienne: EntreeArbreUi,
            nouvelle: EntreeArbreUi,
        ): Boolean = ancienne == nouvelle
    }
}
