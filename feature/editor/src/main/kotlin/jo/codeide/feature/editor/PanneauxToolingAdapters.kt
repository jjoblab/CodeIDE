package jo.codeide.feature.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.GroupeProblemesBinding
import jo.codeide.feature.editor.databinding.LigneArbreEtapeBinding
import jo.codeide.feature.editor.databinding.LigneDetailTelechargementBinding
import jo.codeide.feature.editor.databinding.LigneProblemeBinding
import jo.codeide.feature.editor.databinding.LigneSortieBinding
import jo.codeide.feature.editor.databinding.LigneSyntheseBuildBinding
import java.util.Locale

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

/**
 * Durée lisible pour l'affichage (s au-delà d'une seconde, ms en dessous) —
 * partagée par l'en-tête du panneau (chrono du build) et les lignes de
 * tâches/étapes de la console (v3).
 */
internal object DureesLisibles {
    /** Formate [dureeMs] : « 4,2s » au-delà de la seconde, « 350ms » en dessous. */
    fun formater(dureeMs: Long): String =
        if (dureeMs >= SEUIL_SECONDE_MS) {
            String.format(Locale.ROOT, "%.1fs", dureeMs / SECONDE_MS)
        } else {
            String.format(Locale.ROOT, "%dms", dureeMs)
        }

    /** Une durée vaut-elle l'affichage (les tâches éclair restent nues,
     *  comme la vue Build d'Android Studio) ? */
    fun digne(dureeMs: Long?): Boolean = dureeMs != null && dureeMs >= SEUIL_SECONDE_MS

    private const val SEUIL_SECONDE_MS = 1_000L

    private const val SECONDE_MS = 1_000.0
}

/**
 * Taille lisible pour l'affichage des téléchargements (v4, §3.3) — octets
 * reçus/total de la distribution et des dépendances : « 1,2 Mo », « 340 Ko ».
 */
internal object OctetsLisibles {
    /** Formate [octets] : Mo au-dessus du méga, Ko au-dessus du kilo. */
    fun formater(octets: Long): String =
        when {
            octets >= OCTETS_PAR_MO -> {
                String.format(Locale.ROOT, "%.1f Mo", octets / OCTETS_PAR_MO.toDouble())
            }

            octets >= OCTETS_PAR_KO -> {
                String.format(Locale.ROOT, "%d Ko", octets / OCTETS_PAR_KO)
            }

            else -> {
                String.format(Locale.ROOT, "%d o", octets)
            }
        }

    private const val OCTETS_PAR_KO = 1_024L

    private const val OCTETS_PAR_MO = 1_024L * 1_024L
}

/**
 * Adaptateur de l'onglet Sortie (G5, §6 ; v0.32.5 : lignes CANALISÉES,
 * ADR 0056 décision 5 ; v3 : lignes TYPIÉES ; v4, §3.3 : ARBRE d'étapes
 * et filtre de canal) — quatre genres de rangées :
 *
 * - **étape d'arbre** (vue Sync) : marqueur ✓ / spinner / ○ + libellé de
 *   la phase + durée MESURÉE (jamais devinée) ;
 * - **détail de téléchargement** sous l'étape active : barre de
 *   progression (octets reçus/total), compteur n/N, artefact courant ;
 * - **ligne existante** (chronologie, vue TOUS et Build) : étiquette de
 *   canal + texte monospace, sortie brute (stderr distincte par la couleur
 *   d'erreur, avertissement bénin apaisé — C5), tâche au fil du build
 *   (en cours : couleur du canal ; fin : durée, sautée grisée, échec
 *   rouge — mise à jour EN PLACE, une ligne par tâche comme la vue Build
 *   d'Android Studio) ;
 * - **synthèse finale du build** (vue Build) : verdict + durée.
 *
 * Les libellés des tâches, étapes et synthèses sont LOCALISÉS ici : les
 * rangées restent pures (construites par [construireRangeesConsole]),
 * aucune chaîne n'y est construite.
 */
internal class ConsoleToolingAdapter : ListAdapter<RangeeConsole, RecyclerView.ViewHolder>(DiffRangeesConsole) {
    private companion object {
        /** Alpha d'une tâche sautée (atténuée vers le fond). */
        private const val ALPHA_ATTENUE = 128

        /** Progression maximale en pourcentage (détail de téléchargement). */
        private const val POURCENT_MAX = 100f
    }

    /** Fabrique des rangées (arbre / détail / ligne / synthèse). */
    private enum class Type {
        ETAPE_ARBRE,
        DETAIL_TELECHARGEMENT,
        LIGNE,
        SYNTHESE,
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is RangeeConsole.EtapeArbre -> Type.ETAPE_ARBRE
            is RangeeConsole.DetailTelechargement -> Type.DETAIL_TELECHARGEMENT
            is RangeeConsole.Ligne -> Type.LIGNE
            is RangeeConsole.SyntheseBuild -> Type.SYNTHESE
        }.ordinal

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflateur = LayoutInflater.from(parent.context)
        return when (viewType) {
            Type.ETAPE_ARBRE.ordinal -> {
                EtapeArbreHolder(
                    LigneArbreEtapeBinding.inflate(inflateur, parent, false),
                )
            }

            Type.DETAIL_TELECHARGEMENT.ordinal -> {
                DetailTelechargementHolder(
                    LigneDetailTelechargementBinding.inflate(inflateur, parent, false),
                )
            }

            Type.SYNTHESE.ordinal -> {
                SyntheseHolder(
                    LigneSyntheseBuildBinding.inflate(inflateur, parent, false),
                )
            }

            else -> {
                LigneHolder(
                    LigneSortieBinding.inflate(inflateur, parent, false),
                )
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val rangee = getItem(position)) {
            is RangeeConsole.EtapeArbre -> (holder as EtapeArbreHolder).lier(rangee)
            is RangeeConsole.DetailTelechargement -> (holder as DetailTelechargementHolder).lier(rangee)
            is RangeeConsole.Ligne -> (holder as LigneHolder).lier(rangee.ligne)
            is RangeeConsole.SyntheseBuild -> (holder as SyntheseHolder).lier(rangee)
        }
    }

    // ---- Étape d'arbre (§3.3) -------------------------------------------

    /** Étape : marqueur d'état (✓ / spinner / ○), libellé, durée. */
    private class EtapeArbreHolder(
        private val liaison: LigneArbreEtapeBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(rangee: RangeeConsole.EtapeArbre) {
            val contexte = liaison.root.context
            liaison.libelleEtapeArbre.text = contexte.getString(LibellesEtapesSync.libelle(rangee.phase))
            val annoncee = rangee.annoncee
            val terminee = annoncee?.terminee == true
            liaison.marqueurEtapeTerminee.isVisible = terminee
            liaison.marqueurEtapeEnCours.isVisible = annoncee != null && !terminee
            liaison.marqueurEtapeAttente.isVisible = annoncee == null
            if (liaison.marqueurEtapeEnCours.isVisible) {
                liaison.marqueurEtapeEnCours.setIndicatorColor(
                    ThemeHarmonizer.harmoniserAvecPrimaire(
                        contexte,
                        CanalTooling.SYNC.couleur,
                    ),
                )
            }
            // Durée : MESURÉE à la fin de la phase, jamais devinée en
            // cours (l'étape active ne montre rien — règle 9).
            liaison.dureeEtapeArbre.text =
                annoncee
                    ?.takeIf { it.terminee }
                    ?.let { DureesLisibles.formater(it.dureeMs) }
                    ?: ""
        }
    }

    // ---- Détail de téléchargement (§3.3) ---------------------------------

    /** Détail sous l'étape active : barre + « reçus / total · n/N » +
     *  artefact courant. */
    private class DetailTelechargementHolder(
        private val liaison: LigneDetailTelechargementBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(rangee: RangeeConsole.DetailTelechargement) {
            if (!lierBarre(rangee)) {
                // Aucune progression à montrer : le détail entier se tait
                // (l'étape reste seule en scène).
                liaison.detailTelechargementEtape.isVisible = false
                liaison.elementTelechargementEtape.isVisible = false
                return
            }
            lierTexte(rangee)
            liaison.elementTelechargementEtape.isVisible = rangee.element != null
            rangee.element?.let { element ->
                liaison.elementTelechargementEtape.text =
                    liaison.root.context.getString(R.string.editor_console_detail_element, element)
            }
        }

        /** Barre : déterminée quand le total est connu, indéterminée sinon
         *  (Material exige de masquer pour basculer de mode) — `false`
         *  quand il n'y a RIEN à montrer. */
        private fun lierBarre(rangee: RangeeConsole.DetailTelechargement): Boolean {
            val barre = liaison.barreTelechargementEtape
            barre.isVisible = false
            val total = rangee.octetsTotal
            when {
                total != null && total > 0 && rangee.octetsRecus > 0 -> {
                    if (barre.isIndeterminate) {
                        barre.isIndeterminate = false
                    }
                    barre.setProgressCompat(
                        (rangee.octetsRecus.toFloat() / total * POURCENT_MAX).toInt(),
                        true,
                    )
                }

                rangee.octetsRecus > 0 || rangee.compteur != null -> {
                    barre.isIndeterminate = true
                }

                else -> {
                    return false
                }
            }
            barre.setIndicatorColor(
                ThemeHarmonizer.harmoniserAvecPrimaire(
                    liaison.root.context,
                    CanalTooling.SYNC.couleur,
                ),
            )
            barre.isVisible = true
            return true
        }

        /** Texte de détail : « 42 Mo / 130 Mo », « 3 / 37 » (les deux
         *  ensemble le cas échéant, séparés par « · »). */
        private fun lierTexte(rangee: RangeeConsole.DetailTelechargement) {
            val contexte = liaison.root.context
            val parties = mutableListOf<String>()
            if (rangee.octetsRecus > 0) {
                parties +=
                    contexte.getString(
                        R.string.editor_console_detail_octets,
                        OctetsLisibles.formater(rangee.octetsRecus),
                        OctetsLisibles.formater(rangee.octetsTotal ?: rangee.octetsRecus),
                    )
            }
            if (rangee.compteur != null && (rangee.total ?: 0) > 0) {
                parties +=
                    contexte.getString(
                        R.string.editor_console_detail_compteur,
                        rangee.compteur,
                        rangee.total ?: rangee.compteur,
                    )
            }
            liaison.detailTelechargementEtape.isVisible = parties.isNotEmpty()
            if (parties.isNotEmpty()) {
                liaison.detailTelechargementEtape.text = parties.joinToString(" · ")
            }
        }
    }

    // ---- Synthèse finale (§3.3) ------------------------------------------

    /** Synthèse de fin de build : verdict + durée (ou message d'échec). */
    private class SyntheseHolder(
        private val liaison: LigneSyntheseBuildBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(rangee: RangeeConsole.SyntheseBuild) {
            val contexte = liaison.root.context
            when (rangee.statut) {
                StatutBuild.REUSSI -> {
                    liaison.marqueurSynthese.setImageResource(jo.codeide.core.ui.R.drawable.ic_fait)
                    liaison.marqueurSynthese.setColorFilter(
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_succes),
                    )
                    liaison.texteSyntheseBuild.text =
                        contexte.getString(
                            R.string.editor_sortie_build_reussi,
                            DureesLisibles.formater(rangee.dureeMs ?: 0L),
                        )
                    liaison.texteSyntheseBuild.setTextColor(
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout),
                    )
                }

                StatutBuild.ECHOUE -> {
                    liaison.marqueurSynthese.setImageResource(jo.codeide.core.ui.R.drawable.ic_fermer_onglet)
                    liaison.marqueurSynthese.setColorFilter(
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stderr),
                    )
                    liaison.texteSyntheseBuild.text =
                        rangee.message
                            ?: contexte.getString(R.string.editor_console_echec_defaut)
                    liaison.texteSyntheseBuild.setTextColor(
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stderr),
                    )
                }

                StatutBuild.ANNULE -> {
                    liaison.marqueurSynthese.setImageResource(jo.codeide.core.ui.R.drawable.ic_fermer_onglet)
                    liaison.marqueurSynthese.setColorFilter(
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout).apaiser(),
                    )
                    liaison.texteSyntheseBuild.text =
                        contexte.getString(R.string.editor_console_synthese_annule)
                    liaison.texteSyntheseBuild.setTextColor(
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout),
                    )
                }

                StatutBuild.EN_COURS -> {
                    // Jamais émis par le constructeur de rangées (la
                    // synthèse n'existe qu'une fois le build terminé) —
                    // branche défensive, une ligne vide ne ment pas.
                    liaison.texteSyntheseBuild.text = ""
                }
            }
        }

        /** Atténue une couleur vers le fond. */
        private fun Int.apaiser(): Int =
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                this,
                ALPHA_ATTENUE,
            )
    }

    // ---- Ligne chronologique (v3) ----------------------------------------

    /** Une ligne : étiquette de canal + texte monospace, couleur selon le
     *  genre de la ligne, couleur du canal sur l'étiquette. */
    private class LigneHolder(
        private val liaison: LigneSortieBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(ligne: LigneConsole) {
            liaison.texteSortie.text = texte(ligne)
            liaison.texteSortie.setTextColor(couleur(ligne))
            liaison.canalSortie.text = liaison.root.context.getString(ligne.canal.libelle)
            liaison.canalSortie.setTextColor(
                ThemeHarmonizer.harmoniserAvecPrimaire(liaison.root.context, ligne.canal.couleur),
            )
        }

        /** Texte affiché : brut pour une sortie, LOCALISÉ pour une tâche
         *  ou une étape (l'état reste pur — la chaîne est construite ICI). */
        private fun texte(ligne: LigneConsole): String =
            when (ligne) {
                is LigneConsole.Sortie -> ligne.texte
                is LigneConsole.Tache -> texteTache(ligne.etat)
                is LigneConsole.Etape -> texteEtape(ligne.etat)
            }

        /** Libellé d'une tâche selon son statut (durée au-delà d'une seconde,
         *  comme la vue Build d'Android Studio). */
        private fun texteTache(etat: EtatTacheAffichee): String {
            val contexte = liaison.root.context
            return when (etat.statut) {
                StatutTache.EN_COURS -> {
                    contexte.getString(R.string.editor_console_tache_en_cours, etat.chemin)
                }

                StatutTache.REUSSIE -> {
                    if (DureesLisibles.digne(etat.dureeMs)) {
                        contexte.getString(
                            R.string.editor_console_tache_duree,
                            etat.chemin,
                            DureesLisibles.formater(etat.dureeMs ?: 0),
                        )
                    } else {
                        contexte.getString(R.string.editor_console_tache, etat.chemin)
                    }
                }

                StatutTache.SAUTEE -> {
                    contexte.getString(R.string.editor_console_tache_sautee, etat.chemin)
                }

                StatutTache.ECHOUEE -> {
                    contexte.getString(R.string.editor_console_tache_echouee, etat.chemin)
                }
            }
        }

        /** Libellé d'une étape de sync — conclue avec sa durée. */
        private fun texteEtape(etat: EtapeSyncAffichee): String {
            val contexte = liaison.root.context
            val libelle = contexte.getString(LibellesEtapesSync.libelle(etat.etape))
            return if (etat.terminee) {
                contexte.getString(
                    R.string.editor_console_etape_terminee,
                    libelle,
                    DureesLisibles.formater(etat.dureeMs),
                )
            } else {
                libelle
            }
        }

        /** Couleur du texte selon le genre : la tâche en cours porte la
         *  couleur de SON canal (le « qui parle » en direct), l'échec reste
         *  rouge, le sauté et l'étape conclue se reposent en arrière-plan. */
        private fun couleur(ligne: LigneConsole): Int {
            val contexte = liaison.root.context
            return when (ligne) {
                is LigneConsole.Sortie -> {
                    if (ligne.apaisee || ligne.flux == jo.codeide.core.domain.FluxSortieBuild.STDOUT) {
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout)
                    } else {
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stderr)
                    }
                }

                is LigneConsole.Tache -> {
                    when (ligne.etat.statut) {
                        StatutTache.EN_COURS -> {
                            ThemeHarmonizer.harmoniserAvecPrimaire(contexte, ligne.canal.couleur)
                        }

                        StatutTache.REUSSIE -> {
                            ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout)
                        }

                        StatutTache.SAUTEE -> {
                            ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout).apaiser()
                        }

                        StatutTache.ECHOUEE -> {
                            ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stderr)
                        }
                    }
                }

                is LigneConsole.Etape -> {
                    if (ligne.etat.terminee) {
                        ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_stdout)
                    } else {
                        ThemeHarmonizer.harmoniserAvecPrimaire(contexte, ligne.canal.couleur)
                    }
                }
            }
        }

        /** Atténue une couleur vers le fond (tâche sautée : de l'information,
         *  pas du bruit). */
        private fun Int.apaiser(): Int =
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                this,
                ALPHA_ATTENUE,
            )
    }

    private object DiffRangeesConsole : DiffUtil.ItemCallback<RangeeConsole>() {
        override fun areItemsTheSame(
            ancienne: RangeeConsole,
            nouvelle: RangeeConsole,
        ): Boolean = ancienne.idCle == nouvelle.idCle

        override fun areContentsTheSame(
            ancienne: RangeeConsole,
            nouvelle: RangeeConsole,
        ): Boolean = ancienne == nouvelle
    }
}
