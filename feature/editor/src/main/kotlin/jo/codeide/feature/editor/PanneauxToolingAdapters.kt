package jo.codeide.feature.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.GroupeProblemesBinding
import jo.codeide.feature.editor.databinding.LigneProblemeBinding
import jo.codeide.feature.editor.databinding.LigneSortieBinding
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
 * Adaptateur de l'onglet Sortie (G5, §6 ; v0.32.5 : lignes CANALISÉES,
 * ADR 0056 décision 5 ; v3 : lignes TYPIÉES) : étiquette de canal en tête
 * de ligne — couleur signature Sync/Build — puis texte monospace. Le genre
 * de la ligne pilote le texte et la couleur : sortie brute (stderr distincte
 * par la couleur d'erreur, avertissement bénin apaisé — C5), tâche au fil
 * du build (en cours : couleur du canal ; fin : durée, sautée grisée, échec
 * rouge — mise à jour EN PLACE, une ligne par tâche comme la vue Build
 * d'Android Studio), étape de sync conclue avec sa durée.
 */
internal class SortieAdapter : ListAdapter<LigneConsole, SortieAdapter.Holder>(DiffLignes) {
    private companion object {
        /** Alpha d'une tâche sautée (atténuée vers le fond). */
        private const val ALPHA_ATTENUE = 128
    }

    /** Une ligne : étiquette de canal + texte monospace, couleur selon le
     *  genre de la ligne, couleur du canal sur l'étiquette. */
    inner class Holder(
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
            val libelle = contexte.getString(libelleEtape(etat.etape))
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

        /** Ressource du libellé d'une phase de sync. */
        private fun libelleEtape(etape: EtapeSync): Int =
            when (etape) {
                EtapeSync.CONNEXION -> R.string.editor_console_etape_connexion
                EtapeSync.MODELE_GRADLE -> R.string.editor_console_etape_modele_gradle
                EtapeSync.MODELE_IDEA -> R.string.editor_console_etape_modele_idea
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

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(LigneSortieBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        holder.lier(getItem(position))
    }

    private object DiffLignes : DiffUtil.ItemCallback<LigneConsole>() {
        override fun areItemsTheSame(
            ancienne: LigneConsole,
            nouvelle: LigneConsole,
        ): Boolean = ancienne.id == nouvelle.id

        override fun areContentsTheSame(
            ancienne: LigneConsole,
            nouvelle: LigneConsole,
        ): Boolean = ancienne == nouvelle
    }
}
