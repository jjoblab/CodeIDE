package jo.codeide.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.home.databinding.FragmentHomeBinding
import javax.inject.Inject

/**
 * Écran d'accueil — destination initiale du graphe de navigation.
 *
 * Étape 5 : bandeau « Configurer le dossier de travail » quand
 * l'assistant s'est terminé sans dossier (étape passable, « Plus tard »)
 * — le bouton rouvre l'assistant sur la page du dossier. La liste réelle
 * des projets arrive à l'étape 7 : tri, recherche, états et actions.
 */
@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: HomeViewModel by viewModels()

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentHomeBinding = FragmentHomeBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe les barres système.
        binding.root.applySystemBarsInsets(top = true, bottom = true)

        binding.buttonSettings.setOnClickListener {
            navigator.openSettings()
        }
        binding.bandeauDossier.boutonConfigurer.setOnClickListener {
            navigator.openOnboarding()
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu de l'état : bandeau du dossier de travail. */
    private fun rendre(etat: EtatAccueil) {
        binding.bandeauDossier.root.isVisible = etat.montrerBandeau
    }
}
