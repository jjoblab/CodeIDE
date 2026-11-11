package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import jo.codeide.core.model.MessageRegle
import jo.codeide.core.model.TemplateParameterEvaluation
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.EtapeParametresBinding
import jo.codeide.feature.newproject.databinding.VueMessageRegleBinding

/**
 * Fragment **générique** d'une étape de modèle (ADR 0155 §5) : il rend
 * l'étape courante du parcours — paramètres de modèle via [RenduParametres],
 * messages des règles de projet (ADR 0153 §5) au bon endroit, et rangée de
 * puces récapitulatives en direct. Toute nouvelle étape déclarée par un
 * manifeste s'affiche ici sans aucune modification de code (une seule
 * fabrique dans le registre de l'hôte).
 *
 * L'identifiant d'étape arrive en arguments (traçabilité de l'instance) ;
 * le rendu lit toujours **l'étape courante** de l'état partagé — l'hôte ne
 * remplace le fragment que lorsque l'étape change.
 */
class EtapeParametresFragment : EtapeFragment<EtapeParametresBinding>() {
    private var rendu: RenduParametres? = null

    /** Identifiant d'étape passé en arguments (journalisation, garde hôte). */
    val idEtape: String?
        get() = arguments?.getString(CLE_ETAPE)

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): EtapeParametresBinding = EtapeParametresBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rendu =
            RenduParametres(
                binding.conteneurParametres,
                object : RenduParametres.Ecouteur {
                    override fun saisirTexte(parametreId: String, valeur: String) {
                        wizard.action(ActionWizard.SaisirTexte(parametreId, valeur))
                    }

                    override fun choisirValeur(parametreId: String, valeur: String) {
                        wizard.action(ActionWizard.ChoisirValeur(parametreId, valeur))
                    }

                    override fun resynchroniser(parametreId: String) {
                        wizard.action(ActionWizard.Resynchroniser(parametreId))
                    }
                },
            )

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            if (etat.evaluation == null || etat.etapeCourante == null) {
                binding.contenuParametres.isVisible = false
                return@collectWithLifecycle
            }
            binding.contenuParametres.isVisible = true
            rendreMessages(etat.evaluation.messages)
            majPucesRecapitulatives(etat.parametresEtapeCourante())
            rendu?.rendre(etat.parametresEtapeCourante())
        }
    }

    /** Reconstruit les lignes de messages (sévérité portée par la couleur). */
    private fun rendreMessages(messages: List<MessageRegle>) {
        MessagesRegles.rendre(binding.conteneurMessages, messages, layoutInflater)
    }

    /** Reconstruit la rangée de puces récapitulatives (en direct). */
    private fun majPucesRecapitulatives(parametres: List<TemplateParameterEvaluation>) {
        binding.pucesRecapitulatif.removeAllViews()
        parametres.forEach { parametre ->
            when (parametre.type) {
                TemplateParameterType.BOOLEAN -> {
                    // Un interrupteur n'alimente la rangée que s'il est actif.
                    if (parametre.effectiveValue == "true") {
                        ajouterPuce(parametre.label)
                    }
                }

                TemplateParameterType.CHOICE -> {
                    ajouterPuce("${parametre.label} · ${libelleValeur(parametre)}")
                }

                TemplateParameterType.TEXT -> {
                    if (parametre.effectiveValue.isNotBlank()) {
                        ajouterPuce("${parametre.label} · ${parametre.effectiveValue}")
                    }
                }
            }
        }
    }

    /** Libellé résolu d'une valeur de choix (dictionnaire du modèle). */
    private fun libelleValeur(parametre: TemplateParameterEvaluation): String =
        parametre.choix.firstOrNull { it.valeur == parametre.effectiveValue }?.libelle
            ?: parametre.effectiveValue

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

    public companion object {
        /** Clé d'arguments : identifiant de l'étape affichée. */
        public const val CLE_ETAPE: String = "wizard.etape.parametres"

        /** Nouvelle instance pour une étape de modèle déclarée. */
        public fun nouvelleInstance(idEtape: String): EtapeParametresFragment = EtapeParametresFragment().apply {
            arguments = Bundle(1).apply { putString(CLE_ETAPE, idEtape) }
        }
    }
}
