package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.License
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsBinding
import javax.inject.Inject

/**
 * Écran Paramètres (étape 6) : **écran personnalisé Material 3** (pas de
 * `PreferenceFragmentCompat`), piloté par un ViewModel et DataStore.
 *
 * Sections extensibles : Apparence (thème, couleurs dynamiques), Langue,
 * Projets (dossier de travail, nom d'auteur, licence par défaut), À
 * propos (version, build, licences), Avancé (réinitialiser avec
 * confirmation, relancer l'assistant — l'entrée Diagnostic arrive à
 * l'étape 12).
 *
 * Le fragment ne fait que **rendre l'état** et **émettre des actions**
 * (section 5.3) : chaque réglage se persiste à l'instant, l'effet
 * immédiat vient de la recréation d'écran par `MainActivity`.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) :
 * TooManyFunctions — l'écran rend cinq sections (apparence, langue,
 * projets, à propos, avancé) ; chaque section a ses branchements et
 * son rendu. L'éclater en fragments n'apporterait que du pontage.
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {
    private val viewModel: SettingsViewModel by viewModels()

    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Sélecteur SAF du dossier de travail (section 5.6). */
    private val selecteurDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                viewModel.onAction(ActionParametres.DossierChoisi(uri.toString()))
            }
        }

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

        // Contenu edge-to-edge : la racine absorbe barres système et clavier
        // — la toolbar passe sous la barre d'état, les derniers réglages
        // restent au-dessus de la barre de navigation et du clavier.
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)

        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        brancherApparence()
        brancherLangue()
        brancherProjets()
        brancherAvance()

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet -> appliquer(effet) }
    }

    // ------------------------------------------------------------------
    // Branchement des contrôles → actions (une seule fois)
    // ------------------------------------------------------------------

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    private fun brancherApparence() {
        binding.groupeTheme.setOnCheckedChangeListener { _, id ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerTheme(modeDuTheme(id)))
        }
        binding.interrupteurDynamique.setOnCheckedChangeListener { _, active ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerCouleursDynamiques(active))
        }
    }

    private fun brancherLangue() {
        binding.groupeLangue.setOnCheckedChangeListener { _, id ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerLangue(langueDeLId(id)))
        }
    }

    private fun brancherProjets() {
        binding.boutonChangerDossier.setOnClickListener {
            viewModel.onAction(ActionParametres.DemanderChangementDossier)
        }
        binding.boutonEffacerDossier.setOnClickListener {
            viewModel.onAction(ActionParametres.EffacerDossier)
        }
        binding.champNomAuteur.setOnFocusChangeListener { _, aLeFocus ->
            if (!aLeFocus && !renduEnCours) {
                val nom =
                    binding.champNomAuteur.text
                        ?.toString()
                        .orEmpty()
                viewModel.onAction(ActionParametres.ValiderNomAuteur(nom))
            }
        }
        binding.valeurLicence.setOnClickListener { ouvrirChoixLicence() }
    }

    private fun brancherAvance() {
        binding.boutonReinitialiser.setOnClickListener { confirmerReinitialisation() }
        binding.boutonRelancerAssistant.setOnClickListener {
            viewModel.onAction(ActionParametres.RelancerAssistant)
        }
        binding.valeurLicences.setOnClickListener { ouvrirLicences() }
    }

    // ------------------------------------------------------------------
    // Rendu de l'état
    // ------------------------------------------------------------------

    /** Rendu complet de l'état : sélections, dossier, retour, à propos. */
    private fun rendre(etat: EtatParametres) {
        renduEnCours = true
        try {
            val reglages = etat.reglage

            binding.radioThemeSysteme.isChecked = reglages.themeMode == ThemeMode.SYSTEM
            binding.radioThemeClair.isChecked = reglages.themeMode == ThemeMode.LIGHT
            binding.radioThemeSombre.isChecked = reglages.themeMode == ThemeMode.DARK
            binding.interrupteurDynamique.isChecked = reglages.useDynamicColor

            binding.radioLangueSysteme.isChecked = reglages.languageTag == ""
            binding.radioLangueFr.isChecked = reglages.languageTag == "fr"
            binding.radioLangueEn.isChecked = reglages.languageTag == "en"

            if (!binding.champNomAuteur.isFocused) {
                binding.champNomAuteur.setText(reglages.authorName)
            }
            binding.valeurLicence.text = libelleLicence(reglages.defaultLicense)

            val dossier = reglages.workspace
            binding.texteDossier.text =
                dossier?.displayPath ?: getString(R.string.settings_dossier_aucun)
            binding.boutonEffacerDossier.isVisible = dossier != null

            binding.progressionDossier.isVisible = etat.verificationDossier
            binding.boutonChangerDossier.isEnabled = !etat.verificationDossier
            binding.boutonEffacerDossier.isEnabled = !etat.verificationDossier
            rendreRetourDossier(etat.retourDossier)

            binding.texteVersion.text =
                getString(
                    R.string.settings_a_propos_version,
                    etat.infosBuild.versionName,
                    etat.infosBuild.versionCode,
                )
            binding.texteBuild.text =
                getString(R.string.settings_a_propos_build, etat.infosBuild.buildType)
        } finally {
            renduEnCours = false
        }
    }

    /** Message transitoire sous la rangée du dossier. */
    private fun rendreRetourDossier(retour: RetourDossier) {
        val message =
            when (retour) {
                RetourDossier.Aucun -> {
                    null
                }

                RetourDossier.Change -> {
                    getString(R.string.settings_dossier_change)
                }

                is RetourDossier.Efface -> {
                    if (retour.permissionGardee) {
                        getString(R.string.settings_dossier_efface_permission_gardee)
                    } else {
                        getString(R.string.settings_dossier_efface)
                    }
                }

                RetourDossier.Refuse -> {
                    getString(R.string.settings_dossier_refuse)
                }

                RetourDossier.Erreur -> {
                    getString(R.string.settings_dossier_erreur)
                }
            }
        binding.texteRetourDossier.text = message
        binding.texteRetourDossier.isVisible = message != null
    }

    /** Thème correspondant au bouton radio coché. */
    private fun modeDuTheme(id: Int): ThemeMode =
        when (id) {
            binding.radioThemeClair.id -> ThemeMode.LIGHT
            binding.radioThemeSombre.id -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

    /** Tag BCP 47 du bouton coché, `""` pour suivre le système. */
    private fun langueDeLId(id: Int): String =
        when (id) {
            binding.radioLangueFr.id -> "fr"
            binding.radioLangueEn.id -> "en"
            else -> ""
        }

    /** Libellé localisé d'une licence. */
    private fun libelleLicence(licence: License): String =
        getString(
            when (licence) {
                License.NONE -> R.string.settings_licence_aucune
                License.MIT -> R.string.settings_licence_mit
                License.APACHE_2_0 -> R.string.settings_licence_apache
                License.GPL_3_0 -> R.string.settings_licence_gpl
                License.BSD_3_CLAUSE -> R.string.settings_licence_bsd
            },
        )

    // ------------------------------------------------------------------
    // Dialogues (choix licence, licences ouvertes, confirmation)
    // ------------------------------------------------------------------

    /** Choix de la licence par défaut (liste à choix unique). */
    private fun ouvrirChoixLicence() {
        val licences = License.entries.toTypedArray()
        val courante = licences.indexOfFirst { it == viewModel.etat.value.reglage.defaultLicense }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_licence_dialogue_titre)
            .setSingleChoiceItems(licences.map { libelleLicence(it) }.toTypedArray(), courante) { dialogue, lequel ->
                viewModel.onAction(ActionParametres.ChangerLicence(licences[lequel]))
                dialogue.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Licences open source des dépendances embarquées (texte embarqué). */
    private fun ouvrirLicences() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_licences_titre)
            .setMessage(R.string.settings_licences_contenu)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Confirmation avant la réinitialisation des préférences. */
    private fun confirmerReinitialisation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reinitialiser_titre)
            .setMessage(R.string.settings_reinitialiser_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_reinitialiser_confirmer) { _, _ ->
                viewModel.onAction(ActionParametres.ReinitialiserPreferences)
            }.show()
    }

    /** Application des effets ponctuels. */
    private fun appliquer(effet: EffetParametres) {
        when (effet) {
            EffetParametres.OuvrirSelecteurDossier -> selecteurDossier.launch(null)
            EffetParametres.OuvrirAssistant -> navigator.openOnboarding()
        }
    }
}
