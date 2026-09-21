package jo.codeide.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.feature.home.databinding.FragmentHomeBinding
import javax.inject.Inject

/**
 * Écran d'accueil — destination initiale du graphe de navigation.
 *
 * Étape 1 : écran provisoire qui prouve la chaîne complète (convention
 * feature, ViewBinding via [BaseFragment], injection Hilt et navigation
 * découplée). La liste réelle des projets arrive à l'étape 7 (section 11
 * du prompt maître) : tri, recherche, états vide/chargement/erreur et
 * actions par projet.
 */
@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

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
    }
}
