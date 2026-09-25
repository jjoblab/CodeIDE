package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import jo.codeide.core.model.LogLevel
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauJournalBinding

/**
 * Onglet Journal applicatif du panneau inférieur (v0.32.4, ADR 0055) :
 * filtres par niveau (chips), fenêtre mémoire compacte avec suivi direct
 * par défilement, état vide après filtres, lien vers l'écran Diagnostic.
 *
 * Le contenu migrent du layout empilé de l'activité (v0.32.3) vers ce
 * fragment — le chrome (en-tête, badge, onglets) reste à l'hôte, qui
 * montre/cache les fragments du conteneur. Le ViewModel est celui de
 * l'activité (`activityViewModels`, même pattern que l'explorateur).
 */
class PanneauJournalFragment : Fragment() {
    private var liaisonAmorce: FragmentPanneauJournalBinding? = null
    private val liaison get() = liaisonAmorce!!

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Adaptateur de la fenêtre compacte. */
    private lateinit var adaptateur: EntreesJournalCompactesAdapter

    /** Taille de la dernière fenêtre rendue (suivi direct). */
    private var tailleDerniereFenetre = 0

    /** Mise à jour programmatique des filtres (garde anti-renvoi). */
    private var majProgrammatiqueFiltres = false

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauJournalBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        adaptateur = EntreesJournalCompactesAdapter()
        liaison.listeJournal.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeJournal.adapter = adaptateur

        // Filtres par niveau — même règle que l'écran Diagnostic (étape 12).
        liaison.chipJournalDebug.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.DEBUG))
        }
        liaison.chipJournalInfo.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.INFO))
        }
        liaison.chipJournalWarn.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.WARN))
        }
        liaison.chipJournalError.setOnCheckedChangeListener { _, _ ->
            if (!majProgrammatiqueFiltres) viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))
        }

        // Lien vers le journal complet — l'historique et les exports
        // restent à l'écran Diagnostic (version compacte).
        liaison.boutonJournalComplet.setOnClickListener {
            viewModel.onAction(ActionEditor.OuvrirJournalComplet)
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rend la fenêtre (suivi direct quand elle grandit), l'état vide et
     *  les filtres — cochés selon l'état, sans renvoyer l'action. */
    private fun rendre(etat: EtatEditor) {
        adaptateur.submitList(etat.entreesJournal)
        if (etat.entreesJournal.size > tailleDerniereFenetre && etat.entreesJournal.isNotEmpty()) {
            liaison.listeJournal.scrollToPosition(etat.entreesJournal.lastIndex)
        }
        tailleDerniereFenetre = etat.entreesJournal.size
        liaison.texteJournalVide.isVisible = etat.entreesJournal.isEmpty()

        majProgrammatiqueFiltres = true
        liaison.chipJournalDebug.isChecked = LogLevel.DEBUG in etat.filtresJournal
        liaison.chipJournalInfo.isChecked = LogLevel.INFO in etat.filtresJournal
        liaison.chipJournalWarn.isChecked = LogLevel.WARN in etat.filtresJournal
        liaison.chipJournalError.isChecked = LogLevel.ERROR in etat.filtresJournal
        majProgrammatiqueFiltres = false
    }
}
