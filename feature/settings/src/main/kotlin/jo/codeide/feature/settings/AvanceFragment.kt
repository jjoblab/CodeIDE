package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsAvanceBinding
import javax.inject.Inject

/**
 * Section Avancé (ADR 0059) : diagnostic (journaux et plantages),
 * réinitialisation des préférences (ligne destructive, confirmation
 * obligatoire) et relance de l'assistant de premier lancement — logique
 * inchangée depuis l'ancien écran unique, présentation dédiée.
 */
@AndroidEntryPoint
class AvanceFragment : BaseFragment<FragmentSettingsAvanceBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsAvanceBinding = FragmentSettingsAvanceBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        binding.ligneDiagnostic.setOnClickListener { navigator.openDiagnostics() }
        binding.ligneReinitialiser.setOnClickListener { confirmerReinitialisation() }
        binding.ligneRelancerAssistant.setOnClickListener {
            viewModel.onAction(ActionParametres.RelancerAssistant)
        }

        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            if (effet == EffetParametres.OuvrirAssistant) navigator.openOnboarding()
        }
    }

    /** Confirmation avant la réinitialisation des préférences. */
    private fun confirmerReinitialisation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reinitialiser_titre)
            .setMessage(R.string.settings_reinitialiser_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_reinitialiser_confirmer) { _, _ ->
                viewModel.onAction(ActionParametres.ReinitialiserPreferences)
            }.show()
    }
}
