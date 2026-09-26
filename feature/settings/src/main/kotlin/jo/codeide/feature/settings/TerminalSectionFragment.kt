package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StyleCurseurTerminal
import jo.codeide.core.model.TaillePoliceTerminal
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsTerminalBinding
import javax.inject.Inject

/**
 * Section Terminal (ADR 0059) : la taille de police migre depuis
 * l'ancienne carte Apparence ; le style du curseur (bloc / ligne /
 * barre) et la copie automatique de la sélection s'y ajoutent — le
 * rendu consomme le style via le client de session Termux, la copie via
 * `ClientVueTerminal`.
 */
@AndroidEntryPoint
class TerminalSectionFragment : BaseFragment<FragmentSettingsTerminalBinding>() {
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
    ): FragmentSettingsTerminalBinding = FragmentSettingsTerminalBinding.inflate(inflater, container, attachToRoot)

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
        binding.groupePoliceTerminal.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerTaillePoliceTerminal(tailleDeLId(id)))
            }
        }
        binding.groupeCurseur.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerStyleCurseur(styleDeLId(id)))
            }
        }
        binding.interrupteurCopieSelection.setOnCheckedChangeListener { _, activee ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerCopieSelection(activee))
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            when (reglages.taillePoliceTerminal) {
                TaillePoliceTerminal.PETITE -> binding.groupePoliceTerminal.check(R.id.bouton_police_terminal_1)
                TaillePoliceTerminal.MOYENNE -> binding.groupePoliceTerminal.check(R.id.bouton_police_terminal_2)
                TaillePoliceTerminal.GRANDE -> binding.groupePoliceTerminal.check(R.id.bouton_police_terminal_3)
            }
            when (reglages.styleCurseurTerminal) {
                StyleCurseurTerminal.BLOC -> binding.groupeCurseur.check(R.id.bouton_curseur_1)
                StyleCurseurTerminal.LIGNE -> binding.groupeCurseur.check(R.id.bouton_curseur_2)
                StyleCurseurTerminal.BARRE -> binding.groupeCurseur.check(R.id.bouton_curseur_3)
            }
            binding.interrupteurCopieSelection.isChecked = reglages.copieSelectionAuto
        } finally {
            renduEnCours = false
        }
    }
}

/** Taille de police du terminal du bouton coché (MOYENNE par défaut). */
private fun tailleDeLId(id: Int): TaillePoliceTerminal =
    when (id) {
        R.id.bouton_police_terminal_1 -> TaillePoliceTerminal.PETITE
        R.id.bouton_police_terminal_3 -> TaillePoliceTerminal.GRANDE
        else -> TaillePoliceTerminal.MOYENNE
    }

/** Style de curseur du bouton coché (BLOC par défaut). */
private fun styleDeLId(id: Int): StyleCurseurTerminal =
    when (id) {
        R.id.bouton_curseur_2 -> StyleCurseurTerminal.LIGNE
        R.id.bouton_curseur_3 -> StyleCurseurTerminal.BARRE
        else -> StyleCurseurTerminal.BLOC
    }
