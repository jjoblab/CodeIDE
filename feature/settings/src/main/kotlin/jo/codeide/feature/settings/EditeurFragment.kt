package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.TaillePoliceEditeur
import jo.codeide.core.model.TailleTabulation
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsEditeurBinding
import javax.inject.Inject

/**
 * Section Éditeur (ADR 0059) : entièrement créée — taille de police,
 * retour à la ligne, numéros de ligne, surlignage de la ligne actuelle,
 * taille de tabulation et sauvegarde automatique. Les réglages sont
 * **persistés avant consommation** : le moteur d'édition (cel-ui) s'y
 * abonnera quand la coloration/indentation intelligente existera.
 */
@AndroidEntryPoint
class EditeurFragment : BaseFragment<FragmentSettingsEditeurBinding>() {
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
    ): FragmentSettingsEditeurBinding = FragmentSettingsEditeurBinding.inflate(inflater, container, attachToRoot)

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
        binding.groupePoliceEditeur.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerTaillePoliceEditeur(tailleDeLId(id)))
            }
        }
        binding.groupeTabulation.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerTailleTabulation(tabulationDeLId(id)))
            }
        }
        binding.interrupteurRetourLigne.setOnCheckedChangeListener { _, actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerRetourLigne(actif))
        }
        binding.interrupteurNumerosLigne.setOnCheckedChangeListener { _, affiches ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerNumerosLigne(affiches))
        }
        binding.interrupteurSurlignerLigne.setOnCheckedChangeListener { _, actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSurlignageLigne(actif))
        }
        binding.interrupteurSauvegardeAuto.setOnCheckedChangeListener { _, activee ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSauvegardeAuto(activee))
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            when (reglages.editorTaillePolice) {
                TaillePoliceEditeur.PETITE -> binding.groupePoliceEditeur.check(R.id.bouton_police_editeur_1)
                TaillePoliceEditeur.MOYENNE -> binding.groupePoliceEditeur.check(R.id.bouton_police_editeur_2)
                TaillePoliceEditeur.GRANDE -> binding.groupePoliceEditeur.check(R.id.bouton_police_editeur_3)
            }
            when (reglages.editorTailleTabulation) {
                TailleTabulation.DEUX -> binding.groupeTabulation.check(R.id.bouton_tabulation_1)
                TailleTabulation.QUATRE -> binding.groupeTabulation.check(R.id.bouton_tabulation_2)
                TailleTabulation.HUIT -> binding.groupeTabulation.check(R.id.bouton_tabulation_3)
            }
            binding.interrupteurRetourLigne.isChecked = reglages.editorRetourLigne
            binding.interrupteurNumerosLigne.isChecked = reglages.editorNumerosLigne
            binding.interrupteurSurlignerLigne.isChecked = reglages.editorSurlignerLigneActuelle
            binding.interrupteurSauvegardeAuto.isChecked = reglages.editorSauvegardeAuto
        } finally {
            renduEnCours = false
        }
    }
}

/** Taille de police éditeur du bouton coché (MOYENNE par défaut). */
private fun tailleDeLId(id: Int): TaillePoliceEditeur =
    when (id) {
        R.id.bouton_police_editeur_1 -> TaillePoliceEditeur.PETITE
        R.id.bouton_police_editeur_3 -> TaillePoliceEditeur.GRANDE
        else -> TaillePoliceEditeur.MOYENNE
    }

/** Largeur de tabulation du bouton coché (QUATRE par défaut). */
private fun tabulationDeLId(id: Int): TailleTabulation =
    when (id) {
        R.id.bouton_tabulation_1 -> TailleTabulation.DEUX
        R.id.bouton_tabulation_3 -> TailleTabulation.HUIT
        else -> TailleTabulation.QUATRE
    }
