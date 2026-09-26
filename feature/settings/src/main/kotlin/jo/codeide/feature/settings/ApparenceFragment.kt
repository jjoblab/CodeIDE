package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.PaletteCouleur
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsApparenceBinding
import jo.codeide.feature.settings.databinding.LignePaletteOptionBinding
import javax.inject.Inject
import jo.codeide.core.ui.R as RUi

/**
 * Section Apparence (ADR 0059 ; palette : ADR 0060) : mode de thème,
 * couleurs dynamiques, **sélecteur de palette statique** (huit schémas
 * Material 3 complets jour/nuit) et aperçu de palette — bande de
 * pastilles primaire / secondaire / tertiaire réactive à l'état du
 * switch (atténuée quand les couleurs dynamiques sont coupées), façon
 * sélecteur de style Android.
 */
@AndroidEntryPoint
class ApparenceFragment : BaseFragment<FragmentSettingsApparenceBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    /** Boutons radio des palettes, indexés par palette. */
    private val radiosPalettes = mutableMapOf<PaletteCouleur, LignePaletteOptionBinding>()

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

        construireSelecteurPalette()
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

    /**
     * Gonfle une rangée par palette (ADR 0060) : pastilles fixes de la
     * palette, libellé, radio — le clic porte la rangée entière.
     */
    private fun construireSelecteurPalette() {
        PaletteCouleur.entries.forEach { palette ->
            val liaison = LignePaletteOptionBinding.inflate(layoutInflater, binding.rangeesPalette, false)
            liaison.libelleLignePalette.setText(libellePalette(palette))
            liaison.pastillePrimairePalette.backgroundTintList = pastille(idPastille(palette))
            liaison.pastilleConteneurPalette.backgroundTintList = pastille(idConteneur(palette))
            liaison.pastilleTertiairePalette.backgroundTintList = pastille(idTertiaire(palette))
            liaison.racineLignePalette.setOnClickListener {
                viewModel.onAction(ActionParametres.ChangerPalette(palette))
            }
            binding.rangeesPalette.addView(liaison.root)
            radiosPalettes[palette] = liaison
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
            // Sélecteur : la palette n'a d'effet que sans les couleurs
            // dynamiques — les rangées restent lisibles mais désactivées.
            binding.textePaletteDynamique.isVisible = reglages.useDynamicColor
            radiosPalettes.forEach { (palette, liaison) ->
                liaison.radioLignePalette.isChecked = palette == reglages.paletteCouleur
                liaison.racineLignePalette.isEnabled = !reglages.useDynamicColor
                liaison.racineLignePalette.alpha = if (reglages.useDynamicColor) ALPHA_BIENTOT else 1f
            }
        } finally {
            renduEnCours = false
        }
    }

    /** Pastille [android.content.res.ColorStateList] d'une couleur de palette. */
    private fun pastille(idCouleur: Int): android.content.res.ColorStateList =
        android.content.res.ColorStateList
            .valueOf(ContextCompat.getColor(requireContext(), idCouleur))
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

/** Opacité des rangées de palette quand les couleurs dynamiques sont actives. */
private const val ALPHA_BIENTOT = 0.55f

/** Libellé localisé d'une palette. */
private fun libellePalette(palette: PaletteCouleur): Int =
    when (palette) {
        PaletteCouleur.INDIGO -> R.string.settings_palette_indigo
        PaletteCouleur.BLEU -> R.string.settings_palette_bleu
        PaletteCouleur.TURQUOISE -> R.string.settings_palette_turquoise
        PaletteCouleur.VERT -> R.string.settings_palette_vert
        PaletteCouleur.AMBRE -> R.string.settings_palette_ambre
        PaletteCouleur.ROUGE -> R.string.settings_palette_rouge
        PaletteCouleur.VIOLET -> R.string.settings_palette_violet
        PaletteCouleur.ROSE -> R.string.settings_palette_rose
    }

/** Pastille primaire de chaque palette (déclinaison claire, ADR 0060). */
private fun idPastille(palette: PaletteCouleur): Int =
    when (palette) {
        PaletteCouleur.INDIGO -> RUi.color.palette_indigo_pastille
        PaletteCouleur.BLEU -> RUi.color.palette_bleu_pastille
        PaletteCouleur.TURQUOISE -> RUi.color.palette_turquoise_pastille
        PaletteCouleur.VERT -> RUi.color.palette_vert_pastille
        PaletteCouleur.AMBRE -> RUi.color.palette_ambre_pastille
        PaletteCouleur.ROUGE -> RUi.color.palette_rouge_pastille
        PaletteCouleur.VIOLET -> RUi.color.palette_violet_pastille
        PaletteCouleur.ROSE -> RUi.color.palette_rose_pastille
    }

/** Conteneur primaire de chaque palette (déclinaison claire). */
private fun idConteneur(palette: PaletteCouleur): Int =
    when (palette) {
        PaletteCouleur.INDIGO -> RUi.color.codeide_primary_container
        PaletteCouleur.BLEU -> RUi.color.palette_bleu_primaryContainer
        PaletteCouleur.TURQUOISE -> RUi.color.palette_turquoise_primaryContainer
        PaletteCouleur.VERT -> RUi.color.palette_vert_primaryContainer
        PaletteCouleur.AMBRE -> RUi.color.palette_ambre_primaryContainer
        PaletteCouleur.ROUGE -> RUi.color.palette_rouge_primaryContainer
        PaletteCouleur.VIOLET -> RUi.color.palette_violet_primaryContainer
        PaletteCouleur.ROSE -> RUi.color.palette_rose_primaryContainer
    }

/** Tertiaire de chaque palette (déclinaison claire). */
private fun idTertiaire(palette: PaletteCouleur): Int =
    when (palette) {
        PaletteCouleur.INDIGO -> RUi.color.codeide_tertiary
        PaletteCouleur.BLEU -> RUi.color.palette_bleu_tertiary
        PaletteCouleur.TURQUOISE -> RUi.color.palette_turquoise_tertiary
        PaletteCouleur.VERT -> RUi.color.palette_vert_tertiary
        PaletteCouleur.AMBRE -> RUi.color.palette_ambre_tertiary
        PaletteCouleur.ROUGE -> RUi.color.palette_rouge_tertiary
        PaletteCouleur.VIOLET -> RUi.color.palette_violet_tertiary
        PaletteCouleur.ROSE -> RUi.color.palette_rose_tertiary
    }
