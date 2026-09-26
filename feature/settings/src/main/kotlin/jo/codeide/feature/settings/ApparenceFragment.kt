package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsApparenceBinding
import javax.inject.Inject

/**
 * Section Apparence (ADR 0059) : mode de thème, couleurs dynamiques
 * et **aperçu de palette** — bande de pastilles primaire / secondaire /
 * tertiaire réactive à l'état du switch (atténuée quand les couleurs
 * dynamiques sont coupées), façon sélecteur de style Android.
 */
@AndroidEntryPoint
class ApparenceFragment : BaseFragment<FragmentSettingsApparenceBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsApparenceBinding = FragmentSettingsApparenceBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        brancher()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.reglage) }
    }

    /** Branchements des contrôles → actions (une seule fois). */
    private fun brancher() {
        binding.groupeTheme.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerTheme(modeDuTheme(id)))
            }
        }
        binding.interrupteurCouleursDynamiques.setOnCheckedChangeListener { _, active ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerCouleursDynamiques(active))
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            when (reglages.themeMode) {
                ThemeMode.SYSTEM -> binding.groupeTheme.check(R.id.bouton_theme_1)
                ThemeMode.LIGHT -> binding.groupeTheme.check(R.id.bouton_theme_2)
                ThemeMode.DARK -> binding.groupeTheme.check(R.id.bouton_theme_3)
            }
            binding.interrupteurCouleursDynamiques.isChecked = reglages.useDynamicColor
            // Aperçu réactif : atténué quand les couleurs dynamiques sont
            // coupées — les pastilles montrent la palette du thème courant
            // (dynamique ou de marque) telle quelle.
            binding.apercuPalette.alpha = if (reglages.useDynamicColor) 1f else ALPHA_PALETTE_ETEINTE
        } finally {
            renduEnCours = false
        }
    }
}

/** Thème correspondant au bouton segmenté coché (SYSTÈME par défaut). */
private fun modeDuTheme(id: Int): ThemeMode =
    when (id) {
        R.id.bouton_theme_2 -> ThemeMode.LIGHT
        R.id.bouton_theme_3 -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

/** Opacité de l'aperçu de palette quand les couleurs dynamiques sont coupées. */
private const val ALPHA_PALETTE_ETEINTE = 0.35f
