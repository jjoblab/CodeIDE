package jo.codeide.feature.editor

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.feature.editor.databinding.LigneEntreeJournalCompacteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liste du journal applicatif compact du panneau inférieur (étape 16, ADR
 * 0029) : une ligne = niveau coloré, heure, étiquette et message tronqué —
 * sans action (les exports et l'historique complet restent à l'écran
 * Diagnostic, accessible par le lien « Ouvrir le journal complet »).
 *
 * [LogEntry] est une data class : le diff porte sur l'égalité structurelle,
 * suffisante pour une fenêtre de 200 lignes reconstruite en bloc.
 */
internal class EntreesJournalCompactesAdapter :
    ListAdapter<LogEntry, EntreesJournalCompactesAdapter.VueEntree>(DiffEntrees) {
    /** Vue d'une ligne du journal compact. */
    internal class VueEntree(
        val liaison: LigneEntreeJournalCompacteBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VueEntree =
        VueEntree(
            LigneEntreeJournalCompacteBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(
        holder: VueEntree,
        position: Int,
    ) {
        val entree = getItem(position)
        val contexte = holder.liaison.root.context
        holder.liaison.niveauEntree.text = entree.level.name
        holder.liaison.niveauEntree.setTextColor(ContextCompat.getColor(contexte, couleurNiveau(entree.level)))
        // Format créé à la liaison : la locale peut changer pendant que
        // l'application vit (le cache statique serait fautif — ConstantLocale).
        holder.liaison.heureEntree.text =
            SimpleDateFormat(FORMAT_HEURE, Locale.getDefault()).format(Date(entree.timestampMillis))
        holder.liaison.etiquetteEntree.text = entree.tag
        holder.liaison.messageEntree.text = entree.message
    }

    /** Couleur du niveau, jour/nuit par ressources qualifiées. */
    private fun couleurNiveau(niveau: LogLevel): Int =
        when (niveau) {
            LogLevel.DEBUG -> jo.codeide.core.ui.R.color.codeide_journal_debug
            LogLevel.INFO -> jo.codeide.core.ui.R.color.codeide_journal_info
            LogLevel.WARN -> jo.codeide.core.ui.R.color.codeide_journal_warn
            LogLevel.ERROR -> jo.codeide.core.ui.R.color.codeide_journal_error
        }

    private companion object {
        /** Motif d'heure compacte (le journal vit dans la session courante). */
        const val FORMAT_HEURE = "HH:mm:ss"
    }

    /** Diff structurel des entrées. */
    private object DiffEntrees : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(
            ancienne: LogEntry,
            nouvelle: LogEntry,
        ): Boolean = ancienne == nouvelle

        override fun areContentsTheSame(
            ancienne: LogEntry,
            nouvelle: LogEntry,
        ): Boolean = ancienne == nouvelle
    }
}
