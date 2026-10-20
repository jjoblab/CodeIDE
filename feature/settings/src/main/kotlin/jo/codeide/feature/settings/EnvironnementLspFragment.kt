package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentLspEnvironnementBinding
import javax.inject.Inject

/**
 * L4 — Environnement d'analyse (lot LSP-3, spec § 3) : LECTURE SEULE —
 * chaque valeur vient de l'hôte ou de l'environnement (versions de
 * l'installateur, android.jar de la chaîne d'outils), AUCUNE saisie de
 * chemin (règle 11), « Copier le chemin » n'existe pas (voir L5). JDK
 * absent → VueEtat(erreur) + action unique « Réparer » vers l'écran
 * Environnement des Paramètres.
 */
@AndroidEntryPoint
class EnvironnementLspFragment : BaseFragment<FragmentLspEnvironnementBinding>() {
    private val viewModel: LangagesViewModel by activityViewModels()

    /** Navigation découplée : réparation vers la section Environnement. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLspEnvironnementBinding = FragmentLspEnvironnementBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        binding.vueEtatJdk.setAction(getString(R.string.lsp_reparer)) {
            navigator.openReglageEnvironnement()
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu OBSERVÉ : valeurs lues, échec JDK porté par l'état. */
    private fun rendre(etat: EtatEcransLsp) {
        val modele = etat.environnement ?: return

        binding.rangeeJdk.sousTitre =
            modele.versionJdk?.let { version ->
                getString(R.string.lsp_jdk_fourni) + SEPARATEUR + version
            } ?: getString(R.string.lsp_jdk_introuvable_titre)
        binding.rangeeJdk.setEnabled(modele.versionJdk != null)

        binding.rangeePlateforme.sousTitre =
            modele.plateforme?.let { resoudreTexteLsp(it) }
                ?: getString(R.string.lsp_plateforme_absente)

        binding.rangeeModeJava.sousTitre =
            getString(
                if (modele.modeInstantane) R.string.lsp_mode_instantane else R.string.lsp_mode_par_module,
            )

        binding.vueEtatJdk.isVisible = modele.jdkAbsent
    }

    private companion object {
        const val SEPARATEUR = " · "
    }
}
