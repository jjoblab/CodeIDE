package jo.codeide.feature.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.preview.databinding.FragmentVoletSourceBinding
import jo.codeide.feature.preview.databinding.ItemLigneSourceBinding
import jo.codeide.preview.xml.FeuilleApercu
import javax.inject.Inject

/**
 * Le volet SOURCE de la feuille (PE9a, miroir `renderSrc` — ancien
 * `majSource` de [FeuilleApercuFragment]) : le TEXTE VIVANT de la
 * session en UN RecyclerView réel ([AdaptateurSource]) — lignes
 * numérotées, surlignage de la plage du span de la vue sélectionnée,
 * défilement ramené à la plage, tap sur une ligne → la vue propriétaire
 * (miroir `ownerOf`, [FeuilleApercu.vueDeLigne]), et « Ouvrir dans
 * l'éditeur » (la sélection est DÉJÀ posée dans la session).
 */
@AndroidEntryPoint
internal class VoletSourceFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_SOURCE

    /** Navigation : « Ouvrir dans l'éditeur ↗ » (l'éditeur est en dessous dans la pile). */
    @Inject
    lateinit var navigateur: AppNavigator

    private lateinit var liaison: FragmentVoletSourceBinding

    /** La liste des lignes (recyclée — ~1 500 entrées sans reconstruction). */
    private val adaptateur =
        AdaptateurSource { numero ->
            val indice =
                FeuilleApercu.vueDeLigne(
                    renduCourant?.vues ?: emptyList(),
                    viewModel.texteCourant(),
                    numero,
                )
            if (indice >= 0) viewModel.selectionner(indice)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentVoletSourceBinding.inflate(inflater, conteneur, false)
        liaison.listeSource.adapter = adaptateur
        liaison.listeSource.layoutManager = LinearLayoutManager(requireContext())
        liaison.boutonOuvrirEditeur.setOnClickListener { navigateur.goBack() }
        return liaison.root
    }

    override fun marcher() {
        val texte = viewModel.texteCourant()
        val vues = renduCourant?.vues
        val vue = vues?.getOrNull(selectionCourante)
        val plage = FeuilleApercu.plageSource(texte, vue)
        liaison.plageSource.text =
            if (plage != null) {
                getString(R.string.preview_feuille_source_lignes, plage.first, plage.last)
            } else {
                getString(R.string.preview_feuille_source_entier)
            }
        val lignes =
            texte.split('\n').mapIndexed { indiceLigne, contenuLigne ->
                LigneSourceUi(
                    numero = indiceLigne + 1,
                    texte = if (contenuLigne.isEmpty()) " " else contenuLigne,
                    surlignee = plage != null && indiceLigne + 1 >= plage.first && indiceLigne + 1 <= plage.last,
                )
            }
        adaptateur.submitList(lignes)
        // Défilement vers la plage (miroir `pane.scrollTop = …offsetTop - 70`).
        if (plage != null) {
            liaison.listeSource.post {
                (liaison.listeSource.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(plage.first - 1, StyleVolet.dp(requireContext(), DP_PLAGE))
            }
        }
    }

    private companion object {
        /** Le retrait de la plage ramenée en tête de liste, en dp (miroir `- 70`). */
        private const val DP_PLAGE: Int = 70
    }
}

/** UNE ligne du volet Source (miroir `renderSrc` : numero + texte + surlignage). */
internal data class LigneSourceUi(
    val numero: Int,
    val texte: String,
    val surlignee: Boolean,
)

/**
 * L'adaptateur du volet Source (miroir `renderSrc`) : ligne = item
 * layout RÉEL (`item_ligne_source.xml` — numéro + texte en mono), le
 * fond suit le surlignage (fond_ligne_source_surlignee du thème), le
 * tap remonte au propriétaire via [auLigne] (lecture FRAÎCHE de l'état
 * du volet à chaque appel).
 */
internal class AdaptateurSource(
    private val auLigne: (Int) -> Unit,
) : ListAdapter<LigneSourceUi, AdaptateurSource.SourceVue>(ComparateurSource) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): SourceVue =
        SourceVue(ItemLigneSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false), auLigne)

    override fun getItemCount(): Int = currentList.size

    override fun onBindViewHolder(
        holder: SourceVue,
        position: Int,
    ) {
        holder.poser(currentList[position])
    }

    /** Le conteneur d'une ligne (item_ligne_source.xml). */
    internal class SourceVue(
        private val liaison: ItemLigneSourceBinding,
        auLigne: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var numero: Int = -1

        init {
            liaison.root.setOnClickListener { auLigne(numero) }
        }

        fun poser(ligne: LigneSourceUi) {
            numero = ligne.numero
            val contexte = liaison.root.context
            liaison.numeroLigne.text = contexte.getString(R.string.preview_feuille_ligne_numero, ligne.numero)
            liaison.texteLigne.text = ligne.texte
            // Le fond : surligné (conteneur secondaire du thème) ou rien —
            // setBackgroundResource(0) retire le fond (une ligne = jamais
            // un résidu du recyclage).
            liaison.root.setBackgroundResource(
                if (ligne.surlignee) R.drawable.fond_ligne_source_surlignee else 0,
            )
        }
    }

    /** Le comparateur (lignes stables par NUMÉRO — le texte seul bouge). */
    private object ComparateurSource : DiffUtil.ItemCallback<LigneSourceUi>() {
        override fun areItemsTheSame(
            ancienne: LigneSourceUi,
            nouvelle: LigneSourceUi,
        ): Boolean = ancienne.numero == nouvelle.numero

        override fun areContentsTheSame(
            ancienne: LigneSourceUi,
            nouvelle: LigneSourceUi,
        ): Boolean = ancienne == nouvelle
    }
}
