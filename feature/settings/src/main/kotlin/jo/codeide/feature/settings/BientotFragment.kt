package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.SectionParametres
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.feature.settings.databinding.FragmentSettingsBientotBinding
import javax.inject.Inject

/**
 * Écran générique « bientôt disponible » (ADR 0059) : icône + titre +
 * une phrase — **aucune logique métier** pour les sections pas encore
 * développées (IA, Outils de développement, Sécurité et confidentialité).
 *
 * La section vient en argument de navigation (nom de
 * [SectionParametres]) : ajouter une future section « bientôt » = une
 * entrée d'enum et une destination, ce fragment ne bouge pas.
 */
@AndroidEntryPoint
class BientotFragment : BaseFragment<FragmentSettingsBientotBinding>() {
    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsBientotBinding = FragmentSettingsBientotBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }

        when (section()) {
            SectionParametres.IA -> {
                binding.iconeBientot.setImageResource(jo.codeide.core.ui.R.drawable.ic_smart_toy)
                binding.titreBientot.setText(R.string.settings_bientot_titre_ia)
                binding.texteBientot.setText(R.string.settings_bientot_texte_ia)
            }

            SectionParametres.OUTILS -> {
                binding.iconeBientot.setImageResource(jo.codeide.core.ui.R.drawable.ic_outils)
                binding.titreBientot.setText(R.string.settings_bientot_titre_outils)
                binding.texteBientot.setText(R.string.settings_bientot_texte_outils)
            }

            SectionParametres.SECURITE -> {
                binding.iconeBientot.setImageResource(jo.codeide.core.ui.R.drawable.ic_bouclier)
                binding.titreBientot.setText(R.string.settings_bientot_titre_securite)
                binding.texteBientot.setText(R.string.settings_bientot_texte_securite)
            }

            // Repli défensif : toute autre section n'a rien à faire ici.
            else -> {
                binding.iconeBientot.setImageResource(jo.codeide.core.ui.R.drawable.ic_info)
                binding.titreBientot.setText(R.string.settings_bientot_titre_outils)
                binding.texteBientot.setText(R.string.settings_bientot_texte_ia)
            }
        }
    }

    /** Section portée par l'argument de navigation (repli : IA). */
    private fun section(): SectionParametres =
        arguments?.getString(CLE_SECTION)?.let(SectionParametres::valueOf) ?: SectionParametres.IA

    private companion object {
        /** Clé de l'argument de navigation — même nom que dans le graphe. */
        const val CLE_SECTION = "section"
    }
}
