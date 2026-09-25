package jo.codeide.feature.editor

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauProblemesBinding

/**
 * Onglet Problèmes du panneau inférieur (G5 §6, v0.32.4 ADR 0055) :
 * diagnostics groupés par fichier, saut à la ligne via le contrat
 * [ControleurPanneauEditeur] (implémenté par l'hôte, cast contrôlé dans
 * `onAttach` — même pattern que le Terminal du tiroir, ADR 0053).
 *
 * Le contenu migrent du layout empilé de l'activité (v0.32.3) vers ce
 * fragment ; le fragment collecte lui-même l'état tooling.
 */
class PanneauProblemesFragment : Fragment() {
    private var liaisonAmorce: FragmentPanneauProblemesBinding? = null
    private val liaison get() = liaisonAmorce!!

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Commandes à l'hôte (saut à la ligne dans l'éditeur). */
    private lateinit var controleur: ControleurPanneauEditeur

    /** Diagnostics groupés. */
    private lateinit var adaptateur: ProblemesAdapter

    override fun onAttach(contexte: Context) {
        super.onAttach(contexte)
        val hote = activity as? ControleurPanneauEditeur
        require(hote != null) {
            "L'hôte du panneau doit implémenter ControleurPanneauEditeur"
        }
        controleur = hote
    }

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauProblemesBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        adaptateur =
            ProblemesAdapter { fichier, ligne ->
                controleur.sauterAuProbleme(fichier, ligne)
            }
        liaison.listeProblemes.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeProblemes.adapter = adaptateur

        viewModel.etatGradle.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rend les groupes aplatis (le compte par fichier vit dans les
     *  en-têtes de groupes) et l'état vide. */
    private fun rendre(etat: EtatGradle) {
        adaptateur.submitList(etat.groupesProblemes.aplatir())
        liaison.texteProblemesVide.isVisible = etat.problemesTotal == 0
    }
}
