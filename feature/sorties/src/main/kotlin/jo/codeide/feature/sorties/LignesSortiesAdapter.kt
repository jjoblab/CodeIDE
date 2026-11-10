package jo.codeide.feature.sorties

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import jo.codeide.core.domain.CouleurAnsi
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.ui.NiveauPastilleSortie
import jo.codeide.feature.sorties.databinding.LigneSortieBinding
import jo.codeide.core.ui.R as RUi

/**
 * L'adaptateur des rangées du lecteur (ADR 0159 décision 2, lot L2) :
 * RecyclerView VIRTUALISÉ sur le modèle de rangées — **sans DiffUtil**
 * (leçon de l'ADR 0074 : append par lot, notifications bornées, les
 * deltas viennent de l'index de projection du ViewModel).
 *
 * Le bind n'alloue PAS les analyses : les segments SGR sont pré-parsés
 * dans [RangeeSortie] à l'ingestion — le bind ne construit que les spans
 * AndroidX qui les portent, sur des rangées recyclées.
 *
 * Couleurs ANSI minimales par RÔLES DE THÈME (spec SORTIES § 3.9 — les
 * huit couleurs analytiques se mappent sur les rôles sémantiques réels ;
 * l'intensité « vive » reprend le même rôle — la distinction d'éclat
 * reste portée par le gras de SGR 1 ; le parseur du lot L1, lui, les
 * distingue toutes).
 *
 * Sélection : la plage de rangées sélectionnée est teintée
 * (`colorPrimaryContainer` — c'est une sélection, jamais une erreur).
 *
 * TalkBack : chaque rangée annonce niveau, source, horodatage relatif et
 * texte (spec SORTIES § 3.10 — description assemblée au bind).
 *
 * @param surLigneAppuyee appui simple (index de rangée) — le fragment
 *           étend la sélection si le mode est ouvert.
 * @param surLigneLongueAppuyee appui long (index de rangée) — ouvre le
 *           mode sélection.
 */
internal class LignesSortiesAdapter(
    private val surLigneAppuyee: (Int) -> Unit,
    private val surLigneLongueAppuyee: (Int) -> Unit,
) : RecyclerView.Adapter<LignesSortiesAdapter.LigneViewHolder>() {
    private var rangees: List<RangeeSortie> = emptyList()
    private var options: OptionsRenduRangee = OptionsRenduRangee()
    private var origineMs: Long = 0L
    private var selection: IntRange? = null

    /** Les couleurs ANSI par rôle de thème — résolues UNE fois au
     *  rattachement (jour/nuit par le thème courant). */
    private lateinit var couleursAnsi: Map<CouleurAnsi, Int>

    /** La teinte de la SÉLECTION (rôle conteneur primaire) — résolue au
     *  rattachement, posée au bind (patron IndicateurEtat). */
    private var teinteSelection: android.content.res.ColorStateList? = null

    /** Les options de rendu courantes (le zoom pincé les lit). */
    internal val optionsCourantes: OptionsRenduRangee
        get() = options

    /** Les réglages changent (bascules de la barre, zoom, origine). */
    internal fun definirOptions(valeur: OptionsRenduRangee, origine: Long) {
        options = valeur
        origineMs = origine
        notifyDataSetChanged()
    }

    /** La plage sélectionnée change — les rangees se teintent. */
    internal fun definirSelection(plage: IntRange?) {
        selection = plage
        notifyDataSetChanged()
    }

    /**
     * Publie la projection : delta non nul → notifications BORNÉES
     * (retrait de tête + append fin) ; `null` → re-liaison complète
     * (reprise de pause, filtre, effacement).
     */
    internal fun publierProjection(rangeesNouvelles: List<RangeeSortie>, delta: DeltaProjection?) {
        rangees = rangeesNouvelles
        if (delta == null || rangeesNouvelles.isEmpty()) {
            notifyDataSetChanged()
            return
        }
        if (delta.retraitTete > 0) {
            notifyItemRangeRemoved(0, delta.retraitTete)
        }
        if (delta.ajoutFin > 0) {
            val debut = (rangeesNouvelles.size - delta.ajoutFin).coerceAtLeast(0)
            notifyItemRangeInserted(debut, delta.ajoutFin)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        couleursAnsi = CouleurAnsi.entries.associateWith { couleur -> couleurRole(recyclerView.context, couleur) }
        teinteSelection = android.content.res.ColorStateList.valueOf(
            MaterialColors.getColor(
                recyclerView.context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                0,
            ),
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LigneViewHolder = LigneViewHolder(
        LigneSortieBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount(): Int = rangees.size

    override fun onBindViewHolder(holder: LigneViewHolder, position: Int) {
        holder.lier(position, rangees[position])
    }

    /** La teinte de rôle d'une couleur ANSI (résolue au rattachement). */
    private fun couleurRole(contexte: Context, couleur: CouleurAnsi): Int = MaterialColors.getColor(
        contexte,
        when (couleur) {
            CouleurAnsi.NOIR -> com.google.android.material.R.attr.colorOnSurfaceVariant
            CouleurAnsi.ROUGE -> androidx.appcompat.R.attr.colorError
            CouleurAnsi.VERT -> RUi.attr.colorSucces
            CouleurAnsi.JAUNE -> RUi.attr.colorAvertissement
            CouleurAnsi.BLEU -> androidx.appcompat.R.attr.colorPrimary
            CouleurAnsi.MAGENTA -> com.google.android.material.R.attr.colorTertiary
            CouleurAnsi.CYAN -> com.google.android.material.R.attr.colorSecondary
            CouleurAnsi.BLANC -> com.google.android.material.R.attr.colorOnSurface
        },
        0,
    )

    /** La vue d'une ligne de sortie — rangee dense monospace. */
    internal inner class LigneViewHolder(internal val liaison: LigneSortieBinding) :
        RecyclerView.ViewHolder(liaison.root) {
        internal fun lier(indice: Int, rangee: RangeeSortie) {
            val contexte = liaison.root.context
            liaison.numeroSortie.isGone = !options.afficheNumeros
            if (options.afficheNumeros) {
                liaison.numeroSortie.text = (indice + 1).toString()
            }
            liaison.pastilleSortie.setNiveau(niveauPastille(rangee.niveau))
            liaison.horodatageSortie.isGone = !options.afficheHorodatage
            if (options.afficheHorodatage) {
                liaison.horodatageSortie.text = if (options.horodatageAbsolu) {
                    formaterHorodatageAbsolu(rangee.horodatageMs)
                } else {
                    formaterHorodatageRelatif(rangee.horodatageMs, origineMs)
                }
            }
            liaison.texteSortie.setTextSize(TypedValue.COMPLEX_UNIT_SP, options.taillePoliceSp.toFloat())
            if (!options.retourALaLigne) {
                // Réglage M13 sans retour à la ligne : coupe au bord du
                // viewport (comportement console standard — le texte
                // complet reste porté par la copie et TalkBack).
                liaison.texteSortie.maxLines = 1
            } else {
                liaison.texteSortie.maxLines = Integer.MAX_VALUE
            }
            liaison.texteSortie.ellipsize = null
            liaison.texteSortie.text = texteDe(contexte, rangee)
            val plage = selection
            liaison.root.isSelected = plage != null && indice in plage
            liaison.root.backgroundTintList = if (plage != null && indice in plage) teinteSelection else null
            liaison.root.setOnClickListener { surLigneAppuyee(indice) }
            liaison.root.setOnLongClickListener {
                surLigneLongueAppuyee(indice)
                true
            }
            // TalkBack : niveau, source, horodatage relatif, texte
            // (spec SORTIES § 3.10 — jamais la couleur seule).
            liaison.root.contentDescription = contexte.getString(
                R.string.sorties_a11y_ligne,
                contexte.getString(nomNiveauDe(rangee.niveau)),
                contexte.getString(nomSourceDe(rangee.source)),
                formaterHorodatageRelatif(rangee.horodatageMs, origineMs),
                rangee.textePur,
            )
        }

        /**
         * La rangée est-elle SANS aucun style rendu (ni gras, ni italique,
         * ni couleur ANSI) ni résumé de téléchargement ? Alors le texte
         * pur suffit — aucun span à construire.
         */
        private fun aPlat(rangee: RangeeSortie): Boolean = rangee.segments.all { segment ->
            !segment.gras && !segment.italique && segment.couleur == null
        } && resumeTelechargement(rangee.octetsRecus, rangee.octetsTotal) == null

        /** Le texte rendu : spans gras/italique/couleur ANSI minimale. */
        private fun texteDe(contexte: Context, rangee: RangeeSortie): CharSequence {
            if (aPlat(rangee)) {
                return rangee.textePur
            }
            val resume = resumeTelechargement(rangee.octetsRecus, rangee.octetsTotal)
            val construit = SpannableStringBuilder()
            rangee.segments.forEach { segment ->
                val debut = construit.length
                construit.append(segment.texte)
                if (segment.gras) {
                    construit.setSpan(StyleSpan(Typeface.BOLD), debut, construit.length, 0)
                }
                if (segment.italique) {
                    construit.setSpan(StyleSpan(Typeface.ITALIC), debut, construit.length, 0)
                }
                segment.couleur?.let { couleur ->
                    construit.setSpan(
                        ForegroundColorSpan(couleursAnsi.getValue(couleur)),
                        debut,
                        construit.length,
                        0,
                    )
                }
            }
            resume?.let {
                val debut = construit.length
                construit.append("  ").append(it)
                construit.setSpan(
                    ForegroundColorSpan(
                        MaterialColors.getColor(contexte, androidx.appcompat.R.attr.colorPrimary, 0),
                    ),
                    debut,
                    construit.length,
                    0,
                )
            }
            return construit
        }
    }
}

/** Le niveau de pastille core:ui qui porte la sévérité rendue. */
internal fun niveauPastille(niveau: NiveauSortie): NiveauPastilleSortie = when (niveau) {
    NiveauSortie.VERBEUX -> NiveauPastilleSortie.VERBEUX
    NiveauSortie.DEBOGAGE -> NiveauPastilleSortie.DEBOGAGE
    NiveauSortie.INFO -> NiveauPastilleSortie.INFO
    NiveauSortie.AVERTISSEMENT -> NiveauPastilleSortie.AVERTISSEMENT
    NiveauSortie.ERREUR -> NiveauPastilleSortie.ERREUR
    NiveauSortie.ASSERT -> NiveauPastilleSortie.ASSERT
    NiveauSortie.INDETERMINE -> NiveauPastilleSortie.INDETERMINE
}

/** Le nom localisé d'un niveau (TalkBack). */
internal fun nomNiveauDe(niveau: NiveauSortie): Int = when (niveau) {
    NiveauSortie.VERBEUX -> RUi.string.sorties_niveau_verbeux
    NiveauSortie.DEBOGAGE -> RUi.string.sorties_niveau_debogage
    NiveauSortie.INFO -> RUi.string.sorties_niveau_info
    NiveauSortie.AVERTISSEMENT -> RUi.string.sorties_niveau_avertissement
    NiveauSortie.ERREUR -> RUi.string.sorties_niveau_erreur
    NiveauSortie.ASSERT -> RUi.string.sorties_niveau_assert
    NiveauSortie.INDETERMINE -> RUi.string.sorties_niveau_indefini
}

/** Le nom localisé d'une famille de sorties (TalkBack). */
internal fun nomSourceDe(source: SourceSortie): Int = when (source) {
    SourceSortie.BUILD -> R.string.sorties_source_build
    SourceSortie.SYNC -> R.string.sorties_source_sync
    SourceSortie.TACHES -> R.string.sorties_source_taches
    SourceSortie.SERVEUR -> R.string.sorties_source_serveur
    SourceSortie.LOGCAT -> R.string.sorties_source_logcat
    SourceSortie.JOURNAL -> R.string.sorties_source_journal
}
