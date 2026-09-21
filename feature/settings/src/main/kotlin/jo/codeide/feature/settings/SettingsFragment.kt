package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.feature.settings.databinding.FragmentSettingsBinding
import javax.inject.Inject

/**
 * Écran Paramètres.
 *
 * Étape 1 : écran provisoire avec barre d'outils et retour, qui prouve
 * l'aller-retour Home ↔ Settings via [AppNavigator]. L'écran complet
 * (apparence, langue, projets, à propos, avancé — piloté par DataStore)
 * arrive à l'étape 6 (section 11 du prompt maître).
 */
@AndroidEntryPoint
class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {
    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsBinding = FragmentSettingsBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe les barres système.
        binding.root.applySystemBarsInsets(top = true, bottom = true)

        binding.settingsToolbar.setNavigationOnClickListener {
            navigator.goBack()
        }
    }
}
