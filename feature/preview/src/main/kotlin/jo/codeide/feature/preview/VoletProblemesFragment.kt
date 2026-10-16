package jo.codeide.feature.preview

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.FragmentVoletProblemesBinding
import jo.codeide.feature.preview.databinding.ItemProblemeBinding
import jo.codeide.preview.xml.CalquesApercu
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesPreview

/**
 * Le volet PROBLÈMES de la feuille (PE9a, miroir `renderProbs` — ancien
 * `majProblemes` de [FeuilleApercuFragment]) : les règles pures
 * ([ProblemesPreview.evaluer]) évaluées à chaque rendu valide, rendues
 * dans UN RecyclerView réel ([AdaptateurProblemes]) — pastille
 * ambre/rouge teintée au liage, tap → sélection de la vue fautive. Le
 * badge compteur de l'ONGLET est tenu par la feuille (miroir `nProbs`).
 */
@AndroidEntryPoint
internal class VoletProblemesFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_PROBLEMES

    private lateinit var liaison: FragmentVoletProblemesBinding

    /** La liste (rangées + états), recyclée — le tap sélectionne. */
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
        adaptateur.submitList(problemesMarches(vues, problemes))
    }

    /** Les entrées affichables (données pures) avec leurs textes résolus. */
    private fun problemesMarches(
        vues: List<InfoVueRendu>,
        problemes: List<ProblemesPreview.ProblemeApercu>,
    ): List<EntreeProblemeUi> =
        if (problemes.isEmpty()) {
            listOf(EntreeProblemeUi.Vide(getString(R.string.preview_feuille_aucun_probleme)))
        } else {
            FeuilleApercu.entreesProblemes(problemes).map { entree ->
                EntreeProblemeUi.Ligne(
                    entree = entree,
                    titre = titreProbleme(entree.genre, vues.getOrNull(entree.indiceVue)),
                    detail = detailProbleme(entree, vues.getOrNull(entree.indiceVue)),
                )
            }
        }

    /** Le titre localisé d'un problème (miroir `p.title` de `computeProblems`). */
    private fun titreProbleme(
        genre: ProblemesPreview.GenreProbleme,
        vueFautive: InfoVueRendu?,
    ): String =
        when (genre) {
            ProblemesPreview.GenreProbleme.CLASSE_ABSENTE -> {
                getString(R.string.preview_probleme_classe_absente_titre, vueFautive?.classe ?: "")
            }

            ProblemesPreview.GenreProbleme.CIBLE_TACTILE -> {
                getString(R.string.preview_probleme_cible_tactile_titre)
            }
        }

    /** Le détail localisé (vue fautive + message, miroir `p.msg`). */
    private fun detailProbleme(
        entree: FeuilleApercu.EntreeProbleme,
        vueFautive: InfoVueRendu?,
    ): String {
        val libelle =
            vueFautive?.let { LibellesVolet.libelleVue(requireContext(), it) } ?: ""
        val message =
            when (entree.genre) {
                ProblemesPreview.GenreProbleme.CLASSE_ABSENTE -> {
                    getString(R.string.preview_probleme_classe_absente_message)
                }

                ProblemesPreview.GenreProbleme.CIBLE_TACTILE -> {
                    val densite = viewModel.moteur.profil.densiteDpi
                    if (vueFautive != null && densite > 0) {
                        getString(
                            R.string.preview_probleme_cible_tactile_message,
                            CalquesApercu.pxVersDp(vueFautive.largeurPx, densite),
                            CalquesApercu.pxVersDp(vueFautive.hauteurPx, densite),
                        )
                    } else {
                        getString(R.string.preview_probleme_cible_tactile_message_sans_mesure)
                    }
                }
            }
        return "$libelle\n$message"
    }
}

/** UNE entrée de l'onglet Problèmes : rangée résolue, ou état vide. */
internal sealed class EntreeProblemeUi {
    /** Le message « aucun problème ». */
    internal data class Vide(
        val message: String,
    ) : EntreeProblemeUi()

    /** Une rangée résolue (entrée pure + textes locaux déjà résolus). */
    internal data class Ligne(
        val entree: FeuilleApercu.EntreeProbleme,
        val titre: String,
        val detail: String,
    ) : EntreeProblemeUi()
}

/**
 * L'adaptateur de l'onglet Problèmes (miroir `renderProbs`) : rangée =
 * item layout RÉEL (`item_probleme.xml` — pastille teintée au liage,
 * titre, détail), la sélection part du tap.
 */
internal class AdaptateurProblemes(
    private val auLigne: (Int) -> Unit,
) : ListAdapter<EntreeProblemeUi, RecyclerView.ViewHolder>(ComparateurProblemes) {
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
                LigneProblemeVue(ItemProblemeBinding.inflate(gonfleur, parent, false), auLigne)
            }
        }
    }

    override fun getItemCount(): Int = currentList.size

    override fun getItemViewType(position: Int): Int =
        when (currentList[position]) {
            is EntreeProblemeUi.Vide -> TYPE_VIDE
            is EntreeProblemeUi.Ligne -> TYPE_LIGNE
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = currentList[position]) {
            is EntreeProblemeUi.Vide -> (holder.itemView as android.widget.TextView).text = item.message
            is EntreeProblemeUi.Ligne -> (holder as LigneProblemeVue).poser(item)
        }
    }

    /** Le conteneur d'une rangée (item_probleme.xml). */
    private class LigneProblemeVue(
        private val liaison: ItemProblemeBinding,
        auLigne: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var indiceVue: Int = -1

        init {
            liaison.root.setOnClickListener { auLigne(indiceVue) }
        }

        fun poser(item: EntreeProblemeUi.Ligne) {
            indiceVue = item.entree.indiceVue
            val contexte = liaison.root.context
            val couleurPastille =
                ContextCompat.getColor(
                    contexte,
                    if (item.entree.erreur) R.color.preview_probleme_erreur else R.color.preview_probleme_avertissement,
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

        private fun EntreeProblemeUi.cle(): String =
            when (this) {
                is EntreeProblemeUi.Vide -> "vide"
                is EntreeProblemeUi.Ligne -> "ligne-${entree.indiceVue}-${entree.genre}"
            }
    }

    private companion object {
        private const val TYPE_VIDE: Int = 0
        private const val TYPE_LIGNE: Int = 1
    }
}
