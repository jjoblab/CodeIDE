package jo.codeide.feature.diagnostics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.model.CrashReportSummary
import jo.codeide.feature.diagnostics.databinding.ItemRapportPlantageBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Liste des rapports de plantage de l'onglet Plantages (étape 12) : du
 * plus récent au plus ancien — l'appui ouvre l'écran dédié en consultation,
 * le bouton d'icône supprime après confirmation.
 */
internal class RapportsPlantageAdapter(
    private val surOuverture: (String) -> Unit,
    private val surSuppression: (String) -> Unit,
) : ListAdapter<CrashReportSummary, RapportsPlantageAdapter.Vue>(
        object : DiffUtil.ItemCallback<CrashReportSummary>() {
            override fun areItemsTheSame(
                ancienne: CrashReportSummary,
                nouvelle: CrashReportSummary,
            ): Boolean = ancienne.id == nouvelle.id

            override fun areContentsTheSame(
                ancienne: CrashReportSummary,
                nouvelle: CrashReportSummary,
            ): Boolean = ancienne == nouvelle
        },
    ) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Vue {
        val liaison =
            ItemRapportPlantageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return Vue(liaison, surOuverture, surSuppression)
    }

    override fun onBindViewHolder(
        holder: Vue,
        position: Int,
    ) {
        holder.lier(getItem(position))
    }

    /** Ligne d'un rapport : date, type, exception, résumé, pastille consulté. */
    internal class Vue(
        private val liaison: ItemRapportPlantageBinding,
        surOuverture: (String) -> Unit,
        surSuppression: (String) -> Unit,
    ) : RecyclerView.ViewHolder(liaison.root) {
        private var courant: CrashReportSummary? = null

        init {
            liaison.root.setOnClickListener { courant?.let { surOuverture(it.id) } }
            liaison.boutonSupprimer.setOnClickListener { courant?.let { surSuppression(it.id) } }
        }

        /** Affiche un résumé de rapport. */
        fun lier(rapport: CrashReportSummary) {
            courant = rapport
            val contexte = liaison.root.context
            liaison.dateRapport.text =
                FORMAT_DATE.format(Instant.ofEpochMilli(rapport.timestampMillis).atZone(ZoneId.systemDefault()))
            liaison.typeRapport.text = contexte.getString(TraductionsDiagnostic.type(rapport.type))
            liaison.exceptionRapport.text = rapport.exceptionClassName
            liaison.resumeRapport.text = rapport.shortMessage
            liaison.pastilleNonConsulte.isVisible = !rapport.isReviewed
            liaison.boutonSupprimer.contentDescription =
                contexte.getString(R.string.diagnostics_supprimer_rapport_description, rapport.id)
            liaison.root.contentDescription =
                contexte.getString(
                    R.string.diagnostics_rapport_description,
                    liaison.dateRapport.text,
                    liaison.typeRapport.text,
                    rapport.exceptionClassName,
                )
        }

        private companion object {
            /** Date lisible du plantage, fuseau de l'appareil. */
            val FORMAT_DATE: DateTimeFormatter =
                DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss")
        }
    }
}
