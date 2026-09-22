package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.PageApparenceBinding

/**
 * Page 3 — Apparence (étape 5) : thème, couleurs dynamiques et langue.
 *
 * Chaque choix se **persiste immédiatement** : la collecte des
 * paramètres dans `MainActivity` recrée l'écran avec la nouvelle
 * apparence — c'est l'« aperçu immédiat » exigé par le plan (la langue
 * passe par `AppCompatDelegate.setApplicationLocales`, ADR 0013).
 *
 * Le drapeau [renduEnCours] coupe la boucle « rendu → écouteur → action
 * → rendu » : un `isChecked` posé par le rendu n'est pas une action de
 * l'utilisateur.
 */
class ApparencePage : BaseFragment<PageApparenceBinding>() {
    private val viewModel: OnboardingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageApparenceBinding = PageApparenceBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.groupeTheme.setOnCheckedChangeListener { _, id ->
            if (!renduEnCours) viewModel.onAction(ActionOnboarding.ChangerTheme(modeDuTheme(id)))
        }
        binding.interrupteurDynamique.setOnCheckedChangeListener { _, active ->
            if (!renduEnCours) viewModel.onAction(ActionOnboarding.ChangerCouleursDynamiques(active))
        }
        binding.groupeLangue.setOnCheckedChangeListener { _, id ->
            if (!renduEnCours) viewModel.onAction(ActionOnboarding.ChangerLangue(langueDeLId(id)))
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu des sélections courantes sans réémettre d'actions. */
    private fun rendre(etat: EtatOnboarding) {
        renduEnCours = true
        try {
            binding.radioThemeSysteme.isChecked = etat.modeTheme == ThemeMode.SYSTEM
            binding.radioThemeClair.isChecked = etat.modeTheme == ThemeMode.LIGHT
            binding.radioThemeSombre.isChecked = etat.modeTheme == ThemeMode.DARK
            binding.interrupteurDynamique.isChecked = etat.couleursDynamiques
            binding.radioLangueSysteme.isChecked = etat.langue == ""
            binding.radioLangueFr.isChecked = etat.langue == "fr"
            binding.radioLangueEn.isChecked = etat.langue == "en"
        } finally {
            renduEnCours = false
        }
    }

    private fun modeDuTheme(id: Int): ThemeMode =
        when (id) {
            binding.radioThemeClair.id -> ThemeMode.LIGHT
            binding.radioThemeSombre.id -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

    /** Tag BCP 47 du bouton coché, `""` pour suivre le système. */
    private fun langueDeLId(id: Int): String =
        when (id) {
            binding.radioLangueFr.id -> "fr"
            binding.radioLangueEn.id -> "en"
            else -> ""
        }
}
