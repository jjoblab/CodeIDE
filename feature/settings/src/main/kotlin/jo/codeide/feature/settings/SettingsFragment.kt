package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.StyleCurseurTerminal
import jo.codeide.core.model.TaillePoliceTerminal
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.SectionParametres
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsBinding
import jo.codeide.feature.settings.databinding.LigneParametreBinding
import javax.inject.Inject

/**
 * Écran maître des Paramètres (ADR 0059) : sections groupées en cartes M3
 * (Général, Modules, Environnement, Application), une rangée par section —
 * icône, libellé, **sous-titre d'état** et chevron vers le fragment
 * dédié. Les sections pas encore développées (IA, Outils de
 * développement, Sécurité) restent visibles mais atténuées, puce
 * « Bientôt » à la place du chevron, vers l'écran minimal générique.
 *
 * Le patron reste réutilisable : ajouter une section = une entrée dans
 * [ModeleLigne] et une destination dans le graphe — jamais de retouche du
 * maître pour les sections « bientôt » existantes.
 *
 * Le fragment ne fait que **rendre l'état** (section 5.3) : la persistance
 * vit dans le ViewModel partagé (une instance par activité hôte,
 * consommée par toutes les sections).
 */
@AndroidEntryPoint
class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Rangées gonflées du maître, indexées par section. */
    private val rangees = mutableMapOf<SectionParametres, LigneParametreBinding>()

    /** Sous-titres par section — recalculés à chaque émission d'état. */
    private val sousTitres = mutableMapOf<SectionParametres, (EtatParametres) -> String>()

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

        // Contenu edge-to-edge : la racine absorbe barres système et clavier.
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)

        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        construireCartes()

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            sousTitres.forEach { (section, calcul) ->
                rangees[section]?.sousTitreLigneParametre?.text = calcul(etat)
            }
        }
    }

    // ------------------------------------------------------------------
    // Construction des cartes (une seule fois)
    // ------------------------------------------------------------------

    /** Une rangée du maître : présentation + navigation + sous-titre d'état. */
    private data class ModeleLigne(
        val section: SectionParametres,
        val icone: Int,
        val titre: Int,
        val description: Int,
        val bientot: Boolean,
        val sousTitre: (EtatParametres) -> String,
    )

    /**
     * Construit une entrée du maître.
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — les six
     * paramètres sont la description complète d'une rangée (section,
     * icône, libellé, description d'accès, état « bientôt », sous-titre) ;
     * les regrouper en objet nuirait à la lisibilité déclarative de
     * [construireCartes].
     */
    @Suppress("LongParameterList")
    private fun ligne(
        section: SectionParametres,
        icone: Int,
        titre: Int,
        description: Int,
        bientot: Boolean = false,
        sousTitre: (EtatParametres) -> String,
    ): ModeleLigne = ModeleLigne(section, icone, titre, description, bientot, sousTitre)

    /** Gonfle les rangées dans le conteneur d'une carte. */
    private fun ajouterRangees(
        conteneur: LinearLayout,
        modeles: List<ModeleLigne>,
    ) {
        modeles.forEach { modele ->
            val liaison = LigneParametreBinding.inflate(layoutInflater, conteneur, false)
            liaison.iconeLigneParametre.setImageResource(modele.icone)
            liaison.titreLigneParametre.setText(modele.titre)
            liaison.root.contentDescription = getString(modele.description)
            liaison.chevronLigneParametre.isVisible = !modele.bientot
            liaison.puceBientotLigneParametre.isVisible = modele.bientot
            if (modele.bientot) {
                liaison.root.alpha = ALPHA_BIENTOT
            }
            liaison.root.setOnClickListener { navigator.openSettingsSection(modele.section) }
            conteneur.addView(liaison.root)
            rangees[modele.section] = liaison
            sousTitres[modele.section] = modele.sousTitre
        }
    }

    private fun construireCartes() {
        construireCarteGenerale()
        construireCarteModules()
        construireCarteEnvironnement()
        construireCarteApplication()
    }

    /** Carte « Général » : Apparence, Langue, Notifications. */
    private fun construireCarteGenerale() {
        ajouterRangees(
            binding.rangeesGeneral,
            listOf(
                ligne(
                    SectionParametres.APPARENCE,
                    jo.codeide.core.ui.R.drawable.ic_palette,
                    R.string.settings_maitre_apparence,
                    R.string.settings_cd_ligne_apparence,
                ) { etat ->
                    val mode =
                        when (etat.reglage.themeMode) {
                            ThemeMode.SYSTEM -> getString(R.string.settings_theme_systeme)
                            ThemeMode.LIGHT -> getString(R.string.settings_theme_clair)
                            ThemeMode.DARK -> getString(R.string.settings_theme_sombre)
                        }
                    val dynamique =
                        getString(
                            if (etat.reglage.useDynamicColor) {
                                R.string.settings_dyn_activees
                            } else {
                                R.string.settings_dyn_desactivees
                            },
                        )
                    "$mode · $dynamique"
                },
                ligne(
                    SectionParametres.LANGUE,
                    jo.codeide.core.ui.R.drawable.ic_traduire,
                    R.string.settings_maitre_langue,
                    R.string.settings_cd_ligne_langue,
                ) { etat ->
                    when (etat.reglage.languageTag) {
                        "fr" -> getString(R.string.settings_langue_fr)
                        "en" -> getString(R.string.settings_langue_en)
                        else -> getString(R.string.settings_langue_systeme)
                    }
                },
                ligne(
                    SectionParametres.NOTIFICATIONS,
                    jo.codeide.core.ui.R.drawable.ic_notifications,
                    R.string.settings_maitre_notifications,
                    R.string.settings_cd_ligne_notifications,
                ) { etat ->
                    when {
                        etat.reglage.notificationsSync && etat.reglage.notificationsBuild -> {
                            getString(R.string.settings_notif_sync_build_actives)
                        }

                        etat.reglage.notificationsSync || etat.reglage.notificationsBuild -> {
                            getString(R.string.settings_notif_partiel)
                        }

                        else -> {
                            getString(R.string.settings_notif_toutes_desactivees)
                        }
                    }
                },
            ),
        )
    }

    /** Carte « Modules » : Éditeur, Terminal, IA (bientôt). */
    private fun construireCarteModules() {
        ajouterRangees(
            binding.rangeesModules,
            listOf(
                ligne(
                    SectionParametres.EDITEUR,
                    jo.codeide.core.ui.R.drawable.ic_editer,
                    R.string.settings_maitre_editeur,
                    R.string.settings_cd_ligne_editeur,
                ) { getString(R.string.settings_sous_editeur) },
                ligne(
                    SectionParametres.TERMINAL,
                    jo.codeide.core.ui.R.drawable.ic_terminal,
                    R.string.settings_maitre_terminal,
                    R.string.settings_cd_ligne_terminal,
                ) { etat ->
                    val police =
                        when (etat.reglage.taillePoliceTerminal) {
                            TaillePoliceTerminal.PETITE -> getString(R.string.settings_police_petite)
                            TaillePoliceTerminal.MOYENNE -> getString(R.string.settings_police_moyenne)
                            TaillePoliceTerminal.GRANDE -> getString(R.string.settings_police_grande)
                        }
                    val curseur =
                        when (etat.reglage.styleCurseurTerminal) {
                            StyleCurseurTerminal.BLOC -> getString(R.string.settings_curseur_bloc)
                            StyleCurseurTerminal.LIGNE -> getString(R.string.settings_curseur_ligne)
                            StyleCurseurTerminal.BARRE -> getString(R.string.settings_curseur_barre)
                        }
                    "$police · $curseur"
                },
                ligne(
                    SectionParametres.IA,
                    jo.codeide.core.ui.R.drawable.ic_smart_toy,
                    R.string.settings_maitre_ia,
                    R.string.settings_cd_ligne_ia,
                    bientot = true,
                ) { getString(R.string.settings_sous_ia) },
            ),
        )
    }

    /** Carte « Environnement » : Projets, Outils de développement (bientôt). */
    private fun construireCarteEnvironnement() {
        ajouterRangees(
            binding.rangeesEnvironnement,
            listOf(
                ligne(
                    SectionParametres.PROJETS,
                    jo.codeide.core.ui.R.drawable.ic_dossier,
                    R.string.settings_maitre_projets,
                    R.string.settings_cd_ligne_projets,
                ) { getString(R.string.settings_sous_projets) },
                ligne(
                    SectionParametres.OUTILS,
                    jo.codeide.core.ui.R.drawable.ic_outils,
                    R.string.settings_maitre_outils,
                    R.string.settings_cd_ligne_outils,
                    bientot = true,
                ) { getString(R.string.settings_sous_outils) },
            ),
        )
    }

    /** Carte « Application » : Sécurité (bientôt), À propos, Avancé. */
    private fun construireCarteApplication() {
        ajouterRangees(
            binding.rangeesApplication,
            listOf(
                ligne(
                    SectionParametres.SECURITE,
                    jo.codeide.core.ui.R.drawable.ic_bouclier,
                    R.string.settings_maitre_securite,
                    R.string.settings_cd_ligne_securite,
                    bientot = true,
                ) { getString(R.string.settings_sous_securite) },
                ligne(
                    SectionParametres.A_PROPOS,
                    jo.codeide.core.ui.R.drawable.ic_info,
                    R.string.settings_maitre_apropos,
                    R.string.settings_cd_ligne_apropos,
                ) { etat ->
                    getString(
                        R.string.settings_a_propos_version,
                        etat.infosBuild.versionName,
                        etat.infosBuild.versionCode,
                    )
                },
                ligne(
                    SectionParametres.AVANCE,
                    jo.codeide.core.ui.R.drawable.ic_avance,
                    R.string.settings_maitre_avance,
                    R.string.settings_cd_ligne_avance,
                ) { getString(R.string.settings_section_avance) },
            ),
        )
    }

    private companion object {
        /** Atténuation des rangées « bientôt disponible » (opacité réduite). */
        const val ALPHA_BIENTOT = 0.55f
    }
}
