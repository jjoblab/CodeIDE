package jo.codeide.feature.ai.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.ai.IaTiroirFragment
import jo.codeide.feature.ai.R
import jo.codeide.feature.ai.databinding.FragmentIaAgentBinding
import kotlinx.coroutines.launch

/**
 * Écran Agent (IA-7b, spec E2) : objectif, bandeau limites, étapes,
 * carte d'approbation EXÉCUTION, carte des changements proposés et
 * bandeau « Annuler cette exécution ». AUCUNE logique ici : tout vit
 * dans [ControleurAgentIa] via [AgentViewModel] — la vue rend l'état.
 */
@AndroidEntryPoint
public class AgentFragment : Fragment() {
    private val modele: AgentViewModel by viewModels()
    private var liaison: FragmentIaAgentBinding? = null
    private var synchronisationObjectif = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentIaAgentBinding.inflate(inflater, container, false)
        return liaison!!.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val liaison = liaison ?: return
        val etapes = AdapterEtapesAgent()
        liaison.listeEtapes.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeEtapes.adapter = etapes

        liaison.champObjectif.setOnFocusChangeListener { _, focus ->
            if (!focus) {
                modele.definirObjectif(
                    liaison.champObjectif.text
                        ?.toString()
                        .orEmpty(),
                )
            }
        }
        liaison.boutonLancer.setOnClickListener { modele.lancer() }
        liaison.boutonArreter.setOnClickListener { modele.arreter() }
        liaison.boutonContinuer.setOnClickListener { modele.continuer() }
        liaison.boutonRefuser.setOnClickListener {
            modele.etat.value.demande
                ?.let { modele.refuser(it.id) }
        }
        liaison.boutonAutoriser.setOnClickListener {
            modele.etat.value.demande
                ?.let { modele.approuver(it.id) }
        }
        liaison.boutonExaminer.setOnClickListener {
            modele.ouvrirRevue { prete ->
                if (prete) {
                    (parentFragment as? IaTiroirFragment)?.ouvrirRevueChangements()
                }
            }
        }
        liaison.boutonToutAppliquer.setOnClickListener { modele.appliquerTout() }
        liaison.boutonRejeter.setOnClickListener { modele.rejeterChangements() }
        liaison.boutonAnnulerExecution.setOnClickListener { modele.annulerExecution() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                modele.etat.collect { etat -> rendre(etat, liaison, etapes) }
            }
        }
    }

    override fun onDestroyView() {
        liaison = null
        super.onDestroyView()
    }

    private fun rendre(
        etat: EtatAgentIa,
        liaison: FragmentIaAgentBinding,
        etapes: AdapterEtapesAgent,
    ) {
        if (liaison.champObjectif.text?.toString() != etat.objectif) {
            synchronisationObjectif = true
            liaison.champObjectif.setText(etat.objectif)
            synchronisationObjectif = false
        }
        val enCours = etat.statut == StatutAgentIa.EN_COURS
        liaison.boutonLancer.isVisible = !enCours
        liaison.boutonArreter.isVisible = enCours
        liaison.boutonContinuer.isVisible = etat.statut == StatutAgentIa.INTERROMPU
        liaison.texteLimites.text =
            getString(R.string.agent_limites, etat.etapes.size, etat.etapesMax, etat.modele)

        etapes.submitList(etat.etapes)

        val demande = etat.demande
        liaison.carteApprobation.isVisible = demande != null
        if (demande != null) {
            liaison.texteApprobationOutil.text = demande.outil
            liaison.texteApprobationRaison.text = demande.raison
        }

        val revue = etat.revue
        val changementsVisibles = revue != null && !revue.vide && etat.execution == null
        liaison.carteChangements.isVisible = changementsVisibles
        if (revue != null && changementsVisibles) {
            liaison.texteChangements.text =
                getString(
                    R.string.agent_changements_resumes,
                    revue.fichiers.size,
                    revue.conflits.size,
                )
            liaison.boutonToutAppliquer.isEnabled = revue.applicables.isNotEmpty()
        }

        val execution = etat.execution
        liaison.bandeauAnnulation.isVisible = execution != null
        liaison.texteMessage.isVisible = etat.message != null
        liaison.texteMessage.text = etat.message.orEmpty()
        liaison.indicateur.isVisible = enCours || etat.enApplication
    }
}
