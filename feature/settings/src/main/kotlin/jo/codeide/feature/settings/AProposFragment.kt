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
import jo.codeide.feature.settings.databinding.FragmentSettingsAproposBinding
import javax.inject.Inject

/**
 * Section À propos (ADR 0059) : version, type de build et licences open
 * source des dépendances embarquées — logique inchangée depuis l'ancien
 * écran unique, présentation dédiée.
 */
@AndroidEntryPoint
class AProposFragment : BaseFragment<FragmentSettingsAproposBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsAproposBinding = FragmentSettingsAproposBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        binding.ligneLicences.setOnClickListener { ouvrirLicences() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            binding.texteVersion.text =
                getString(
                    R.string.settings_a_propos_version,
                    etat.infosBuild.versionName,
                    etat.infosBuild.versionCode,
                )
            binding.texteBuild.text =
                getString(R.string.settings_a_propos_build, etat.infosBuild.buildType)
        }
    }

    /** Licences open source des dépendances embarquées (texte embarqué). */
    private fun ouvrirLicences() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_licences_titre)
            .setMessage(R.string.settings_licences_contenu)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
