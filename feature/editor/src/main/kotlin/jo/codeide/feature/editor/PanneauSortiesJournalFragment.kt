package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.versNiveauSortie
import jo.codeide.core.model.LogLevel
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauSortiesJournalBinding
import jo.codeide.feature.sorties.ControleurLecteurSorties
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.FragmentLecteurSorties

/**
 * Onglet Journal du panneau inférieur (lot L4, ADR 0162) — le chrome
 * compact autour du lecteur UNIQUE du Centre de sorties
 * ([FragmentLecteurSorties], famille [FamilleSorties.JOURNAL]) : l'ancien
 * affichage (`PanneauJournalFragment` + fenêtre mémoire du ViewModel) est
 * remplacé DANS CE LOT (pas de code mort, pas de double affichage).
 *
 * Ce que le chrome garde :
 *
 * - les **chips de niveaux** (état persisté du [EditorViewModel] —
 *   `filtresJournal`, même règle que l'écran Diagnostic : ensemble vide
 *   = tous les niveaux) — chaque bascule est POUSSÉE au lecteur comme un
 *   filtre unifié (`FiltreSorties`, ADR 0160 : rien n'est réécrit) ;
 * - le **lien vers l'écran Diagnostic** (l'historique lu sur disque et
 *   les exports restent là — le lecteur n'en duplique rien).
 *
 * Le lecteur rend tout le reste : fenêtre vivante, recherche, pause,
 * effacement, état vide honnête « le journal de CodeIDE ».
 */
class PanneauSortiesJournalFragment : Fragment() {
    private var liaisonAmorce: FragmentPanneauSortiesJournalBinding? = null

    /** Liaison de la vue courante (correctif n°9 : plus de `!!`). */
    private val liaison: FragmentPanneauSortiesJournalBinding
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison du panneau Journal indisponible — vue détruite ?"
            }

    /** ViewModel de l'ESPACE (chips persistées + lien Diagnostic) — porté
     *  par l'activité (patron d'origine : une seule source d'état). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Le lecteur enfant (famille JOURNAL) — le même composant partout. */
    private var lecteur: FragmentLecteurSorties? = null

    /** Mise à jour programmatique des filtres (garde anti-renvoi). */
    private var majProgrammatiqueFiltres = false

    override fun onCreateView(inflateur: LayoutInflater, conteneur: ViewGroup?, etat: Bundle?): View {
        liaisonAmorce = FragmentPanneauSortiesJournalBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(vue: View, etat: Bundle?) {
        brancherLecteur()

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

        // L'état persisté des chips est poussé AU LECTEUR (filtre unifié —
        // ADR 0160) : le VM ne garde que l'état, jamais un affichage.
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Le lecteur enfant (créé UNE fois — les arguments portent la
     *  famille, le ViewModel survit à la rotation). */
    private fun brancherLecteur() {
        val existant = childFragmentManager.findFragmentById(R.id.conteneur_lecteur)
        val hote = (existant as? FragmentLecteurSorties) ?: FragmentLecteurSorties.nouvelle(FamilleSorties.JOURNAL)
        lecteur = hote
        if (existant == null) {
            childFragmentManager.commit {
                replace(R.id.conteneur_lecteur, hote)
            }
        }
    }

    /** Rend les chips (cochés selon l'état persisté, sans renvoyer
     *  l'action) et pousse le filtre unifié au lecteur. */
    private fun rendre(etat: EtatEditor) {
        majProgrammatiqueFiltres = true
        liaison.chipJournalDebug.isChecked = LogLevel.DEBUG in etat.filtresJournal
        liaison.chipJournalInfo.isChecked = LogLevel.INFO in etat.filtresJournal
        liaison.chipJournalWarn.isChecked = LogLevel.WARN in etat.filtresJournal
        liaison.chipJournalError.isChecked = LogLevel.ERROR in etat.filtresJournal
        majProgrammatiqueFiltres = false

        // Ensemble vide = tous les niveaux : aucun contrainte au filtre
        // (jamais « rien ne passe » — même sémantique que l'écran 12).
        val niveaux = etat.filtresJournal.map(LogLevel::versNiveauSortie).toSet()
        val filtre =
            if (niveaux.isEmpty()) {
                FiltreSorties()
            } else {
                FiltreSorties(niveaux = niveaux)
            }
        controleurLecteur()?.definirFiltre(filtre)
    }

    /** Le contrôleur du lecteur enfant (façade publique — lot L4). */
    private fun controleurLecteur(): ControleurLecteurSorties? = lecteur
}
