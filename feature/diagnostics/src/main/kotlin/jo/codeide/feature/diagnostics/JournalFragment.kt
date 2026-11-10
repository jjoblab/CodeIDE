package jo.codeide.feature.diagnostics

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.versNiveauSortie
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.diagnostics.databinding.PageJournalBinding
import jo.codeide.feature.sorties.CollecteurSortiesJournal
import jo.codeide.feature.sorties.ControleurLecteurSorties
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.FragmentLecteurSorties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Onglet Journaux de l'écran Diagnostic (lot L4, ADR 0162) — un
 * CONSOMMATEUR du lecteur du Centre de sorties (famille JOURNAL), pas un
 * second afficheur : la visionneuse (fenêtre vivante, recherche n/N
 * insensible aux accents, suivi, replis des exceptions aplanies, état
 * vide honnête) est le lecteur embarqué ; ce fragment garde le chrome des
 * actions (filtres par niveaux persistés, verbosité, partage,
 * enregistrement, effacement, usage disque) et le bandeau honnête si la
 * lecture d'historique a échoué.
 */
@AndroidEntryPoint
class JournalFragment : BaseFragment<PageJournalBinding>() {
    private val viewModel: JournalViewModel by viewModels()

    /** Le collecteur JOURNAL process-wide (source du lecteur) — son état
     *  de lecture alimente le bandeau honnête. */
    @Inject
    lateinit var collecteur: CollecteurSortiesJournal

    /** Navigation et partage découplés : la feature ne connaît jamais les autres. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Sélecteur de destination SAF de l'action « Enregistrer ». */
    private val lanceurEnregistrer =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null) {
                viewModel.onAction(ActionJournal.Enregistrer(uri.toString()))
            }
        }

    /** Le lecteur enfant (famille JOURNAL) — le même composant partout. */
    private var lecteur: FragmentLecteurSorties? = null

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageJournalBinding = PageJournalBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        brancherLecteur()

        binding.chipDebug.setOnClickListener {
            viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.DEBUG))
        }
        binding.chipInfo.setOnClickListener {
            viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.INFO))
        }
        binding.chipWarn.setOnClickListener {
            viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.WARN))
        }
        binding.chipError.setOnClickListener {
            viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.ERROR))
        }

        binding.boutonPartager.setOnClickListener {
            viewModel.onAction(ActionJournal.Partager)
        }
        binding.boutonEnregistrer.setOnClickListener {
            lanceurEnregistrer.launch(nomArchivePropose())
        }
        binding.boutonEffacer.setOnClickListener { confirmerEffacement() }
        binding.boutonVerbosite.setOnClickListener { choisirVerbosite() }

        // La lecture d'historique en échec est honnête (règle 14) : le
        // lecteur reste utilisable — les entrées vivantes continuent.
        collecteur.lectureEnEchec.collectWithLifecycle(viewLifecycleOwner) { enEchec ->
            binding.bandeauLecture.isVisible = enEchec
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet -> appliquer(effet) }
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

    /** Rend le chrome : chips persistées, verbosité, usage, erreur — et
     *  pousse les filtres au lecteur (filtre unifié, ADR 0160). */
    private fun rendre(etat: EtatJournal) {
        binding.chipDebug.isChecked = LogLevel.DEBUG in etat.filtresNiveaux
        binding.chipInfo.isChecked = LogLevel.INFO in etat.filtresNiveaux
        binding.chipWarn.isChecked = LogLevel.WARN in etat.filtresNiveaux
        binding.chipError.isChecked = LogLevel.ERROR in etat.filtresNiveaux
        binding.boutonVerbosite.text = getString(TraductionsDiagnostic.verbosite(etat.verbosite))

        // Ensemble vide = tous les niveaux (jamais « rien ne passe »).
        val niveaux = etat.filtresNiveaux.map(LogLevel::versNiveauSortie).toSet()
        controleurLecteur()?.definirFiltre(
            if (niveaux.isEmpty()) {
                FiltreSorties()
            } else {
                FiltreSorties(niveaux = niveaux)
            },
        )

        val usage = etat.usageDisque
        binding.texteUsageDisque.text =
            if (usage == null) {
                getString(R.string.diagnostics_usage_inconnu)
            } else {
                getString(
                    R.string.diagnostics_usage_taille,
                    android.text.format.Formatter
                        .formatShortFileSize(requireContext(), usage.bytes),
                ) + " · " +
                    resources.getQuantityString(
                        R.plurals.diagnostics_usage_fichiers,
                        usage.fileCount,
                        usage.fileCount,
                    )
            }

        binding.texteErreur.isVisible = etat.erreur != null
        etat.erreur?.let { erreur ->
            binding.texteErreur.text = getString(TraductionsDiagnostic.message(erreur))
        }
    }

    /** Applique un effet ponctuel. */
    private fun appliquer(effet: EffetJournal) {
        when (effet) {
            is EffetJournal.ArchivePrete -> {
                navigator.partagerArchive(effet.archive.fileName, effet.archive.location)
            }

            EffetJournal.Efface -> {
                // Le dépôt est effacé — le tampon du lecteur aussi (le
                // même geste honnête que « effacer » du lecteur).
                controleurLecteur()?.effacer()
            }
        }
    }

    /** Le contrôleur du lecteur enfant (façade publique — lot L4). */
    private fun controleurLecteur(): ControleurLecteurSorties? = lecteur

    /** Confirmation explicite avant l'effacement (règle du prompt). */
    private fun confirmerEffacement() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_effacer_titre)
            .setMessage(R.string.diagnostics_effacer_message)
            .setNegativeButton(R.string.diagnostics_annuler, null)
            .setPositiveButton(R.string.diagnostics_effacer_confirmer) { dialogue, _ ->
                dialogue.dismiss()
                viewModel.onAction(ActionJournal.Effacer)
            }.show()
    }

    /** Choix du niveau de journalisation (Normal / Détaillé). */
    private fun choisirVerbosite() {
        val courante = viewModel.etat.value.verbosite
        val propositions = LogVerbosity.entries
        val noms = propositions.map { getString(TraductionsDiagnostic.verbosite(it)) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diagnostics_verbosite_titre)
            .setSingleChoiceItems(noms.toTypedArray(), propositions.indexOf(courante)) { dialogue, lequel ->
                dialogue.dismiss()
                viewModel.onAction(ActionJournal.ChoisirVerbosite(propositions[lequel]))
            }.setNegativeButton(R.string.diagnostics_annuler, null)
            .show()
    }

    /** Nom d'archive proposé au sélecteur d'enregistrement (UTC-ish, local). */
    private fun nomArchivePropose(): String {
        val horodatage = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        return "codeide-logs-$horodatage.zip"
    }
}
