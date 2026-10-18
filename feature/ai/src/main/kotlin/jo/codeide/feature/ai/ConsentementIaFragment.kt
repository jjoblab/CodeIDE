package jo.codeide.feature.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.feature.ai.databinding.FragmentConsentementIaBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Consentement IA (mission IA, lot IA-2, spec E5) : « ce qui est envoyé /
 * jamais envoyé », boutons Activer / Pas maintenant. Aucune case « ne
 * plus afficher » : la révocation vit dans les Réglages (interrupteur
 * maître). Activer est le SEUL chemin d'activation de l'assistant.
 */
@AndroidEntryPoint
public class ConsentementIaFragment : BaseFragment<FragmentConsentementIaBinding>() {
    /** Navigation découplée (retour). */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: ConsentementIaViewModel by viewModels()

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentConsentementIaBinding = FragmentConsentementIaBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.toolbarConsentementIa.setNavigationOnClickListener { navigator.goBack() }
        binding.boutonActiver.setOnClickListener { viewModel.onAction(ActionConsentementIa.Accepter) }
        binding.boutonPasMaintenant.setOnClickListener { viewModel.onAction(ActionConsentementIa.Refuser) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effets.collect { effet ->
                    when (effet) {
                        EffetConsentementIa.Fermer -> navigator.goBack()
                    }
                }
            }
        }
    }
}
