package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.feature.editor.databinding.LigneLogLogcatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lettre logcat d'un niveau (V D I W E F) — légende § 4.2, partagée
 *  avec la copie brute de ligne du fragment. */
internal fun lettreNiveauLogcat(niveau: NiveauJournal): String =
    when (niveau) {
        NiveauJournal.VERBEUX -> "V"
        NiveauJournal.DEBOGAGE -> "D"
        NiveauJournal.INFO -> "I"
        NiveauJournal.AVERTISSEMENT -> "W"
        NiveauJournal.ERREUR -> "E"
        NiveauJournal.ASSERT -> "F"
    }

/**
 * Adaptateur des lignes de l'onglet Logcat (mission « Exécuter » R3,
 * spec EXECUTER.md § 4) : table monospace 11 sp — horodatage, pid-tid,
 * étiquette, niveau, message.
 *
 * **Virtualisation obligatoire** (spec § 4.2 : jamais de `TextView` à
 * append sans borne) : `ListAdapter` + DiffUtil, l'identité d'une ligne
 * est son NUMÉRO (strictement croissant par session — le contenu peut
 * se répéter, la position peut être filtrée).
 *
 * Couleurs de niveau (jetons § 4.2, jour/nuit par ressources) : V
 * texte-3, D accent, I vert, W orange, E/F rouge — résolues UNE fois à
 * la construction (aucune allocation au bind, critère d'acceptation § 6).
 *
 * @param contexte contexte des ressources (couleurs de niveaux).
 * @param surLigneLongueAppuyee copie de la ligne brute (clic long,
 *        spec § 4.3 — l'export reste une action explicite ailleurs).
 */
class LignesLogcatAdapter(
    contexte: Context,
    private val surLigneLongueAppuyee: (LigneJournal) -> Unit,
) : ListAdapter<LigneLogcatNumerotee, LignesLogcatAdapter.LigneViewHolder>(DIFF) {
    /** Format d'horodatage des colonnes (HH:mm:ss.SSS, invariant locale). */
    private val formatHeure = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Couleur de niveau par niveau — résolue une fois (jour/nuit). */
    private val couleursNiveaux =
        NiveauJournal.entries.associateWith { niveau ->
            ContextCompat.getColor(contexte, couleurDeNiveau(niveau))
        }

    /** Vue d'une ligne de journal. */
    inner class LigneViewHolder internal constructor(
        private val liaison: LigneLogLogcatBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        internal fun lier(entree: LigneLogcatNumerotee) {
            val ligne = entree.ligne
            liaison.horodatageLog.text = formatHeure.format(Date(ligne.horodatageMs))
            // Formatage explicite par ressource (pid-tid, pas d'I18n).
            liaison.pidTidLog.text = liaison.root.context.getString(R.string.logcat_pid_tid, ligne.pid, ligne.tid)
            liaison.etiquetteLog.text = ligne.etiquette
            liaison.niveauLog.text = lettreNiveauLogcat(ligne.niveau)
            liaison.messageLog.text = ligne.message

            liaison.niveauLog.setTextColor(couleursNiveaux.getValue(ligne.niveau))
            liaison.niveauLog.setTypeface(
                Typeface.DEFAULT,
                if (ligne.niveau == NiveauJournal.ASSERT) Typeface.BOLD else Typeface.NORMAL,
            )

            liaison.root.setOnLongClickListener {
                surLigneLongueAppuyee(ligne)
                true
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LigneViewHolder =
        LigneViewHolder(LigneLogLogcatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: LigneViewHolder,
        position: Int,
    ) = holder.lier(getItem(position))

    /** Ressource de couleur d'un niveau (jetons § 4.2). */
    private fun couleurDeNiveau(niveau: NiveauJournal): Int =
        when (niveau) {
            NiveauJournal.VERBEUX -> R.color.logcat_niveau_verbeux
            NiveauJournal.DEBOGAGE -> R.color.logcat_niveau_debogage
            NiveauJournal.INFO -> R.color.logcat_niveau_info
            NiveauJournal.AVERTISSEMENT -> R.color.logcat_niveau_avertissement
            NiveauJournal.ERREUR -> R.color.logcat_niveau_erreur
            NiveauJournal.ASSERT -> R.color.logcat_niveau_erreur
        }

    private companion object {
        /** Identité = numéro ; contenu = ligne complète. */
        val DIFF =
            object : DiffUtil.ItemCallback<LigneLogcatNumerotee>() {
                override fun areItemsTheSame(
                    ancienne: LigneLogcatNumerotee,
                    nouvelle: LigneLogcatNumerotee,
                ): Boolean = ancienne.numero == nouvelle.numero

                override fun areContentsTheSame(
                    ancienne: LigneLogcatNumerotee,
                    nouvelle: LigneLogcatNumerotee,
                ): Boolean = ancienne.ligne == nouvelle.ligne
            }
    }
}
