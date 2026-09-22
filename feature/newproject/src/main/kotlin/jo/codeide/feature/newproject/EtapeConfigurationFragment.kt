package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.model.TemplateSection
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EtapeConfigurationBinding

/**
 * Étape 2 « Configuration » du wizard (section 12.3) : rendu **dynamique**
 * des paramètres de section `CONFIGURATION` ([RenduParametres], issu du
 * moteur), plus une **rangée de puces récapitulatives** (« Application ·
 * Gradle · JDK 21 · JUnit 5 ») qui se met à jour en direct.
 *
 * Les champs qui n'ont pas de sens selon les choix disparaissent avec
 * animation (`visibleWhen` du moteur + `animateLayoutChanges` du
 * conteneur).
 */
class EtapeConfigurationFragment : EtapeFragment<EtapeConfigurationBinding>() {
    private var rendu: RenduParametres? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeConfigurationBinding = EtapeConfigurationBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        rendu =
            RenduParametres(
                binding.conteneurParametres,
                object : RenduParametres.Ecouteur {
                    override fun saisirTexte(
                        parametreId: String,
                        valeur: String,
                    ) {
                        wizard.action(ActionWizard.SaisirTexte(parametreId, valeur))
                    }

                    override fun choisirValeur(
                        parametreId: String,
                        valeur: String,
                    ) {
                        wizard.action(ActionWizard.ChoisirValeur(parametreId, valeur))
                    }

                    override fun resynchroniser(parametreId: String) {
                        wizard.action(ActionWizard.Resynchroniser(parametreId))
                    }
                },
            )

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            if (etat.evaluation == null) {
                binding.contenuConfiguration.isVisible = false
                return@collectWithLifecycle
            }
            binding.contenuConfiguration.isVisible = true
            rendu?.rendre(etat.parametresSection(TemplateSection.CONFIGURATION))
            majPucesRecapitulatives(etat)
        }
    }

    /** Reconstruit la rangée de puces récapitulatives (en direct). */
    private fun majPucesRecapitulatives(etat: EtatWizard) {
        binding.pucesRecapitulatif.removeAllViews()
        etat.parametresSection(TemplateSection.CONFIGURATION).forEach { parametre ->
            when (parametre.type) {
                TemplateParameterType.BOOLEAN -> {
                    // Un interrupteur n'alimente la rangée que s'il est actif.
                    if (parametre.effectiveValue == "true") {
                        ajouterPuce(parametre.label)
                    }
                }

                else -> {
                    ajouterPuce(libelleValeur(parametre.effectiveValue))
                }
            }
        }
    }

    /** Libellé affichable d'une valeur de choix (registre de présentation). */
    private fun libelleValeur(valeur: String): String =
        when (valeur) {
            "application" -> getString(R.string.wizard_choix_application)
            "library" -> getString(R.string.wizard_choix_bibliotheque)
            "gradle-kts" -> getString(R.string.wizard_choix_gradle)
            "maven" -> getString(R.string.wizard_choix_maven)
            "none" -> getString(R.string.wizard_choix_sources)
            "17" -> getString(R.string.wizard_choix_jdk17)
            "21" -> getString(R.string.wizard_choix_jdk21)
            else -> valeur
        }

    /** Ajoute une puce informative (non actionnable). */
    private fun ajouterPuce(texte: String) {
        val puce =
            Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                isCheckable = false
                isClickable = false
                this.text = texte
            }
        binding.pucesRecapitulatif.addView(puce)
    }

    override fun onDestroyView() {
        rendu = null
        super.onDestroyView()
    }
}
