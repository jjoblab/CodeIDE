package jo.codeide.feature.diagnostics

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.SimpleTextWatcher
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.diagnostics.databinding.DialogueDetailEntreeBinding
import jo.codeide.feature.diagnostics.databinding.PageJournalBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Onglet Journaux de l'écran Diagnostic (étape 12) : visionneuse des 500
 * dernières entrées (les plus anciennes se révèlent au défilement),
 * filtres par niveau, recherche avec délai, suivi en direct, actions
 * Partager / Enregistrer / Effacer et réglage du niveau de journalisation.
 */
@AndroidEntryPoint
class JournalFragment : BaseFragment<PageJournalBinding>() {
    private val viewModel: JournalViewModel by viewModels()

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

    private val adaptateurEntrees = EntreesJournalAdapter { entree -> ouvrirDetail(entree) }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageJournalBinding = PageJournalBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.listeEntrees.layoutManager = LinearLayoutManager(requireContext())
        binding.listeEntrees.adapter = adaptateurEntrees
        binding.listeEntrees.addOnScrollListener(observateurDefilement)

        binding.champRecherche.editText?.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) {
                    viewModel.onAction(ActionJournal.Rechercher(texte?.toString().orEmpty()))
                }
            },
        )

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

        binding.boutonSuiviDirect.setOnClickListener {
            viewModel.onAction(ActionJournal.BasculerSuiviDirect)
        }
        binding.boutonPartager.setOnClickListener {
            viewModel.onAction(ActionJournal.Partager)
        }
        binding.boutonEnregistrer.setOnClickListener {
            lanceurEnregistrer.launch(nomArchivePropose())
        }
        binding.boutonEffacer.setOnClickListener { confirmerEffacement() }
        binding.boutonVerbosite.setOnClickListener { choisirVerbosite() }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(viewLifecycleOwner) { effet -> appliquer(effet) }
    }

    /** Rend l'état de la visionneuse. */
    private fun rendre(etat: EtatJournal) {
        adaptateurEntrees.submitList(etat.entrees)
        binding.progression.isVisible = etat.chargement
        binding.chipDebug.isChecked = LogLevel.DEBUG in etat.filtresNiveaux
        binding.chipInfo.isChecked = LogLevel.INFO in etat.filtresNiveaux
        binding.chipWarn.isChecked = LogLevel.WARN in etat.filtresNiveaux
        binding.chipError.isChecked = LogLevel.ERROR in etat.filtresNiveaux
        binding.boutonSuiviDirect.isChecked = etat.suivreDirect
        binding.boutonVerbosite.text = getString(TraductionsDiagnostic.verbosite(etat.verbosite))

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

        binding.texteVide.isVisible = !etat.chargement && etat.entrees.isEmpty()
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

            EffetJournal.DefilerVersBas -> {
                binding.listeEntrees.scrollToPosition(adaptateurEntrees.itemCount - 1)
            }
        }
    }

    /** Révèle les entrées plus anciennes quand le défilement atteint le haut. */
    private val observateurDefilement =
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: RecyclerView,
                dx: Int,
                dy: Int,
            ) {
                if (dy >= 0) return
                val gestionnaire = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (gestionnaire.findFirstCompletelyVisibleItemPosition() <= SEUIL_HAUT &&
                    viewModel.etat.value.plusAnciennesDisponibles
                ) {
                    viewModel.onAction(ActionJournal.ChargerPlusAnciennes)
                }
            }
        }

    /** Ouvre le détail d'une entrée (exception repliable). */
    private fun ouvrirDetail(entree: LogEntry) {
        val contexte = requireContext()
        val vue = DialogueDetailEntreeBinding.inflate(layoutInflater)

        vue.texteHorodatage.text =
            SimpleDateFormat("EEEE d MMMM yyyy · HH:mm:ss.SSS", Locale.getDefault())
                .format(Date(entree.timestampMillis))
        vue.texteNiveau.text = contexte.getString(TraductionsDiagnostic.niveau(entree.level))
        vue.texteEtiquette.text = entree.tag
        vue.texteThread.text = entree.threadName
        vue.texteSession.text = entree.sessionId
        vue.texteMessage.text = entree.message

        val exception = entree.exception
        if (exception == null) {
            vue.boutonException.isVisible = false
            vue.texteException.isVisible = false
        } else {
            vue.texteException.isVisible = false
            vue.texteException.text = formatterException(exception)
            vue.boutonException.isVisible = true
            vue.boutonException.setOnClickListener {
                val visible = vue.texteException.isVisible
                vue.texteException.isVisible = !visible
                vue.boutonException.setText(
                    if (visible) {
                        R.string.diagnostics_detail_voir_exception
                    } else {
                        R.string.diagnostics_detail_masquer_exception
                    },
                )
            }
        }

        MaterialAlertDialogBuilder(contexte)
            .setTitle(R.string.diagnostics_detail_titre)
            .setView(vue.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Met en forme la chaîne d'exception aplanie (causes imbriquées). */
    private fun formatterException(exception: FlattenedException): String =
        buildString {
            appendLine("${exception.className}: ${exception.message.orEmpty()}")
            exception.frames.forEach { tranche -> appendLine("    $tranche") }
            var cause = exception.cause
            var profondeur = 1
            while (cause != null && profondeur <= PROFONDEUR_CAUSES_AFFICHEES) {
                appendLine("Caused by: ${cause.className}: ${cause.message.orEmpty()}")
                cause.frames.take(TRANCHES_CAUSE_AFFICHEES).forEach { tranche ->
                    appendLine("    $tranche")
                }
                cause = cause.cause
                profondeur++
            }
        }

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

    private companion object {
        /** Lignes visibles avant de considérer le haut atteint. */
        const val SEUIL_HAUT = 1

        /** Causes affichées dans le détail d'une exception. */
        const val PROFONDEUR_CAUSES_AFFICHEES = 10

        /** Tranches affichées par cause dans le détail. */
        const val TRANCHES_CAUSE_AFFICHEES = 5
    }
}
