package jo.codeide.feature.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.diagnostics.databinding.PageDiagnosticGitBinding

/**
 * Onglet « Git » de l'écran Diagnostic (v0.90.1, mission « section Git
 * figée » étape A) : rapport observable de l'environnement d'exécution
 * de git pour le projet le plus récemment ouvert.
 *
 * L'affichage porte les valeurs réelles (chemins, uid) — c'est la
 * matière du diagnostic ; le bouton Copier colle la version **expurgée**
 * (chemins, courriels, identités et jetons masqués — règle 15 : aucune
 * donnée personnelle dans la sortie copiée).
 */
@AndroidEntryPoint
class DiagnosticGitFragment : Fragment() {
    private val viewModel: DiagnosticGitViewModel by viewModels()

    private var liaisonAmorce: PageDiagnosticGitBinding? = null
    private val liaison: PageDiagnosticGitBinding
        get() = checkNotNull(liaisonAmorce) { "Binding détruit" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonAmorce = PageDiagnosticGitBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        liaison.boutonRelancerDiagnosticGit.setOnClickListener { viewModel.relancer() }
        liaison.boutonCopierDiagnosticGit.setOnClickListener { copier() }
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    private fun rendre(etat: EtatDiagnosticGit) {
        liaison.chargementDiagnosticGit.isVisible = etat.chargement
        liaison.texteDiagnosticGit.isVisible = !etat.chargement
        liaison.defilementDiagnosticGit.isVisible = !etat.chargement
        liaison.boutonCopierDiagnosticGit.isEnabled = etat.texteCopie != null
        etat.texteAffiche?.let { liaison.texteDiagnosticGit.text = it }
    }

    /**
     * Copie le rapport EXPURGÉ dans le presse-papiers — retour visible
     * par snackbar (aucune donnée personnelle ne quitte l'écran sans
     * masquage, règle 15 du prompt maître).
     */
    private fun copier() {
        val texte = viewModel.etat.value.texteCopie ?: return
        val pressePapiers =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        pressePapiers.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.diagnostics_git_titre), texte),
        )
        Snackbar.make(liaison.root, R.string.diagnostics_git_copie, Snackbar.LENGTH_SHORT).show()
    }
}
