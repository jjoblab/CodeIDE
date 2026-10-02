package jo.codeide.feature.editor

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.GroupeProblemesBinding
import jo.codeide.feature.editor.databinding.LigneProblemeBinding

/**
 * Rang aplati de l'onglet Problèmes (G5) : soit l'en-tête d'un groupe
 * (fichier + compte), soit un diagnostic cliquable (saut à la ligne).
 */
internal sealed interface RangeeProbleme {
    /** En-tête du groupe [groupe]. */
    data class EnTete(
        val groupe: GroupeProblemes,
    ) : RangeeProbleme

    /** Diagnostic cliquable, porté par le groupe [groupe]. */
    data class Diagnostic(
        val groupe: GroupeProblemes,
        val diagnostic: jo.codeide.core.domain.DiagnosticBuild,
    ) : RangeeProbleme
}

/** Aplatit les groupes en rangées (en-tête puis diagnostics, par groupe). */
internal fun List<GroupeProblemes>.aplatir(): List<RangeeProbleme> =
    flatMap { groupe ->
        listOf<RangeeProbleme>(RangeeProbleme.EnTete(groupe)) +
            groupe.problems.map { diagnostic -> RangeeProbleme.Diagnostic(groupe, diagnostic) }
    }

/**
 * Adaptateur de l'onglet Problèmes (G5, §6) : groupes par fichier
 * (en-tête replié sur le nom + compte), diagnostics triés par ligne ;
 * l'appui sur un diagnostic remonte au domaine pour le saut à la ligne.
 *
 * @param surSaut appelé avec (fichier absolu, ligne) du diagnostic choisi.
 */
internal class ProblemesAdapter(
    private val surSaut: (fichier: String, ligne: Int) -> Unit,
) : ListAdapter<RangeeProbleme, RecyclerView.ViewHolder>(DiffRangees) {
    /** Fabrique des rangées (en-tête / diagnostic). */
    private enum class Type {
        EN_TETE,
        DIAGNOSTIC,
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is RangeeProbleme.EnTete -> Type.EN_TETE.ordinal
            is RangeeProbleme.Diagnostic -> Type.DIAGNOSTIC.ordinal
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        when (viewType) {
            Type.EN_TETE.ordinal -> {
                EnTeteHolder(GroupeProblemesBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

            else -> {
                DiagnosticHolder(LigneProblemeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val rangee = getItem(position)) {
            is RangeeProbleme.EnTete -> {
                (holder as EnTeteHolder).lier(rangee.groupe)
            }

            is RangeeProbleme.Diagnostic -> {
                (holder as DiagnosticHolder).lier(
                    rangee,
                ) { fichier, ligne -> surSaut(fichier, ligne) }
            }
        }
    }

    /** En-tête : nom du fichier et compte de diagnostics. */
    private class EnTeteHolder(
        private val liaison: GroupeProblemesBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(groupe: GroupeProblemes) {
            liaison.nomGroupe.text = groupe.nomFichier
            liaison.compteGroupe.text =
                String.format(java.util.Locale.ROOT, "%d", groupe.problems.size)
        }
    }

    /** Diagnostic : pastille de sévérité, message, position. */
    private class DiagnosticHolder(
        private val liaison: LigneProblemeBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(
            rangee: RangeeProbleme.Diagnostic,
            surSaut: (String, Int) -> Unit,
        ) {
            val diagnostic = rangee.diagnostic
            liaison.pointProbleme.setImageResource(pointDeSeverite(diagnostic.severite))
            liaison.messageProbleme.text = diagnostic.message
            liaison.positionProbleme.text =
                String.format(java.util.Locale.ROOT, "%d", diagnostic.ligne)
            liaison.racineLigne.setOnClickListener {
                surSaut(rangee.groupe.fichier, diagnostic.ligne.toInt())
            }
        }

        private fun pointDeSeverite(severite: jo.codeide.core.domain.SeveriteDiagnostic): Int =
            when (severite) {
                jo.codeide.core.domain.SeveriteDiagnostic.ERREUR -> R.drawable.point_probleme_erreur
                jo.codeide.core.domain.SeveriteDiagnostic.AVERTISSEMENT -> R.drawable.point_probleme_avertissement
                jo.codeide.core.domain.SeveriteDiagnostic.INFO -> R.drawable.point_probleme_info
            }
    }

    private object DiffRangees : DiffUtil.ItemCallback<RangeeProbleme>() {
        override fun areItemsTheSame(
            ancienne: RangeeProbleme,
            nouvelle: RangeeProbleme,
        ): Boolean =
            when (ancienne) {
                is RangeeProbleme.EnTete -> {
                    nouvelle is RangeeProbleme.EnTete && ancienne.groupe.fichier == nouvelle.groupe.fichier
                }

                is RangeeProbleme.Diagnostic -> {
                    nouvelle is RangeeProbleme.Diagnostic &&
                        ancienne.diagnostic.fichier == nouvelle.diagnostic.fichier &&
                        ancienne.diagnostic.ligne == nouvelle.diagnostic.ligne &&
                        ancienne.diagnostic.message == nouvelle.diagnostic.message
                }
            }

        override fun areContentsTheSame(
            ancienne: RangeeProbleme,
            nouvelle: RangeeProbleme,
        ): Boolean = ancienne == nouvelle
    }
}
