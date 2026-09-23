package jo.codeide.feature.diagnostics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.model.LogEntry
import jo.codeide.feature.diagnostics.databinding.ItemEntreeJournalBinding

/**
 * Liste des entrées de journal de l'onglet Journaux (étape 12) :
 * chronologique, la plus récente en bas — l'appui ouvre le détail repliable.
 */
internal class EntreesJournalAdapter(
    private val surEntree: (LogEntry) -> Unit,
) : ListAdapter<LogEntry, EntreesJournalAdapter.Vue>(
        // Égalité structurelle des entrées (data class immuable) : contenu
        // identique = même ligne, jamais de clignotement au dédoublonnage.
        // DiffUtil.equals est intentionnellement contourné (outil:ignore
        // hors layout, règle documentée de l'étape 11).
        object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(
                ancienne: LogEntry,
                nouvelle: LogEntry,
            ): Boolean = ancienne == nouvelle

            override fun areContentsTheSame(
                ancienne: LogEntry,
                nouvelle: LogEntry,
            ): Boolean = ancienne == nouvelle
        },
    ) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Vue {
        val liaison =
            ItemEntreeJournalBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return Vue(liaison, surEntree)
    }

    override fun onBindViewHolder(
        holder: Vue,
        position: Int,
    ) {
        holder.lier(getItem(position))
    }

    /** Ligne d'une entrée : heure, niveau, étiquette, message tronqué. */
    internal class Vue(
        private val liaison: ItemEntreeJournalBinding,
        surEntree: (LogEntry) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var courante: LogEntry? = null

        init {
            liaison.root.setOnClickListener { courante?.let(surEntree) }
        }

        /** Affiche une entrée. */
        fun lier(entree: LogEntry) {
            courante = entree
            val contexte = liaison.root.context
            liaison.heureEntree.text =
                java.text
                    .SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                    .format(java.util.Date(entree.timestampMillis))
            liaison.niveauEntree.text = contexte.getString(TraductionsDiagnostic.niveau(entree.level))
            liaison.etiquetteEntree.text = entree.tag
            liaison.messageEntree.text = entree.message
            liaison.pointeurException.isVisible = entree.exception != null
            liaison.root.contentDescription =
                contexte.getString(
                    R.string.diagnostics_entree_description,
                    contexte.getString(TraductionsDiagnostic.niveau(entree.level)),
                    entree.tag,
                    entree.message,
                )
        }
    }
}
