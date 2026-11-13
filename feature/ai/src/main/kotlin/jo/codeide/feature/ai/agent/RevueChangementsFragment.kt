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
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.ai.IaTiroirFragment
import jo.codeide.feature.ai.R
import jo.codeide.feature.ai.databinding.FragmentRevueChangementsBinding
import kotlinx.coroutines.launch

/**
 * Revue des changements (IA-7b, spec E2 « plein tiroir ») : liste de
 * fichiers (case à cocher, badge Conflit), application par sélection ou
 * en bloc — un conflit n'est JAMAIS cochable. Retour vers l'écran Agent.
 */
@AndroidEntryPoint
public class RevueChangementsFragment : Fragment() {
    private val modele: AgentViewModel by viewModels()
    private var liaison: FragmentRevueChangementsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        liaison = FragmentRevueChangementsBinding.inflate(inflater, container, false)
        return liaison!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liaison = liaison ?: return
        val adapter = AdapterRevueFichiers { _, _ -> majBoutons(liaison) }
        liaison.listeFichiers.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeFichiers.adapter = adapter

        liaison.boutonRetour.setOnClickListener {
            (parentFragment as? IaTiroirFragment)?.fermerRevueChangements()
        }
        liaison.boutonAppliquerSelection.setOnClickListener {
            modele.appliquerSelection(adapter.retenus.toSet())
        }
        liaison.boutonAnnulerExecution.setOnClickListener { modele.annulerExecution() }

        // Prépare (résolution + conflits) à CHAQUE ouverture : l'état du
        // disque peut avoir changé depuis la proposition.
        modele.ouvrirRevue { }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                modele.etat.collect { etat ->
                    val revue = etat.revue
                    liaison.texteVide.isVisible = revue == null || revue.vide
                    if (revue != null && !revue.vide) {
                        adapter.submitList(revue.fichiers)
                        // Tout coché par défaut (hors conflits) — le choix
                        // reste explicite fichier par fichier.
                        if (adapter.retenus.isEmpty()) {
                            adapter.retenus.addAll(revue.applicables.map { it.cheminRelatif })
                        }
                    }
                    liaison.indicateurApplication.isVisible = etat.enApplication
                    val message = etat.message
                    liaison.texteMessage.isVisible = message != null
                    liaison.texteMessage.text =
                        message?.let {
                            it.argument?.let { argument -> getString(it.ressource, argument) }
                                ?: getString(it.ressource)
                        }.orEmpty()
                    liaison.bandeauAnnulation.isVisible = etat.execution != null
                    majBoutons(liaison)
                }
            }
        }
    }

    override fun onDestroyView() {
        liaison = null
        super.onDestroyView()
    }

    private fun majBoutons(liaison: FragmentRevueChangementsBinding) {
        liaison.boutonAppliquerSelection.isEnabled =
            (liaison.listeFichiers.adapter as? AdapterRevueFichiers)?.retenus?.isNotEmpty() == true &&
            !modele.etat.value.enApplication
    }
}
