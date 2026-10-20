package jo.codeide.feature.preview

import android.content.res.ColorStateList
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
import jo.codeide.feature.preview.databinding.FragmentVoletProblemesBinding
import jo.codeide.feature.preview.databinding.ItemProblemeBinding
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesPreview

/**
 * Le volet PROBLÈMES de la feuille (PE9a, miroir `renderProbs` — refonte
 * PE11k à l'identique de `fragment_volet_problemes`/`item_probleme` de
 * la référence, ADR 0134) : les règles pures
 * ([ProblemesPreview.evaluer]) évaluées à chaque rendu valide, rendues
 * dans UN RecyclerView réel ([AdaptateurProblemes]) — point de sévérité
 * 8 dp teinté au liage (ambre/erreur), « Voir » mono accent, tap →
 * sélection de la vue fautive ; le message VIDE vit dans le LAYOUT
 * (`message_aucun_probleme`, plus de vue construite en code). Le badge
 * compteur de l'ONGLET est tenu par la feuille (miroir `nProbs`, masqué
 * à zéro).
 */
@AndroidEntryPoint
internal class VoletProblemesFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_PROBLEMES

    private lateinit var liaison: FragmentVoletProblemesBinding

    /** La liste (rangées recyclées), le tap sélectionne. */
    private val adaptateur = AdaptateurProblemes { indice -> viewModel.selectionner(indice) }

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentVoletProblemesBinding.inflate(inflater, conteneur, false)
        liaison.listeProblemes.adapter = adaptateur
        liaison.listeProblemes.layoutManager = LinearLayoutManager(requireContext())
        return liaison.root
    }

    override fun marcher() {
        val vues = renduCourant?.vues ?: return
        val problemes = ProblemesPreview.evaluer(vues, viewModel.moteur.profil.densiteDpi)
        // Miroir renderProbs : liste VIDE → le message « Aucun problème
        // détecté… » du LAYOUT (plus d'entrée « vide » dans l'adaptateur).
        liaison.messageAucunProbleme.isVisible = problemes.isEmpty()
        adaptateur.submitList(
            if (problemes.isEmpty()) {
                emptyList()
            } else {
                problemesMarches(vues, problemes)
            },
        )
    }

    /** Les entrées affichables (données pures) avec leurs textes résolus. */
    private fun problemesMarches(
        vues: List<InfoVueRendu>,
        problemes: List<ProblemesPreview.ProblemeApercu>,
    ): List<EntreeProblemeUi> =
        FeuilleApercu.entreesProblemes(problemes).map { entree ->
            EntreeProblemeUi(
                entree = entree,
                titre = titreProbleme(entree.genre, vues.getOrNull(entree.indiceVue)),
                detail = detailProbleme(entree, vues.getOrNull(entree.indiceVue)),
            )
        }

    /** Le titre localisé d'un problème (miroir `p.title` de `computeProblems`). */
    private fun titreProbleme(
        genre: ProblemesPreview.GenreProbleme,
        vueFautive: InfoVueRendu?,
    ): String = LibellesProblemes.titre(requireContext(), genre, vueFautive)

    /** Le détail localisé (vue fautive + message, miroir `p.msg`). */
    private fun detailProbleme(
        entree: FeuilleApercu.EntreeProbleme,
        vueFautive: InfoVueRendu?,
    ): String {
        val libelle =
            vueFautive?.let { LibellesVolet.libelleVue(requireContext(), it) } ?: ""
        val message =
            LibellesProblemes.message(
                requireContext(),
                entree.genre,
                vueFautive,
                viewModel.moteur.profil.densiteDpi,
                viewModel.echellePolice,
            )
        return "$libelle\n$message"
    }
}

/** UNE rangée résolue de l'onglet Problèmes (entrée pure + textes locaux). */
internal data class EntreeProblemeUi(
    /** L'entrée pure (vue fautive, genre, sévérité). */
    val entree: FeuilleApercu.EntreeProbleme,
    /** Le titre localisé (miroir `p.title`). */
    val titre: String,
    /** Le détail localisé (libellé de la vue, puis message — deux lignes). */
    val detail: String,
)

/**
 * L'adaptateur de l'onglet Problèmes (miroir `renderProbs`) : rangée =
 * item layout RÉEL (`item_probleme.xml` — point de sévérité 8 dp teinté
 * au liage : ambre/erreur, titre, détail, « Voir »), la sélection part
 * du tap sur la rangée entière (miroir `d.onclick → select(p.rid)`).
 */
internal class AdaptateurProblemes(
    private val auLigne: (Int) -> Unit,
) : ListAdapter<EntreeProblemeUi, AdaptateurProblemes.LigneProblemeVue>(ComparateurProblemes) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LigneProblemeVue {
        val gonfleur = LayoutInflater.from(parent.context)
        return LigneProblemeVue(ItemProblemeBinding.inflate(gonfleur, parent, false), auLigne)
    }

    override fun getItemCount(): Int = currentList.size

    override fun onBindViewHolder(
        holder: LigneProblemeVue,
        position: Int,
    ) {
        holder.poser(currentList[position])
    }

    /** Le conteneur d'une rangée (item_probleme.xml). */
    internal class LigneProblemeVue(
        private val liaison: ItemProblemeBinding,
        auLigne: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var indiceVue: Int = -1

        init {
            liaison.root.setOnClickListener { auLigne(indiceVue) }
        }

        fun poser(item: EntreeProblemeUi) {
            indiceVue = item.entree.indiceVue
            val contexte = liaison.root.context
            // Le point de sévérité : ambre (avertissement) ou erreur —
            // teinte posée au liage sur le drawable `apercu_probleme_point`.
            val couleurPastille =
                StyleVolet.couleur(
                    contexte,
                    if (item.entree.erreur) R.attr.apercuErreur else R.attr.apercuAvert,
                )
            liaison.pastilleProbleme.backgroundTintList = ColorStateList.valueOf(couleurPastille)
            liaison.titreProbleme.text = item.titre
            liaison.detailProbleme.text = item.detail
        }
    }

    /** Le comparateur (rangées stables par vue fautive + genre). */
    private object ComparateurProblemes : DiffUtil.ItemCallback<EntreeProblemeUi>() {
        override fun areItemsTheSame(
            ancienne: EntreeProblemeUi,
            nouvelle: EntreeProblemeUi,
        ): Boolean = ancienne.cle() == nouvelle.cle()

        override fun areContentsTheSame(
            ancienne: EntreeProblemeUi,
            nouvelle: EntreeProblemeUi,
        ): Boolean = ancienne == nouvelle

        private fun EntreeProblemeUi.cle(): String = "ligne-${entree.indiceVue}-${entree.genre}"
    }
}
