package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.settings.databinding.FragmentEnvironnementBinding
import kotlinx.coroutines.launch

/**
 * Écran Environnement des Paramètres (E5, § 7 — ADR 0089) : versions,
 * tailles et état vérifié par composant ; actions Vérifier
 * (légère/approfondie — projet généré + `assembleDebug` réel, ADR
 * 0089 § 7), Réparer, Désinstaller un composant (avec confirmation) et
 * accès au diagnostic (journal en pied d'écran, copiable).
 */
@AndroidEntryPoint
class EnvironnementFragment : Fragment() {
    private val viewModel: EnvironnementViewModel by viewModels()

    private var liaisonPrivee: FragmentEnvironnementBinding? = null
    private val liaison: FragmentEnvironnementBinding get() = liaisonPrivee!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaisonPrivee = FragmentEnvironnementBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val adaptateur =
            ComposantsEnvAdapter { composant ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.environnement_desinstaller_titre, composant.id))
                    .setMessage(getString(R.string.environnement_desinstaller_texte, composant.id))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                        R.string.environnement_desinstaller,
                    ) { _, _ -> viewModel.desinstaller(composant.id) }
                    .show()
            }
        liaison.listeComposants.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeComposants.adapter = adaptateur
        liaison.boutonVerifier.setOnClickListener { viewModel.verifier(profonde = false) }
        liaison.boutonVerifierProfond.setOnClickListener { viewModel.verifier(profonde = true) }
        liaison.boutonReparer.setOnClickListener { viewModel.reparer() }
        liaison.boutonDiagnostic.setOnClickListener { copierDiagnostic() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.composants.collect { composants ->
                        adaptateur.submitList(composants)
                        liaison.boutonReparer.isVisible = composants.isNotEmpty()
                    }
                }
                launch {
                    viewModel.journal.collect { lignes ->
                        liaison.journalEnvironnement.text = lignes.takeLast(NB_LIGNES).joinToString("\n")
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonPrivee = null
    }

    /** Copie le diagnostic expurgé (journal + récapitulatif, § 7-8). */
    private fun copierDiagnostic() {
        val pressePapiers =
            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
        pressePapiers.setPrimaryClip(
            android.content.ClipData.newPlainText(
                getString(R.string.environnement_diagnostic),
                viewModel.diagnostic(),
            ),
        )
    }

    private companion object {
        private const val NB_LIGNES: Int = 12
    }
}
