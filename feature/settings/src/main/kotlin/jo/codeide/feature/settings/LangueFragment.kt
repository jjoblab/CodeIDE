package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsLangueBinding
import javax.inject.Inject

/**
 * Section Langue (ADR 0059) : système, français ou anglais — la
 * locale applicative est pilotée par `MainActivity` (ADR 0013), le
 * réglage se persiste immédiatement.
 */
@AndroidEntryPoint
class LangueFragment : BaseFragment<FragmentSettingsLangueBinding>() {
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
    ): FragmentSettingsLangueBinding = FragmentSettingsLangueBinding.inflate(inflater, container, attachToRoot)

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
        binding.groupeLangue.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerLangue(langueDeLId(id)))
            }
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            when (reglages.languageTag) {
                "fr" -> binding.groupeLangue.check(R.id.bouton_langue_2)
                "en" -> binding.groupeLangue.check(R.id.bouton_langue_3)
                else -> binding.groupeLangue.check(R.id.bouton_langue_1)
            }
        } finally {
            renduEnCours = false
        }
    }
}

/** Tag BCP 47 du bouton de langue — l'ordre suit le gabarit du groupe. */
private fun langueDeLId(id: Int): String =
    when (id) {
        R.id.bouton_langue_2 -> "fr"
        R.id.bouton_langue_3 -> "en"
        else -> ""
    }
