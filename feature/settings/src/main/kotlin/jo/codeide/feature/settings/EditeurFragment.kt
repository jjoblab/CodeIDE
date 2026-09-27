package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.TaillePoliceEditeur
import jo.codeide.core.model.ThemeEditeur
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsEditeurBinding
import javax.inject.Inject

/**
 * Section Éditeur (ADR 0059 ; v0.37.0) : les réglages sont **consommés au
 * fil de l'eau** par l'éditeur (`OptionsEditeur`, feature:editor) — chaque
 * contrôle projette une API réelle de cel-ui. Trois cartes à libellé de
 * section, comme le maître des Paramètres : Apparence de l'éditeur (thème
 * de coloration parmi les neuf embarqués de la bibliothèque + automatique,
 * taille de police, ligatures), Affichage (retour à la ligne, minimap,
 * caractères non imprimables, badges de diagnostic) et Édition (sauvegarde
 * automatique).
 *
 * Les réglages « persistés avant consommation » d'ADR 0059 que cel-ui
 * n'honore pas (numéros de ligne et bandeau de ligne courante toujours
 * dessinés, indentation auto-détectée par fichier) ont été retirés : la
 * section n'affiche plus que des contrôles réels.
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
        binding.rangeeThemeEditeur.setOnClickListener {
            ouvrirChoixTheme()
        }
        binding.groupePoliceEditeur.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) {
                viewModel.onAction(ActionParametres.ChangerTaillePoliceEditeur(tailleDeLId(id)))
            }
        }
        binding.interrupteurLigatures.setOnCheckedChangeListener { _, activees ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerLigatures(activees))
        }
        binding.interrupteurRetourLigne.setOnCheckedChangeListener { _, actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerRetourLigne(actif))
        }
        binding.interrupteurMinimap.setOnCheckedChangeListener { _, activee ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerMinimap(activee))
        }
        binding.interrupteurCaracteresNonImprimables.setOnCheckedChangeListener { _, affiches ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerCaracteresNonImprimables(affiches))
        }
        binding.interrupteurChipsDiagnostics.setOnCheckedChangeListener { _, activees ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerChipsDiagnostics(activees))
        }
        binding.interrupteurSauvegardeAuto.setOnCheckedChangeListener { _, activee ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSauvegardeAuto(activee))
        }
    }

    /** Rendu de l'état dans les contrôles. */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            binding.valeurThemeEditeur.setText(libelleTheme(reglages.editorThemeEditeur))
            when (reglages.editorTaillePolice) {
                TaillePoliceEditeur.PETITE -> binding.groupePoliceEditeur.check(R.id.bouton_police_editeur_1)
                TaillePoliceEditeur.MOYENNE -> binding.groupePoliceEditeur.check(R.id.bouton_police_editeur_2)
                TaillePoliceEditeur.GRANDE -> binding.groupePoliceEditeur.check(R.id.bouton_police_editeur_3)
            }
            binding.interrupteurLigatures.isChecked = reglages.editorLigatures
            binding.interrupteurRetourLigne.isChecked = reglages.editorRetourLigne
            binding.interrupteurMinimap.isChecked = reglages.editorMinimap
            binding.interrupteurCaracteresNonImprimables.isChecked = reglages.editorCaracteresNonImprimables
            binding.interrupteurChipsDiagnostics.isChecked = reglages.editorChipsDiagnostics
            binding.interrupteurSauvegardeAuto.isChecked = reglages.editorSauvegardeAuto
        } finally {
            renduEnCours = false
        }
    }

    /**
     * Sélecteur de thème (liste à choix unique) — le même patron que la
     * licence par défaut : la valeur courante pré-cochée, action
     * immédiate puis fermeture.
     */
    private fun ouvrirChoixTheme() {
        val themes = ThemeEditeur.entries.toTypedArray()
        val courant = themes.indexOfFirst { it == viewModel.etat.value.reglage.editorThemeEditeur }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme_editeur_dialogue)
            .setSingleChoiceItems(
                themes.map { getString(libelleTheme(it)) }.toTypedArray(),
                courant,
            ) { dialogue, lequel ->
                viewModel.onAction(ActionParametres.ChangerThemeEditeur(themes[lequel]))
                dialogue.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/** Taille de police éditeur du bouton coché (MOYENNE par défaut). */
private fun tailleDeLId(id: Int): TaillePoliceEditeur =
    when (id) {
        R.id.bouton_police_editeur_1 -> TaillePoliceEditeur.PETITE
        R.id.bouton_police_editeur_3 -> TaillePoliceEditeur.GRANDE
        else -> TaillePoliceEditeur.MOYENNE
    }

/** Ressource du libellé localisé d'un thème de l'éditeur. */
@Suppress("CyclomaticComplexMethod") // Une branche par thème embarqué de cel-ui — projection plate.
private fun libelleTheme(theme: ThemeEditeur): Int =
    when (theme) {
        ThemeEditeur.AUTO -> R.string.settings_theme_auto
        ThemeEditeur.VSCODE_SOMBRE -> R.string.settings_theme_vscode_sombre
        ThemeEditeur.VSCODE_CLAIR -> R.string.settings_theme_vscode_clair
        ThemeEditeur.DRACULA -> R.string.settings_theme_dracula
        ThemeEditeur.ONE_DARK -> R.string.settings_theme_one_dark
        ThemeEditeur.MONOKAI -> R.string.settings_theme_monokai
        ThemeEditeur.SOLARIZED_SOMBRE -> R.string.settings_theme_solarized_sombre
        ThemeEditeur.GITHUB_CLAIR -> R.string.settings_theme_github_clair
        ThemeEditeur.GITHUB_SOMBRE -> R.string.settings_theme_github_sombre
        ThemeEditeur.NORD -> R.string.settings_theme_nord
    }
