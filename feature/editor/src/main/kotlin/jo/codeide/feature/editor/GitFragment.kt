package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.StatutFichier
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentGitBinding

/**
 * Fragment Git du tiroir (mission Git G2, ADR 0092) : entête avec branche
 * courante, champ de message de commit, liste des changements, bouton Commit.
 *
 * Structure VS Code + vocabulaire/icônes Android Studio. Le ViewModel
 * délègue au [jo.codeide.core.domain.MoteurGit] qui exécute le binaire
 * `git` via le pont FUSE.
 */
@AndroidEntryPoint
class GitFragment : Fragment() {
    private val viewModel: GitViewModel by viewModels()

    private var liaisonAmorce: FragmentGitBinding? = null
    private val liaison: FragmentGitBinding
        get() = checkNotNull(liaisonAmorce) { "Binding détruit" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonAmorce = FragmentGitBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonActualiserGit.setOnClickListener { viewModel.rafraichir() }
        liaison.boutonInitialiserGit.setOnClickListener { viewModel.initialiser() }
        liaison.boutonCommitter.setOnClickListener { viewModel.committer() }
        liaison.messageCommit.setOnEditorActionListener { _, _, _ ->
            if (viewModel.etat.value.commitPossible) {
                viewModel.committer()
                true
            } else {
                false
            }
        }
        liaison.messageCommit.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.messageCommit(s?.toString() ?: "")
                }
            },
        )
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    private fun rendre(etat: EtatGit) {
        // v0.80.4 : pendant l'initialisation, la zone « pas un dépôt »
        // s'efface au profit du repère de chargement — le bouton ne peut
        // plus être martelé pendant un git init en cours.
        liaison.chargementGit.isVisible = etat.chargement
        liaison.zonePasDepot.isVisible = etat.pasDepot && !etat.chargement
        liaison.boutonInitialiserGit.isEnabled = !etat.chargement
        liaison.corpsGit.isVisible = !etat.chargement && !etat.pasDepot
        liaison.boutonCommitter.isVisible = !etat.chargement && !etat.pasDepot

        liaison.brancheGit.text = etat.branche
        liaison.brancheGit.isVisible = etat.branche != null

        val nb = etat.nbChangements
        liaison.resumeChangements.isVisible = !etat.chargement && !etat.pasDepot && etat.statut != null
        liaison.resumeChangements.text =
            if (nb == 0) {
                getString(R.string.git_aucun_changement)
            } else {
                resources.getQuantityString(R.plurals.git_n_changements, nb, nb)
            }

        liaison.listeChangements.isVisible = nb > 0 && etat.statut != null
        liaison.listeChangements.text =
            etat.statut?.modifications?.joinToString("\n") { mod ->
                val code = codeStatutAffichage(mod.statutIndex, mod.statutTravail)
                "$code ${mod.chemin}"
            }

        liaison.erreurGit.isVisible = etat.erreur != null
        liaison.erreurGit.text = etat.erreur

        liaison.boutonCommitter.isEnabled = etat.commitPossible
    }

    /** Code d'affichage du statut (lettre + couleur, format Android Studio). */
    private fun codeStatutAffichage(
        index: StatutFichier,
        travail: StatutFichier,
    ): String =
        when {
            index == StatutFichier.AJOUTE -> "A"
            index == StatutFichier.MODIFIE -> "M"
            index == StatutFichier.SUPPRIME -> "D"
            index == StatutFichier.RENOMME -> "R"
            travail == StatutFichier.MODIFIE -> "M"
            travail == StatutFichier.NON_SUIVI -> "U"
            travail == StatutFichier.CONFLIT -> "C"
            else -> " "
        }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonAmorce = null
    }
}
