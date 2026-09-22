package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.feature.newproject.databinding.FragmentNewprojectBinding
import javax.inject.Inject

/**
 * Écran du wizard de création de projet — destination placeholder de
 * l'étape 7 : l'accueil y mène par « Nouveau projet », la machine à
 * états complète (modèles, paramètres, fichiers, récapitulatif) arrive
 * à l'étape 10 du plan.
 *
 * Le bouton retour referme l'écran sur l'accueil sans rien créer.
 */
@AndroidEntryPoint
class NewProjectFragment : BaseFragment<FragmentNewprojectBinding>() {
    /** Navigation découplée : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentNewprojectBinding = FragmentNewprojectBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Contenu edge-to-edge : la racine absorbe les barres système.
        binding.root.applySystemBarsInsets(top = true, bottom = true)
        binding.boutonRetour.setOnClickListener { navigator.goBack() }
    }
}
