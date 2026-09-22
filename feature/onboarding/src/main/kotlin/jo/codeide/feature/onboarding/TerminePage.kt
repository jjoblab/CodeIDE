package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.PageTermineBinding

/**
 * Page 5 — Terminé (étape 5) : récapitulatif des choix retenus avant la
 * validation. Le bouton « Terminer » de la barre hôte déclenche
 * `isSetupCompleted = true` puis le retour à l'accueil.
 */
class TerminePage : BaseFragment<PageTermineBinding>() {
    private val viewModel: OnboardingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageTermineBinding = PageTermineBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Récapitulatif : dossier, auteur, licence — valeurs réelles ou replis. */
    private fun rendre(etat: EtatOnboarding) {
        binding.texteDossier.text =
            when (val dossier = etat.dossier) {
                is EtatDossier.Configure -> {
                    getString(R.string.page_termine_dossier, dossier.dossier.displayPath)
                }

                else -> {
                    getString(R.string.page_termine_dossier_absent)
                }
            }
        binding.texteAuteur.text =
            etat.nomAuteur
                .ifBlank { null }
                ?.let { getString(R.string.page_termine_auteur, it) }
                ?: getString(R.string.page_termine_auteur_absent)
        binding.texteLicence.text =
            getString(R.string.page_termine_licence, getString(libelleLicence(etat.licenceDefaut)))
    }

    private fun libelleLicence(licence: jo.codeide.core.model.License): Int =
        when (licence) {
            jo.codeide.core.model.License.NONE -> R.string.licence_none
            jo.codeide.core.model.License.APACHE_2_0 -> R.string.licence_apache
            jo.codeide.core.model.License.GPL_3_0 -> R.string.licence_gpl
            jo.codeide.core.model.License.BSD_3_CLAUSE -> R.string.licence_bsd
            jo.codeide.core.model.License.MIT -> R.string.licence_mit
        }
}
