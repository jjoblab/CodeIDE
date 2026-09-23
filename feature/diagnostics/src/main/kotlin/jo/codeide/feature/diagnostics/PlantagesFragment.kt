package jo.codeide.feature.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.diagnostics.databinding.PagePlantagesBinding
import javax.inject.Inject

/**
 * Onglet Plantages de l'écran Diagnostic (étape 12) : historique des
 * rapports conservés — l'appui ouvre l'écran dédié en consultation (mode
 * VIEW via [AppNavigator]), chaque ligne porte sa suppression, les actions
 * globales exportent ou vident l'historique (confirmations explicites).
 */
@AndroidEntryPoint
class PlantagesFragment : BaseFragment<PagePlantagesBinding>() {
    private val viewModel: PlantagesViewModel by viewModels()

    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    private val adaptateurRapports =
        RapportsPlantageAdapter(
            surOuverture = { id -> navigator.openCrashReport(id) },
            surSuppression = { id -> confirmerSuppression(id) },
        )

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PagePlantagesBinding = PagePlantagesBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.listeRapports.layoutManager = LinearLayoutManager(requireContext())
        binding.listeRapports.adapter = adaptateurRapports

        binding.boutonExporter.setOnClickListener {
            viewModel.onAction(ActionPlantages.Exporter)
        }
        binding.boutonToutSupprimer.setOnClickListener { confirmerToutSupprimer() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet -> appliquer(effet) }
    }

    /** Rend l'état de l'historique. */
    private fun rendre(etat: EtatPlantages) {
        adaptateurRapports.submitList(etat.rapports)
        binding.progression.isVisible = etat.chargement
        binding.texteVide.isVisible = !etat.chargement && etat.rapports.isEmpty()
        binding.texteErreur.isVisible = etat.erreur != null
        etat.erreur?.let { erreur ->
            binding.texteErreur.text = getString(TraductionsDiagnostic.message(erreur))
        }
        // Exporter sans rapport produirait une archive vide : l'action
        // se désactive quand il n'y a rien à archiver.
        binding.boutonExporter.isEnabled = etat.rapports.isNotEmpty()
        binding.boutonToutSupprimer.isEnabled = etat.rapports.isNotEmpty()
    }

    /** Applique un effet ponctuel. */
    private fun appliquer(effet: EffetPlantages) {
        when (effet) {
            is EffetPlantages.ArchivePrete -> {
                navigator.partagerArchive(effet.archive.fileName, effet.archive.location)
            }
        }
    }

    /** Confirmation explicite avant la suppression d'un rapport. */
    private fun confirmerSuppression(id: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_supprimer_titre)
            .setMessage(R.string.diagnostics_supprimer_message)
            .setNegativeButton(R.string.diagnostics_annuler, null)
            .setPositiveButton(R.string.diagnostics_supprimer_confirmer) { dialogue, _ ->
                dialogue.dismiss()
                viewModel.onAction(ActionPlantages.Supprimer(id))
            }.show()
    }

    /** Confirmation explicite avant la suppression de tous les rapports. */
    private fun confirmerToutSupprimer() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_tout_supprimer_titre)
            .setMessage(R.string.diagnostics_tout_supprimer_message)
            .setNegativeButton(R.string.diagnostics_annuler, null)
            .setPositiveButton(R.string.diagnostics_tout_supprimer_confirmer) { dialogue, _ ->
                dialogue.dismiss()
                viewModel.onAction(ActionPlantages.ToutSupprimer)
            }.show()
    }
}
