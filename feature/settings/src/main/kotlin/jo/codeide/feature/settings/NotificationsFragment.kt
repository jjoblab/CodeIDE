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
import jo.codeide.feature.settings.databinding.FragmentSettingsNotificationsBinding
import javax.inject.Inject

/**
 * Section Notifications (ADR 0059) : interrupteurs par canal
 * (Synchronisation, Build) et réglage Son — `DecisionNotificationTooling`
 * (feature:editor) consomme ces réglages via le service de notification,
 * la persistance est identique aux autres réglages.
 */
@AndroidEntryPoint
class NotificationsFragment : BaseFragment<FragmentSettingsNotificationsBinding>() {
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
    ): FragmentSettingsNotificationsBinding =
        FragmentSettingsNotificationsBinding.inflate(inflater, container, attachToRoot)

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
        binding.rangeeNotifSync.setSurChangementCoche { actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerNotificationsSync(actif))
        }
        binding.rangeeNotifBuild.setSurChangementCoche { actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerNotificationsBuild(actif))
        }
        binding.rangeeNotifSon.setSurChangementCoche { actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSonNotifications(actif))
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            binding.rangeeNotifSync.coche = reglages.notificationsSync
            binding.rangeeNotifBuild.coche = reglages.notificationsBuild
            binding.rangeeNotifSon.coche = reglages.sonNotifications
        } finally {
            renduEnCours = false
        }
    }
}
