package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.PageDossierBinding

/**
 * Page 2 — Dossier de travail (étape 5, section 5.6).
 *
 * Sélecteur SAF (`ACTION_OPEN_DOCUMENT_TREE` via effet ponctuel), test
 * d'écriture transparent (création/suppression d'un fichier témoin dans
 * le ViewModel), messages clairs sur les dossiers refusés par Android 11+
 * — jamais un crash. Étape **passable** : « Plus tard » mène au bandeau
 * « Configurer le dossier de travail » de l'accueil.
 */
class DossierPage : BaseFragment<PageDossierBinding>() {
    private val viewModel: OnboardingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageDossierBinding = PageDossierBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.boutonChoisir.setOnClickListener {
            viewModel.onAction(ActionOnboarding.DemanderSelectionDossier)
        }
        binding.boutonPasser.setOnClickListener {
            viewModel.onAction(ActionOnboarding.PasserDossier)
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.dossier) }
    }

    /** Rendu de l'état du dossier : invite, progression, succès ou refus. */
    private fun rendre(dossier: EtatDossier) {
        binding.progressionDossier.isVisible = dossier is EtatDossier.Verification
        binding.boutonChoisir.isEnabled = dossier !is EtatDossier.Verification

        when (dossier) {
            EtatDossier.NonConfigure -> {
                binding.texteEtat.isVisible = false
                binding.texteErreur.isVisible = false
                binding.boutonChoisir.setText(R.string.page_dossier_choisir)
            }

            EtatDossier.Verification -> {
                binding.texteEtat.setText(R.string.page_dossier_verification)
                binding.texteEtat.isVisible = true
                binding.texteErreur.isVisible = false
                binding.boutonChoisir.setText(R.string.page_dossier_choisir)
            }

            is EtatDossier.Refuse -> {
                binding.texteEtat.isVisible = false
                binding.texteErreur.setText(messageRefus(dossier.raison))
                binding.texteErreur.isVisible = true
                binding.boutonChoisir.setText(R.string.page_dossier_choisir)
            }

            is EtatDossier.Erreur -> {
                binding.texteEtat.isVisible = false
                binding.texteErreur.setText(R.string.page_dossier_erreur)
                binding.texteErreur.isVisible = true
                binding.boutonChoisir.setText(R.string.page_dossier_reessayer)
            }

            is EtatDossier.Configure -> {
                binding.texteEtat.text = getString(R.string.page_dossier_configure, dossier.dossier.displayPath)
                binding.texteEtat.isVisible = true
                binding.texteErreur.isVisible = false
                binding.boutonChoisir.setText(R.string.page_dossier_changer)
            }
        }
    }

    /** Message clair et actionnable par raison de refus (Android 11+). */
    private fun messageRefus(raison: ForbiddenFolders.Reason): Int =
        when (raison) {
            ForbiddenFolders.Reason.STORAGE_ROOT -> R.string.page_dossier_refuse_racine
            ForbiddenFolders.Reason.DOWNLOADS -> R.string.page_dossier_refuse_download
            ForbiddenFolders.Reason.ANDROID_DATA -> R.string.page_dossier_refuse_android_data
            ForbiddenFolders.Reason.ANDROID_OBB -> R.string.page_dossier_refuse_android_obb
        }
}
