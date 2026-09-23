package jo.codeide.feature.install

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.install.databinding.FragmentInstallBinding

/**
 * Écran d'installation du bootstrap natif (étape T3, prompt Terminal-1
 * sections 3.4 et 6).
 *
 * **Écran de progression partagé** : l'état vit dans le singleton du
 * domaine ([jo.codeide.core.domain.BootstrapInstaller]) — ouvrir cet
 * écran depuis l'onboarding ou le bandeau de l'accueil pendant une
 * installation déjà lancée y affiche la même progression, la refermer
 * ne l'interrompt pas.
 *
 * Le retour système referme l'écran sans jamais interrompre une
 * installation en cours : l'annulation est un choix explicite.
 *
 * Correctif v0.25.0 (rapport 30e81ee0) : sans [AndroidEntryPoint], la
 * factory par défaut ne connaît pas le constructeur `@Inject` de
 * [InstallViewModel] — `NoSuchMethodException` à l'ouverture de l'écran
 * sur appareil. L'annotation installe la factory Hilt (ViewModel
 * scopé au fragment).
 */
@AndroidEntryPoint
class InstallFragment : BaseFragment<FragmentInstallBinding>() {
    private val viewModel: InstallViewModel by viewModels()

    private companion object {
        /** Passage d'une fraction 0..1 vers l'échelle entière de la barre. */
        private const val ECHELLE_POURCENT = 100
    }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentInstallBinding = FragmentInstallBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.boutonInstaller.setOnClickListener { viewModel.onAction(ActionInstallation.Installer) }
        binding.boutonAnnuler.setOnClickListener { viewModel.onAction(ActionInstallation.Annuler) }
        binding.boutonFermer.setOnClickListener { findNavController().popBackStack() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu complet de l'état : une phase visible à la fois. */
    private fun rendre(etat: EtatInstallation) {
        binding.invite.isVisible = etat.phase == PhaseInstallation.INVITE || etat.phase == PhaseInstallation.ANNULEE
        binding.progression.isVisible = etat.phase == PhaseInstallation.PROGRESSION
        binding.resultat.isVisible = etat.phase == PhaseInstallation.TERMINEE
        binding.echec.isVisible = etat.phase == PhaseInstallation.ECHEC

        when (etat.phase) {
            PhaseInstallation.INVITE, PhaseInstallation.ANNULEE -> {
                binding.boutonInstaller.isVisible = true
                binding.boutonInstaller.setText(
                    if (etat.phase == PhaseInstallation.ANNULEE) {
                        R.string.installation_reessayer
                    } else {
                        R.string.installation_installer
                    },
                )
                binding.texteAnnulee.isVisible = etat.phase == PhaseInstallation.ANNULEE
            }

            PhaseInstallation.PROGRESSION -> {
                binding.boutonInstaller.isVisible = false
                binding.texteAnnulee.isVisible = false
                binding.boutonAnnuler.isVisible = true
                binding.texteEtape.setText(libelleEtape(etat.libelleEtape))
                val progression = etat.progressionTelechargement
                if (progression != null) {
                    binding.barreProgression.isIndeterminate = false
                    binding.barreProgression.progress = (progression * ECHELLE_POURCENT).toInt()
                } else {
                    binding.barreProgression.isIndeterminate = true
                }
            }

            PhaseInstallation.TERMINEE -> {
                binding.boutonAnnuler.isVisible = false
                rendreOutils(etat.outils)
            }

            PhaseInstallation.ECHEC -> {
                binding.boutonAnnuler.isVisible = false
                binding.texteErreur.setText(messageErreur(etat.erreur))
                binding.boutonInstaller.isVisible = true
                binding.boutonInstaller.setText(R.string.installation_reessayer)
            }
        }
    }

    /** Résumé des outils installés (paquet par paquet). */
    private fun rendreOutils(outils: List<jo.codeide.core.model.OutilResume>) {
        if (outils.isEmpty()) {
            binding.texteOutils.setText(R.string.installation_outils_aucun)
            return
        }
        binding.texteOutils.text =
            outils.joinToString(separator = "\n") { outil ->
                val suffixe =
                    getString(
                        if (outil.installe) {
                            R.string.installation_outil_installe
                        } else {
                            R.string.installation_outil_absent
                        },
                    )
                "${outil.paquet} — $suffixe"
            }
    }

    /** Libellé localisé de l'étape en cours. */
    private fun libelleEtape(etape: EtapeInstallation?): Int =
        when (etape) {
            null, EtapeInstallation.VerificationEspaceDisque -> R.string.installation_etape_verification
            is EtapeInstallation.Telechargement -> R.string.installation_etape_telechargement
            is EtapeInstallation.Extraction -> R.string.installation_etape_extraction
            EtapeInstallation.LiensSymboliques -> R.string.installation_etape_liens
            EtapeInstallation.BasculeVersPrefixe -> R.string.installation_etape_bascule
            EtapeInstallation.SecondStage -> R.string.installation_etape_second_stage
            EtapeInstallation.ConfigurationApt -> R.string.installation_etape_sources
            EtapeInstallation.MiseAJourApt -> R.string.installation_etape_apt
            is EtapeInstallation.InstallationPaquets -> R.string.installation_etape_paquets
        }

    /** Message actionnable d'une erreur typée (les détails partent au journal). */
    private fun messageErreur(erreur: AppError?): Int =
        when ((erreur as? AppError.Bootstrap)?.reason) {
            AppError.BootstrapReason.ReseauIndisponible -> {
                R.string.installation_erreur_reseau
            }

            AppError.BootstrapReason.EspaceDisqueInsuffisant -> {
                R.string.installation_erreur_espace
            }

            AppError.BootstrapReason.ArchiveCorrompue, AppError.BootstrapReason.EmpreinteInvalide -> {
                R.string.installation_erreur_archive
            }

            AppError.BootstrapReason.PermissionRefusee -> {
                R.string.installation_erreur_permission
            }

            AppError.BootstrapReason.EchecSecondStage, AppError.BootstrapReason.EchecApt -> {
                R.string.installation_erreur_paquets
            }

            AppError.BootstrapReason.AssetAbsent -> {
                R.string.installation_erreur_asset
            }

            AppError.BootstrapReason.ArchitectureNonSupportee -> {
                R.string.installation_erreur_architecture
            }

            else -> {
                R.string.installation_erreur_inconnue
            }
        }
}
