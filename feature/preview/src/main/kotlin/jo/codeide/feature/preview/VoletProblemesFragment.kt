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
import jo.codeide.preview.xml.ProblemesFamilles
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
 *
 * PE12f — les CINQ genres de FAMILLE s'y AJOUTENT, lus dans le TEXTE
 * vivant (spec § 9) : ils restent listés quand le rendu est en erreur
 * (la boucle § 12 se lit alors que le dernier rendu valide est
 * conservé) ; leur tap vise la vue fautive du dernier rendu VALIDE par
 * le début d'élément, sans effet sans correspondance.
 */
@AndroidEntryPoint
internal class VoletProblemesFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_PROBLEMES

    private lateinit var liaison: FragmentVoletProblemesBinding

    /** La liste (rangées recyclées), le tap sélectionne. */
    private val adaptateur = AdaptateurProblemes { indice -> viewModel.selectionner(indice) }

    override fun onCreateView(inflater: LayoutInflater, conteneur: ViewGroup?, savedInstanceState: Bundle?): View {
        liaison = FragmentVoletProblemesBinding.inflate(inflater, conteneur, false)
        liaison.listeProblemes.adapter = adaptateur
        liaison.listeProblemes.layoutManager = LinearLayoutManager(requireContext())
        return liaison.root
    }

    override fun marcher() {
        val vues = renduCourant?.vues.orEmpty()
        val problemes = ProblemesPreview.evaluer(vues, viewModel.moteur.profil.densiteDpi)
        val entrees = problemesMarches(vues, problemes) + famillesMarchees(viewModel.problemesFamilles())
        // Miroir renderProbs : liste VIDE → le message « Aucun problème
        // détecté… » du LAYOUT (plus d'entrée « vide » dans l'adaptateur).
        liaison.messageAucunProbleme.isVisible = entrees.isEmpty()
        adaptateur.submitList(entrees)
    }

    /** Les entrées affichables (données pures) avec leurs textes résolus. */
    private fun problemesMarches(
        vues: List<InfoVueRendu>,
        problemes: List<ProblemesPreview.ProblemeApercu>,
    ): List<EntreeProblemeUi> = FeuilleApercu.entreesProblemes(problemes).map { entree ->
        EntreeProblemeUi(
            entree = entree,
            titre = titreProbleme(entree.genre, vues.getOrNull(entree.indiceVue)),
            detail = detailProbleme(entree, vues.getOrNull(entree.indiceVue)),
        )
    }

    /**
     * Les entrées des problèmes de FAMILLE (PE12f — spec § 9) : libellé
     * de la vue fautive lu dans le TEXTE (balise + identifiant), tap →
     * vue fautive du dernier rendu VALIDE par le début d'élément — sans
     * correspondance (rendu périmé) le tap ne sélectionne RIEN.
     */
    private fun famillesMarchees(familles: List<ProblemesFamilles.Probleme>): List<EntreeProblemeUi> {
        val vues = renduCourant?.vues.orEmpty()
        return familles.map { probleme ->
            val indice = vues.indexOfFirst { it.debutElement == probleme.debut }
            EntreeProblemeUi(
                entree =
                FeuilleApercu.EntreeProbleme(
                    indiceVue = if (indice >= 0) indice else SANS_VUE,
                    erreur = probleme.severite == ProblemesPreview.Severite.ERREUR,
                    genre = probleme.genre,
                ),
                titre = LibellesProblemes.titreFamille(requireContext(), probleme),
                detail =
                libelleFamille(probleme) + "\n" +
                    LibellesProblemes.messageFamille(requireContext(), probleme),
            )
        }
    }

    /**
     * Le libellé de la vue fautive d'un problème de FAMILLE (« Classe ·
     * @+id/x » — même formulation que [LibellesVolet.libelleVue], lue du
     * texte : le rendu peut être en erreur).
     */
    private fun libelleFamille(probleme: ProblemesFamilles.Probleme): String =
        probleme.id?.let { getString(R.string.preview_feuille_libelle, probleme.balise, it) } ?: probleme.balise

    /** Le titre localisé d'un problème (miroir `p.title` de `computeProblems`). */
    private fun titreProbleme(genre: ProblemesPreview.GenreProbleme, vueFautive: InfoVueRendu?): String =
        LibellesProblemes.titre(requireContext(), genre, vueFautive)

    /** Le détail localisé (vue fautive + message, miroir `p.msg`). */
    private fun detailProbleme(entree: FeuilleApercu.EntreeProbleme, vueFautive: InfoVueRendu?): String {
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

/** Indice d'une entrée SANS vue correspondante (le tap ne sélectionne rien — rendu périmé). */
private const val SANS_VUE: Int = -1

/**
 * L'adaptateur de l'onglet Problèmes (miroir `renderProbs`) : rangée =
 * item layout RÉEL (`item_probleme.xml` — point de sévérité 8 dp teinté
 * au liage : ambre/erreur, titre, détail, « Voir »), la sélection part
 * du tap sur la rangée entière (miroir `d.onclick → select(p.rid)`).
 */
internal class AdaptateurProblemes(private val auLigne: (Int) -> Unit) :
    ListAdapter<EntreeProblemeUi, AdaptateurProblemes.LigneProblemeVue>(ComparateurProblemes) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LigneProblemeVue {
        val gonfleur = LayoutInflater.from(parent.context)
        return LigneProblemeVue(ItemProblemeBinding.inflate(gonfleur, parent, false), auLigne)
    }

    override fun getItemCount(): Int = currentList.size

    override fun onBindViewHolder(holder: LigneProblemeVue, position: Int) {
        holder.poser(currentList[position])
    }

    /** Le conteneur d'une rangée (item_probleme.xml). */
    internal class LigneProblemeVue(private val liaison: ItemProblemeBinding, auLigne: (Int) -> Unit) :
        RecyclerView.ViewHolder(liaison.root) {
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
        override fun areItemsTheSame(ancienne: EntreeProblemeUi, nouvelle: EntreeProblemeUi): Boolean =
            ancienne.cle() == nouvelle.cle()

        override fun areContentsTheSame(ancienne: EntreeProblemeUi, nouvelle: EntreeProblemeUi): Boolean =
            ancienne == nouvelle

        private fun EntreeProblemeUi.cle(): String = "ligne-${entree.indiceVue}-${entree.genre}"
    }
}
