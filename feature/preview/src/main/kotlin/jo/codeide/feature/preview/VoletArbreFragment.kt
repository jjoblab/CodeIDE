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
 * Le volet ARBRE de la feuille (PE9a, miroir `renderTree` — ancien
 * `majArbre` de [FeuilleApercuFragment]) : le pré-ordre de l'arbre
 * rendu dans UN RecyclerView réel ([AdaptateurArbre]) — indentation
 * par profondeur posée au liage, badge « ! n » (problèmes) sinon
 * dimensions, sélection DEUX VOIES par le tap d'une rangée.
 */
@AndroidEntryPoint
internal class VoletArbreFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_ARBRE

    private lateinit var liaison: FragmentVoletArbreBinding

    /** La liste (rangées + état vide), recyclée — le tap sélectionne. */
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
        if (vues.isNullOrEmpty()) {
            adaptateur.submitList(listOf(EntreeArbreUi.Vide(getString(R.string.preview_feuille_vide))))
            return
        }
        val densite = viewModel.moteur.profil.densiteDpi
        val problemes = ProblemesPreview.evaluer(vues, densite)
        adaptateur.submitList(
            FeuilleApercu.entreesArbre(vues, problemes, densite).map { EntreeArbreUi.Ligne(it) },
        )
    }
}

/** UNE entrée de l'onglet Arbre : rangée d'arbre, ou état vide. */
internal sealed class EntreeArbreUi {
    /** Le message « rien à afficher » (aucun rendu valide). */
    internal data class Vide(
        val message: String,
    ) : EntreeArbreUi()

    /** Une rangée du pré-ordre (données pures du modèle [FeuilleApercu]). */
    internal data class Ligne(
        val entree: FeuilleApercu.EntreeArbre,
    ) : EntreeArbreUi()
}

/**
 * L'adaptateur de l'onglet Arbre (miroir `renderTree`) : rangée = item
 * layout RÉEL (`item_arbre.xml` — monogramme, classe, id, badge),
 * l'indentation de profondeur est la SEULE pose au liage.
 *
 * Exemption detekt ciblée (règle 16) : MagicNumber — la SEULE dimension
 * au code est l'indentation (donnée du modèle, dp) ; tout le reste vit
 * dans le layout.
 */
@Suppress("MagicNumber")
internal class AdaptateurArbre(
    private val auLigne: (Int) -> Unit,
) : ListAdapter<EntreeArbreUi, RecyclerView.ViewHolder>(ComparateurArbre) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val gonfleur = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_VIDE -> {
                object : RecyclerView.ViewHolder(StyleVolet.texteVide(parent.context, "")) {}
            }

            else -> {
                LigneArbreVue(ItemArbreBinding.inflate(gonfleur, parent, false), auLigne)
            }
        }
    }

    override fun getItemCount(): Int = currentList.size

    override fun getItemViewType(position: Int): Int =
        when (currentList[position]) {
            is EntreeArbreUi.Vide -> TYPE_VIDE
            is EntreeArbreUi.Ligne -> TYPE_LIGNE
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = currentList[position]) {
            is EntreeArbreUi.Vide -> (holder.itemView as android.widget.TextView).text = item.message
            is EntreeArbreUi.Ligne -> (holder as LigneArbreVue).poser(item.entree)
        }
    }

    /** Le conteneur d'une rangée (item_arbre.xml — tout est en layout). */
    private class LigneArbreVue(
        private val liaison: ItemArbreBinding,
        auLigne: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var indice: Int = -1

        init {
            liaison.root.setOnClickListener { auLigne(indice) }
        }

        fun poser(entree: FeuilleApercu.EntreeArbre) {
            indice = entree.indice
            val contexte = liaison.root.context
            liaison.root.setPadding(
                StyleVolet.dp(contexte, entree.indentation),
                liaison.root.paddingTop,
                liaison.root.paddingEnd,
                liaison.root.paddingBottom,
            )
            liaison.pillMonogramme.text = entree.monogramme
            liaison.libelleClasse.text = entree.classe
            liaison.libelleId.text = contexte.getString(R.string.preview_feuille_id, entree.idNom)
            liaison.libelleId.isVisible = entree.idNom.isNotEmpty()
            liaison.badgeArbre.text = entree.badge
            liaison.badgeArbre.setTextColor(
                StyleVolet.couleur(
                    contexte,
                    if (entree.aProblemes) R.attr.apercuErreur else R.attr.apercuTexteDoux,
                ),
            )
        }
    }

    /** Le comparateur (rangées stables par INDICE dans l'arbre). */
    private object ComparateurArbre : DiffUtil.ItemCallback<EntreeArbreUi>() {
        override fun areItemsTheSame(
            ancienne: EntreeArbreUi,
            nouvelle: EntreeArbreUi,
        ): Boolean = ancienne.cle() == nouvelle.cle()

        override fun areContentsTheSame(
            ancienne: EntreeArbreUi,
            nouvelle: EntreeArbreUi,
        ): Boolean = ancienne == nouvelle

        private fun EntreeArbreUi.cle(): String =
            when (this) {
                is EntreeArbreUi.Vide -> "vide"
                is EntreeArbreUi.Ligne -> "ligne-${entree.indice}"
            }
    }

    private companion object {
        private const val TYPE_VIDE: Int = 0
        private const val TYPE_LIGNE: Int = 1
    }
}
