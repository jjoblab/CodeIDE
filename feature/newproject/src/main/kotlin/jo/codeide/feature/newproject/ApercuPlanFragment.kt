package jo.codeide.feature.newproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.newproject.databinding.FragmentApercuBinding

/**
 * Aperçu en direct du plan (L5d, ADR 0155 §6) : arborescence prévue,
 * recalculée à chaque changement du formulaire — antirebond 300 ms côté
 * ViewModel, annulable, hors fil principal (le domaine est suspend) —,
 * **différentiel ajouté/retiré** entre deux plans consécutifs, état
 * d'erreur typé avec « Réessayer ».
 *
 * Contenu réutilisable : embarqué comme **panneau latéral** par l'hôte sur
 * tablette (sw600dp, deux colonnes) et comme contenu de la **feuille
 * basse** [ApercuFeuilleFragment] sur téléphone. Lecture seule : le
 * renommage reste l'affaire du récapitulatif (ADR 0077).
 */
class ApercuPlanFragment : EtapeFragment<FragmentApercuBinding>() {
    private var adapteur: ArborescenceAdapter? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentApercuBinding = FragmentApercuBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapteur = ArborescenceAdapter()
        binding.listeApercu.layoutManager = LinearLayoutManager(requireContext())
        binding.listeApercu.adapter = adapteur
        binding.boutonReessayerApercu.setOnClickListener { wizard.action(ActionWizard.ReessayerPlan) }

        wizard.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
    }

    /** Rendu idempotent : différentiel, progression, erreur, arborescence. */
    private fun rendre(etat: EtatWizard) {
        rendreDifferences(etat)

        binding.progressionApercu.isVisible = etat.chargementPlan
        binding.texteErreurApercu.isVisible = etat.erreurPlan
        binding.boutonReessayerApercu.isVisible = etat.erreurPlan

        val arborescence = etat.plan?.let(Arborescence::depuisPlan)
        if (arborescence != null) {
            adapteur?.soumettre(arborescence)
            binding.listeApercu.isVisible = true
        } else {
            binding.listeApercu.isVisible = false
        }
    }

    /** Puces du différentiel : ajoutées puis retirées, jamais de bruit à vide. */
    private fun rendreDifferences(etat: EtatWizard) {
        binding.puceAjoutes.isVisible = etat.fichiersAjoutes.isNotEmpty()
        if (etat.fichiersAjoutes.isNotEmpty()) {
            binding.puceAjoutes.text =
                getString(R.string.wizard_apercu_ajoutes, etat.fichiersAjoutes.size)
        }
        binding.puceRetires.isVisible = etat.fichiersRetires.isNotEmpty()
        if (etat.fichiersRetires.isNotEmpty()) {
            binding.puceRetires.text =
                getString(R.string.wizard_apercu_retires, etat.fichiersRetires.size)
        }
    }

    override fun onDestroyView() {
        adapteur = null
        super.onDestroyView()
    }
}
