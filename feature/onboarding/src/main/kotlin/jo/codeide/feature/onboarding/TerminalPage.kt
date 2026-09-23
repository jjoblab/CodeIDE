package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.PageTerminalBinding

/**
 * Page Terminal de l'assistant (étape T3, prompt Terminal-1, section 6).
 *
 * Explication courte de ce qu'apporte le terminal intégré (JDK, Gradle
 * quand il sera publié, SDK Android, shell complet) et de son poids ;
 * deux actions : **« Installer maintenant »** — ouvre l'écran de
 * progression **partagé** (la poursuite du parcours reste possible, la
 * fermeture de l'écran n'interrompt rien) — et **« Plus tard »**, jamais
 * bloquant (même traitement que le dossier de travail : bandeau
 * d'invitation à l'accueil).
 *
 * Au retour de l'écran d'installation, la présence des outils est
 * revérifiée : la page affiche alors « déjà installé ».
 */
class TerminalPage : BaseFragment<PageTerminalBinding>() {
    private val viewModel: OnboardingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageTerminalBinding = PageTerminalBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.boutonInstaller.setOnClickListener {
            viewModel.onAction(ActionOnboarding.InstallerTerminal)
        }
        binding.boutonPasser.setOnClickListener {
            viewModel.onAction(ActionOnboarding.PasserTerminal)
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.terminalInstalle) }
    }

    override fun onResume() {
        super.onResume()
        // Retour possible depuis l'écran d'installation : revérification
        // (marqueur de fichier pur, réponse immédiate).
        viewModel.onAction(ActionOnboarding.VerifierTerminal)
    }

    /** Rendu : boutons d'installation, ou résumé « déjà installé ». */
    private fun rendre(installe: Boolean) {
        binding.boutonInstaller.isVisible = !installe
        binding.texteDejaInstalle.isVisible = installe
    }
}
