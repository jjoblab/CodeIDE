package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.License
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsProjetsBinding
import javax.inject.Inject

/**
 * Section Projets (ADR 0059) : dossier de travail (sélecteur SAF, règle
 * de l'ancienne permission — étape 6), nom d'auteur et licence par
 * défaut — logique inchangée depuis l'ancien écran unique, seule la
 * présentation déménage dans son propre fragment.
 */
@AndroidEntryPoint
class ProjetsFragment : BaseFragment<FragmentSettingsProjetsBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Sélecteur SAF du dossier de travail (section 5.6). */
    private val selecteurDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                viewModel.onAction(ActionParametres.DossierChoisi(uri.toString()))
            }
        }

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsProjetsBinding = FragmentSettingsProjetsBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        brancher()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet ->
            if (effet == EffetParametres.OuvrirSelecteurDossier) selecteurDossier.launch(null)
        }
    }

    /** Branchements des contrôles → actions (une seule fois). */
    private fun brancher() {
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

    /** Rendu de l'état : dossier, auteur, licence, retour. */
    private fun rendre(etat: EtatParametres) {
        renduEnCours = true
        try {
            val reglages = etat.reglage

            val dossier = reglages.workspace
            binding.texteDossier.text =
                dossier?.displayPath ?: getString(R.string.settings_dossier_aucun)
            binding.boutonEffacerDossier.isVisible = dossier != null

            if (!binding.champNomAuteur.isFocused) {
                binding.champNomAuteur.setText(reglages.authorName)
            }
            binding.valeurLicence.text = libelleLicence(reglages.defaultLicense)

            binding.progressionDossier.isVisible = etat.verificationDossier
            binding.boutonChangerDossier.isEnabled = !etat.verificationDossier
            binding.boutonEffacerDossier.isEnabled = !etat.verificationDossier
            rendreRetourDossier(etat.retourDossier)
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
}
