package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import jo.codeide.core.model.License
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.PageProfilBinding

/**
 * Page 4 — Profil (étape 5) : nom d'auteur optionnel (pré-remplit
 * README et licences du futur wizard) et licence par défaut.
 *
 * Les frappes ne déclenchent **aucune écriture disque** : l'état vit
 * dans le ViewModel et le `SavedStateHandle` ; la persistance a lieu à
 * la fin de l'assistant (« Terminer »).
 */
class ProfilPage : BaseFragment<PageProfilBinding>() {
    private val viewModel: OnboardingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageProfilBinding = PageProfilBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.champNom.doOnTextChanged { texte, _, _, _ ->
            if (!renduEnCours) viewModel.onAction(ActionOnboarding.SaisirNomAuteur(texte?.toString().orEmpty()))
        }

        val libelles = licences().map { getString(it.second) }.toTypedArray()
        binding.menuLicence.setSimpleItems(libelles)
        binding.menuLicence.setOnItemClickListener { _, _, position, _ ->
            if (!renduEnCours) viewModel.onAction(ActionOnboarding.ChangerLicence(licences()[position].first))
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu des valeurs courantes sans réémettre d'actions. */
    private fun rendre(etat: EtatOnboarding) {
        renduEnCours = true
        try {
            if (binding.champNom.text?.toString() != etat.nomAuteur) {
                binding.champNom.setText(etat.nomAuteur)
            }
            binding.menuLicence.setText(libelleDe(etat.licenceDefaut), false)
        } finally {
            renduEnCours = false
        }
    }

    /** Libellé localisé d'une licence. */
    private fun libelleDe(licence: License): String = getString(libelleLicence(licence))

    /** Licences proposées, dans l'ordre du wizard (étape 9). */
    private fun licences(): List<Pair<License, Int>> =
        listOf(
            License.NONE to R.string.licence_none,
            License.MIT to R.string.licence_mit,
            License.APACHE_2_0 to R.string.licence_apache,
            License.GPL_3_0 to R.string.licence_gpl,
            License.BSD_3_CLAUSE to R.string.licence_bsd,
        )

    private fun libelleLicence(licence: License): Int =
        licences().firstOrNull { it.first == licence }?.second ?: R.string.licence_mit
}
