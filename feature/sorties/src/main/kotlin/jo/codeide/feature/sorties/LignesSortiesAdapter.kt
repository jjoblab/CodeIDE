package jo.codeide.feature.sorties

import android.annotation.SuppressLint
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
import jo.codeide.core.domain.GroupeLogcat
import jo.codeide.core.domain.GroupeTache
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.ui.NiveauPastilleSortie
import jo.codeide.feature.sorties.databinding.EnteteLogcatSortieBinding
import jo.codeide.feature.sorties.databinding.EnteteTacheSortieBinding
import jo.codeide.feature.sorties.databinding.LigneSortieBinding
import jo.codeide.core.ui.R as RUi

/**
 * L'adaptateur des rangées du lecteur (ADR 0159 décision 2, lot L2 ;
 * lot L3 : DEUX types de vue — les lignes et les en-têtes regroupés) :
 * RecyclerView VIRTUALISÉ sur le modèle de rangées — **sans DiffUtil**
 * (leçon de l'ADR 0074 : append par lot, notifications bornées, les
 * deltas viennent de l'index de projection du ViewModel).
 *
 * Le bind n'alloue PAS les analyses : les segments SGR sont pré-parsés
 * dans [RangeeSortie] à l'ingestion — le bind ne construit que les spans
 * AndroidX qui les portent, sur des rangées recyclées. Les en-têtes
 * regroupés ([RangeeLecteur.EnteteTache]) portent leurs données dans la
 * rangée (une dérivation — jamais une seconde liste, ADR 0078).
 *
 * Couleurs ANSI minimales par RÔLES DE THÈME (spec SORTIES § 3.9 — les
 * huit couleurs analytiques se mappent sur les rôles sémantiques réels ;
 * l'intensité « vive » reprend le même rôle — la distinction d'éclat
 * reste portée par le gras de SGR 1 ; le parseur du lot L1, lui, les
 * distingue toutes).
 *
 * Sélection : la plage de rangées sélectionnée est teintée
 * (`colorPrimaryContainer` — c'est une sélection, jamais une erreur).
 * Les en-têtes ne participent PAS à la sélection (ils portent du
 * contenu, pas des lignes — la copie reste un geste sur les lignes).
 *
 * Liens source (lot L3) : un appui simple sur une ligne qui PORTE une
 * position cliquable ouvre le source (le geste de l'onglet Problèmes —
 * pas de second endroit pour la même information) ; hors sélection, un
 * appui sans position n'agit pas.
 *
 * TalkBack : chaque rangée annonce niveau, source, horodatage relatif et
 * texte (spec SORTIES § 3.10 — description assemblée au bind) ; chaque
 * en-tête annonce chemin, statut, durée, compteur et état de repli.
 *
 * @param surLigneAppuyee appui simple (index de rangée) — le fragment
 *           étend la sélection si le mode est ouvert.
 * @param surLigneLongueAppuyee appui long (index de rangée) — ouvre le
 *           mode sélection.
 * @param surPositionSource appui sur une ligne PORTANT une position
 *           cliquable (liens source, lot L3).
 * @param surEnteteAppuyee appui sur un en-tête (bascule du repli).
 */
@Suppress("TooManyFunctions")
internal class LignesSortiesAdapter(
    private val surLigneAppuyee: (Int) -> Unit,
    private val surLigneLongueAppuyee: (Int) -> Unit,
    private val surPositionSource: (PositionSource) -> Unit,
    private val surEnteteAppuyee: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rangees: List<RangeeLecteur> = emptyList()
    private var options: OptionsRenduRangee = OptionsRenduRangee()
    private var origineMs: Long = 0L
    private var selection: IntRange? = null

    /** Les couleurs ANSI par rôle de thème — résolues UNE fois au
     *  rattachement (jour/nuit par le thème courant). */
    private lateinit var couleursAnsi: Map<CouleurAnsi, Int>

    /** La teinte de la SÉLECTION (rôle conteneur primaire) — résolue au
     *  rattachement, posée au bind (patron IndicateurEtat). */
    private var teinteSelection: android.content.res.ColorStateList? = null

    /** La teinte du CHEMIN d'en-tête portant une ERREUR (rôle erreur). */
    private var teinteErreur: Int = 0

    /** Les options de rendu courantes (le zoom pincé les lit). */
    internal val optionsCourantes: OptionsRenduRangee
        get() = options

    /** Les réglages changent (bascules de la barre, zoom, origine).
     *  Re-liaison complète VOLONTAIRE (les options de rendu touchent
     *  TOUTES les rangées — police, pid/tid, horodatages) : le
     *  notifyDataSetChanged est ici le « dernier recours » assumé de
     *  lint (les événements bornés ne savent pas re-mesurer la hauteur
     *  de chaque rangée). */
    @SuppressLint("NotifyDataSetChanged")
    internal fun definirOptions(valeur: OptionsRenduRangee, origine: Long) {
        options = valeur
        origineMs = origine
        notifyDataSetChanged()
    }

    /** La plage sélectionnée change — les rangees se teintent (toutes :
     *  l'ancienne sélection ET la nouvelle sont impactées). */
    @SuppressLint("NotifyDataSetChanged")
    internal fun definirSelection(plage: IntRange?) {
        selection = plage
        notifyDataSetChanged()
    }

    /**
     * Publie la projection : delta non nul → notifications BORNÉES
     * (retrait de tête + append fin) ; `null` → re-liaison complète
     * (reprise de pause, filtre, effacement, bascule regroupée) —
     * dernier recours assumé pour ce lot, le delta ne le décrit pas
     * (lint NotifyDataSetChanged).
     */
    @SuppressLint("NotifyDataSetChanged")
    internal fun publierProjection(rangeesNouvelles: List<RangeeLecteur>, delta: DeltaProjection?) {
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
        teinteErreur = MaterialColors.getColor(recyclerView.context, androidx.appcompat.R.attr.colorError, 0)
    }

    override fun getItemViewType(position: Int): Int = when (rangees[position]) {
        is RangeeLecteur.EnteteTache -> TYPE_ENTETE
        is RangeeLecteur.EnteteLogcat -> TYPE_ENTETE_LOGCAT
        else -> TYPE_LIGNE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        TYPE_ENTETE -> EnteteViewHolder(
            EnteteTacheSortieBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        TYPE_ENTETE_LOGCAT -> EnteteLogcatViewHolder(
            EnteteLogcatSortieBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        else -> LigneViewHolder(
            LigneSortieBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
    }

    override fun getItemCount(): Int = rangees.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val rangee = rangees[position]) {
            is RangeeLecteur.EnteteTache -> (holder as EnteteViewHolder).lier(rangee)
            is RangeeLecteur.EnteteLogcat -> (holder as EnteteLogcatViewHolder).lier(rangee)
            is RangeeLecteur.Ligne -> (holder as LigneViewHolder).lier(position, rangee.rangee)
        }
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
                // Numéro TECHNIQUE de ligne de sortie — le format localisé
                // suit le précédent ExplorateurAdapter (lint SetTextI18n).
                liaison.numeroSortie.text =
                    String.format(java.util.Locale.getDefault(), "%d", indice + 1)
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
            liaison.root.setOnClickListener {
                // Liens source (lot L3) : une ligne qui porte une position
                // s'ouvre au simple appui HORS sélection (le geste reste
                // explicite — le mode sélection reste maître).
                val position = rangee.positionsSource.firstOrNull()
                if (plage != null || position == null) {
                    surLigneAppuyee(indice)
                } else {
                    surPositionSource(position)
                }
            }
            liaison.root.setOnLongClickListener {
                surLigneLongueAppuyee(indice)
                true
            }
            // TalkBack : niveau, source, horodatage relatif, texte
            // (spec SORTIES § 3.10 — jamais la couleur seule) ; le
            // pid/tid s'annonce TEL QU'AFFICHÉ (option § 5.4 — le
            // helper pur est le seul détenteur du format).
            val pidTid = prefixePidTid(rangee.pid, rangee.tid)
                ?.takeIf { options.affichePidTid }
                ?.let { prefixe -> " $prefixe" }
                ?: ""
            liaison.root.contentDescription = contexte.getString(
                R.string.sorties_a11y_ligne,
                contexte.getString(nomNiveauDe(rangee.niveau)),
                contexte.getString(nomSourceDe(rangee.source)),
                formaterHorodatageRelatif(rangee.horodatageMs, origineMs),
                rangee.textePur,
            ) + pidTid
        }

        /**
         * La rangée est-elle SANS aucun style rendu (ni gras, ni italique,
         * ni couleur ANSI) ni résumé de téléchargement ni préfixe pid/tid
         * (option § 5.4) ? Alors le texte pur suffit — aucun span à
         * construire.
         */
        private fun aPlat(rangee: RangeeSortie): Boolean = rangee.segments.all { segment ->
            !segment.gras && !segment.italique && segment.couleur == null
        } && resumeTelechargement(rangee.octetsRecus, rangee.octetsTotal) == null &&
            prefixePidTid(rangee.pid, rangee.tid)?.takeIf { options.affichePidTid } == null

        /** Le texte rendu : préfixe pid/tid (option § 5.4), spans
         *  gras/italique/couleur ANSI minimale. */
        private fun texteDe(contexte: Context, rangee: RangeeSortie): CharSequence {
            val prefixe = prefixePidTid(rangee.pid, rangee.tid)?.takeIf { options.affichePidTid }
            if (aPlat(rangee) && prefixe == null) {
                return rangee.textePur
            }
            val resume = resumeTelechargement(rangee.octetsRecus, rangee.octetsTotal)
            val construit = SpannableStringBuilder()
            prefixe?.let {
                val debut = construit.length
                construit.append(it).append(' ')
                construit.setSpan(
                    ForegroundColorSpan(
                        MaterialColors.getColor(
                            contexte,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            0,
                        ),
                    ),
                    debut,
                    construit.length,
                    0,
                )
            }
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
                    // Le réglage « couleurs ANSI » (lot L6, M13) coupé :
                    // rendu MONOCHROME — le gras/italique survivent, la
                    // teinte disparaît (le texte reste purgé des
                    // séquences, jamais brut — règle 14).
                    if (options.couleursAnsi) {
                        construit.setSpan(
                            ForegroundColorSpan(couleursAnsi.getValue(couleur)),
                            debut,
                            construit.length,
                            0,
                        )
                    }
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

    /** La vue d'un en-tête regroupé (chemin, statut, durée, repli). */
    internal inner class EnteteViewHolder(internal val liaison: EnteteTacheSortieBinding) :
        RecyclerView.ViewHolder(liaison.root) {
        internal fun lier(rangee: RangeeLecteur.EnteteTache) {
            val contexte = liaison.root.context
            val groupe = rangee.groupe
            liaison.cheminEntete.text = groupe.chemin
            // Le chemin d'une tâche ÉCHOUÉE se colore (rôle erreur — la
            // lettre du statut porte l'information sans la couleur).
            liaison.cheminEntete.setTextColor(
                if (groupe.erreurs > 0 || groupe.statutTexte == jo.codeide.core.domain.StatutTacheGradle.ECHOUEE) {
                    teinteErreur
                } else {
                    MaterialColors.getColor(liaison.cheminEntete, com.google.android.material.R.attr.colorOnSurface, 0)
                },
            )
            liaison.statutEntete.text = statutTexte(groupe)
            liaison.dureeEntete.isGone = groupe.dureeMs == null
            groupe.dureeMs?.let { duree ->
                liaison.dureeEntete.text = dureeTexte(duree)
            }
            liaison.graviteEntete.isGone = groupe.erreurs == 0 && groupe.avertissements == 0
            if (!liaison.graviteEntete.isGone) {
                liaison.graviteEntete.text = graviteTexte(contexte, groupe)
            }
            liaison.chevronEntete.text = if (rangee.replie) {
                contexte.getString(R.string.sorties_repli_ferme)
            } else {
                contexte.getString(R.string.sorties_repli_ouvert)
            }
            liaison.root.setOnClickListener { surEnteteAppuyee(rangee.indiceGroupe) }
            // TalkBack : chemin, statut, compteur, repli (la couleur
            // seule ne porte jamais l'information).
            liaison.root.contentDescription = contexte.getString(
                R.string.sorties_a11y_entete_tache,
                groupe.chemin,
                statutTexte(groupe),
                rangee.lignesVisibles,
                contexte.getString(
                    if (rangee.replie) R.string.sorties_repli_ferme else R.string.sorties_repli_ouvert,
                ),
            )
        }

        /** Le statut affiché : l'ÉVÉNEMENT fait foi, le texte affine (D1). */
        private fun statutTexte(groupe: GroupeTache): String = when (groupe.statutEvenement) {
            jo.codeide.core.domain.StatutTache.ECHOUEE -> "✕"

            jo.codeide.core.domain.StatutTache.SAUTEE -> "–"

            null -> when (groupe.statutTexte) {
                jo.codeide.core.domain.StatutTacheGradle.ECHOUEE -> "✕"
                jo.codeide.core.domain.StatutTacheGradle.A_JOUR -> "○"
                jo.codeide.core.domain.StatutTacheGradle.DEPUIS_CACHE -> "◇"
                jo.codeide.core.domain.StatutTacheGradle.SANS_SOURCE -> "–"
                jo.codeide.core.domain.StatutTacheGradle.SAUTEE -> "–"
                jo.codeide.core.domain.StatutTacheGradle.EXECUTEE -> "●"
                null -> ""
            }

            else -> "●"
        }

        /** La durée serveur affichée (l'événement fait foi — jamais une
         *  mesure locale). */
        private fun dureeTexte(dureeMs: Long): String = when {
            dureeMs >= MS_PAR_SECONDE -> String.format(
                java.util.Locale.getDefault(),
                "%.1f s",
                dureeMs / MS_PAR_SECONDE.toDouble(),
            )

            dureeMs > 0 -> "$dureeMs ms"

            else -> ""
        }

        /** Le résumé de gravité du groupe (« 2 ! · 1 ⚠ »). */
        private fun graviteTexte(contexte: Context, groupe: GroupeTache): String {
            val morceaux = ArrayList<String>()
            if (groupe.erreurs > 0) {
                morceaux.add(
                    contexte.getString(R.string.sorties_entete_erreurs, groupe.erreurs),
                )
            }
            if (groupe.avertissements > 0) {
                morceaux.add(
                    contexte.getString(R.string.sorties_entete_avertissements, groupe.avertissements),
                )
            }
            return morceaux.joinToString(separator = " · ")
        }
    }

    /** La vue d'un en-tête LOGCAT regroupé (lot L5, spec SORTIES § 5.4
     *  M5) — le contenu de la ligne représentée (run répétée ou tête de
     *  trace), le badge honnête (×N ou « N lignes · k causes »), le
     *  chevron. L'appui bascule le repli (MÊME geste que les tâches :
     *  les indices de groupe sont la clé, le tampon ne bouge jamais). */
    internal inner class EnteteLogcatViewHolder(internal val liaison: EnteteLogcatSortieBinding) :
        RecyclerView.ViewHolder(liaison.root) {
        internal fun lier(rangee: RangeeLecteur.EnteteLogcat) {
            val contexte = liaison.root.context
            // Le contenu rendu = la ligne REPRÉSENTÉE (pré-parsée à
            // l'ingestion — le bind n'analyse jamais).
            liaison.texteEnteteLogcat.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                options.taillePoliceSp.toFloat(),
            )
            liaison.texteEnteteLogcat.text = rangee.rangee.textePur
            // Une tête d'erreur se colore (rôle erreur — le badge et le
            // chevron portent le reste sans la couleur seule).
            liaison.texteEnteteLogcat.setTextColor(
                if (rangee.rangee.estErreur) {
                    teinteErreur
                } else {
                    MaterialColors.getColor(
                        liaison.texteEnteteLogcat,
                        com.google.android.material.R.attr.colorOnSurface,
                        0,
                    )
                },
            )
            liaison.badgeEnteteLogcat.text = badge(contexte, rangee)
            liaison.chevronEnteteLogcat.text = if (rangee.replie) {
                contexte.getString(R.string.sorties_repli_ferme)
            } else {
                contexte.getString(R.string.sorties_repli_ouvert)
            }
            liaison.root.setOnClickListener { surEnteteAppuyee(rangee.indiceGroupe) }
            // TalkBack : contenu, badge, compteur, état de repli.
            liaison.root.contentDescription = contexte.getString(
                R.string.sorties_a11y_entete_logcat,
                rangee.rangee.textePur,
                badge(contexte, rangee),
                rangee.lignesVisibles,
                contexte.getString(
                    if (rangee.replie) R.string.sorties_repli_ferme else R.string.sorties_repli_ouvert,
                ),
            )
        }

        /** Le badge honnête : « ×N » (répétition — le compteur AFFICHÉ du
         *  groupe, les masquées restant au bandeau du filtre) ou « N
         *  lignes · k causes » (trace). */
        private fun badge(contexte: Context, rangee: RangeeLecteur.EnteteLogcat): String = when (rangee.groupe) {
            is GroupeLogcat.Repetition -> contexte.getString(
                R.string.sorties_entete_logcat_repetition,
                rangee.lignesVisibles,
            )

            is GroupeLogcat.Trace -> contexte.getString(
                R.string.sorties_entete_logcat_trace,
                rangee.lignesVisibles,
                rangee.causesVisibles,
            )
        }
    }

    private companion object {
        /** Type de vue : une ligne de tampon. */
        const val TYPE_LIGNE = 0

        /** Type de vue : un en-tête regroupé (tâche Gradle). */
        const val TYPE_ENTETE = 1

        /** Type de vue : un en-tête regroupé Logcat (×N ou trace, lot L5). */
        const val TYPE_ENTETE_LOGCAT = 2

        /** Millisecondes par seconde (affichage de la durée serveur). */
        const val MS_PAR_SECONDE = 1_000L
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
