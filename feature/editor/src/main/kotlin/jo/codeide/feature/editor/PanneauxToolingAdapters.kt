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
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.feature.editor.databinding.GroupeProblemesBinding
import jo.codeide.feature.editor.databinding.LigneArbreEtapeBinding
import jo.codeide.feature.editor.databinding.LigneClasspathModuleBinding
import jo.codeide.feature.editor.databinding.LigneDetailTelechargementBinding
import jo.codeide.feature.editor.databinding.LigneProblemeBinding
import jo.codeide.feature.editor.databinding.LigneSyntheseBuildBinding
import jo.codeide.feature.editor.databinding.LigneTacheConsoleBinding
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

    /** Fabrique des rangées (arbre / détail / classpath / tâche / synthèses). */
    private enum class Type {
        ETAPE_ARBRE,
        DETAIL_TELECHARGEMENT,
        DETAIL_CLASSPATH,
        TACHE,
        SYNTHESE_BUILD,
        SYNTHESE_SYNC,
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is RangeeConsole.EtapeArbre -> Type.ETAPE_ARBRE
            is RangeeConsole.DetailTelechargement -> Type.DETAIL_TELECHARGEMENT
            is RangeeConsole.DetailClasspath -> Type.DETAIL_CLASSPATH
            is RangeeConsole.Tache -> Type.TACHE
            is RangeeConsole.SyntheseBuild -> Type.SYNTHESE_BUILD
            is RangeeConsole.SyntheseSync -> Type.SYNTHESE_SYNC
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

            Type.DETAIL_CLASSPATH.ordinal -> {
                ClasspathModuleHolder(
                    LigneClasspathModuleBinding.inflate(inflateur, parent, false),
                )
            }

            Type.SYNTHESE_BUILD.ordinal -> {
                SyntheseHolder(
                    LigneSyntheseBuildBinding.inflate(inflateur, parent, false),
                )
            }

            Type.SYNTHESE_SYNC.ordinal -> {
                SyntheseHolder(
                    LigneSyntheseBuildBinding.inflate(inflateur, parent, false),
                )
            }

            else -> {
                TacheHolder(
                    LigneTacheConsoleBinding.inflate(inflateur, parent, false),
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
            is RangeeConsole.DetailClasspath -> (holder as ClasspathModuleHolder).lier(rangee)
            is RangeeConsole.Tache -> (holder as TacheHolder).lier(rangee.ligne)
            is RangeeConsole.SyntheseBuild -> (holder as SyntheseHolder).lier(rangee)
            is RangeeConsole.SyntheseSync -> (holder as SyntheseHolder).lier(rangee)
        }
    }

    /**
     * Variante à payloads (v0.40.1, correctif n°5 du prompt de suivi) :
     * seuls les champs qui ont changé sont rebondis — pas de rebind
     * complet, pas d'animation de changement (clignotement). Pour une
     * étape d'arbre : mise à jour du `marqueur` (✓ / anneau / ○) et de
     * la durée. Pour une tâche : mise à jour du statut et de la durée.
     *
     * Si la liste de payloads est VIDE (cas DiffUtil), on retombe sur le
     * `onBindViewHolder` complet — c'est le contrat Android.
     */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        when (val rangee = getItem(position)) {
            is RangeeConsole.EtapeArbre -> {
                val nouvelEtat = payloads.firstOrNull() as? EtatEtapeArbre
                if (nouvelEtat != null) {
                    (holder as EtapeArbreHolder).lierMajEtat(rangee, nouvelEtat)
                } else {
                    (holder as EtapeArbreHolder).lier(rangee)
                }
            }

            is RangeeConsole.Tache -> {
                val nouvelEtat = payloads.firstOrNull() as? EtatTacheAffichee
                if (nouvelEtat != null) {
                    (holder as TacheHolder).lierMajEtat(nouvelEtat)
                } else {
                    (holder as TacheHolder).lier(rangee.ligne)
                }
            }

            else -> {
                onBindViewHolder(holder, position)
            }
        }
    }

    // ---- Étape d'arbre (§3.3 ; v5 — plan de l'aperçu ; v0.40.1 — correctif
    //      n°5 du prompt de suivi : le marqueur en cours est un
    //      `AnneauTournant` (drawable vectoriel + ObjectAnimator partagé)
    //      au lieu d'un `CircularProgressIndicator` Material ; v6 — prompt
    //      de suivi §2 : suppression du concept « sautée / En cache »,
    //      une étape qui n'a pas lieu n'est plus affichée du tout) ---------

    /** Étape : marqueur d'état (✓ / anneau tournant / ○), libellé, durée
     *  MESURÉE à droite (jamais devinée — l'étape en cours n'en montre pas).
     *  v0.40.1 : l'anneau en cours est un `AnneauTournant` — un seul
     *  animateur partagé entre les holders visibles, plus de
     *  redémarrage d'animation à chaque `lier()`.
     *  v6 : plus de marqueur « sautée » ni de libellé « En cache » — une
     *  étape non concernée n'apparaît pas dans la liste. */
    private class EtapeArbreHolder(
        private val liaison: LigneArbreEtapeBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(rangee: RangeeConsole.EtapeArbre) {
            val contexte = liaison.root.context
            liaison.libelleEtapeArbre.text = contexte.getString(LibellesEtapesSync.libelle(rangee.etape))
            val etat = rangee.etat
            majMarqueur(etat, contexte)
            majDuree(etat)
        }

        /**
         * Mise à jour EN PLACE (v0.40.1, correctif n°5) : seuls le
         * marqueur et la durée changent entre deux versions d'une même
         * étape d'arbre — le libellé ne change pas. Pas de rebind complet,
         * pas d'animation de changement (clignotement), pas de
         * redémarrage de l'anneau tournant (il reste attaché à la fenêtre).
         *
         * @param rangee la rangée courante (non utilisisée — seul le
         *        nouvel état est appliqué ; le paramètre est conservé
         *        pour la signature explicite du contrat `payloads`).
         * @param nouvelEtat l'état consolidé de l'étape (statut/durée).
         */
        @Suppress("UnusedParameter")
        fun lierMajEtat(
            rangee: RangeeConsole.EtapeArbre,
            nouvelEtat: EtatEtapeArbre?,
        ) {
            val contexte = liaison.root.context
            majMarqueur(nouvelEtat, contexte)
            majDuree(nouvelEtat)
        }

        /** Met à jour le marqueur d'état (✓ / anneau / ○) selon [etat].
         *  v6 : plus de marqueur « sautée » — trois états seulement. */
        private fun majMarqueur(
            etat: EtatEtapeArbre?,
            contexte: Context,
        ) {
            val terminee = etat?.terminee == true
            liaison.marqueurEtapeTerminee.isVisible = terminee
            liaison.marqueurEtapeEnCours.isVisible = etat != null && !terminee
            liaison.marqueurEtapeAttente.isVisible = etat == null
            if (liaison.marqueurEtapeEnCours.isVisible) {
                // v0.40.1 : `backgroundTintList` teinte le drawable vectoriel
                // de l'anneau — un seul `ObjectAnimator` partagé tourne
                // toutes les `AnneauTournant` visibles, le `lier()` ne
                // redémarre plus l'animation à chaque tick de durée.
                liaison.marqueurEtapeEnCours.backgroundTintList =
                    ColorStateList.valueOf(
                        ThemeHarmonizer.harmoniserAvecPrimaire(
                            contexte,
                            CanalTooling.SYNC.couleur,
                        ),
                    )
            }
        }

        /** Met à jour la durée (MESURÉE à la fin, jamais devinée en cours).
         *  v6 : plus de texte « En cache » — une étape sans travail n'est
         *  simplement pas affichée. */
        private fun majDuree(etat: EtatEtapeArbre?) {
            liaison.dureeEtapeArbre.text =
                if (etat?.terminee == true) DureesLisibles.formater(etat.dureeMs) else ""
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

    /** Synthèse de fin de build : verdict + durée (ou message d'échec) +
     *  compte des tâches actionnables (v0.39.1, correctif n°4 — style
     *  Android Studio : « N actionable tasks: M executed[, K up-to-date] »). */
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
            // v0.39.1 (correctif n°4) : compte des tâches actionnables,
            // restitué sous le verdict comme la console d'Android Studio.
            // Masqué tant que la synthèse de Gradle n'est pas arrivée
            // (build échoué avant la fin, sortie non détectable, build
            // annulé). Les comptes viennent du serveur (extraction regex
            // de la dernière ligne stdout) — pas de re-parse côté UI.
            rendreCompteTaches(rangee)
        }

        /**
         * Affiche la ligne « N actionable tasks: M executed[, K up-to-date] »
         * sous le verdict (v0.39.1, correctif n°4) — masquée tant que les
         * comptes sont absents. Distinction incrémental vs non-incrémental
         * via la présence du compte « up-to-date ».
         */
        private fun rendreCompteTaches(rangee: RangeeConsole.SyntheseBuild) {
            val contexte = liaison.root.context
            val actionable = rangee.tachesActionnables
            val executees = rangee.tachesExecutees
            val aJour = rangee.tachesAJour
            if (actionable == null || executees == null) {
                liaison.texteSyntheseTaches.isVisible = false
                liaison.texteSyntheseTaches.text = ""
                return
            }
            liaison.texteSyntheseTaches.text =
                if (aJour != null) {
                    contexte.getString(
                        R.string.editor_console_synthese_taches_ajour,
                        actionable,
                        executees,
                        aJour,
                    )
                } else {
                    contexte.getString(
                        R.string.editor_console_synthese_taches_executees,
                        actionable,
                        executees,
                    )
                }
            liaison.texteSyntheseTaches.isVisible = true
        }

        /** Pied de conclusion de la sync (v5, aperçu) : coche verte +
         *  « Synchronisation terminée » ou « Projet à jour, rien à
         *  télécharger — les tâches sont disponibles ». */
        fun lier(rangee: RangeeConsole.SyntheseSync) {
            val contexte = liaison.root.context
            liaison.marqueurSynthese.setImageResource(jo.codeide.core.ui.R.drawable.ic_fait)
            liaison.marqueurSynthese.setColorFilter(
                ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_succes),
            )
            liaison.texteSyntheseBuild.text =
                contexte.getString(
                    if (rangee.aJour) {
                        R.string.editor_console_synthese_sync_a_jour
                    } else {
                        R.string.editor_console_synthese_sync_terminee
                    },
                )
            liaison.texteSyntheseBuild.setTextColor(
                ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_succes),
            )
            // v0.40.1 (prompt de suivi §4) : récapitulatif classpath en
            // sous-ligne. Affiché SEULEMENT si les stats sont disponibles
            // (sync réussie avec classpath résolu). Sinon masqué.
            val stats = formatRecapClasspath(contexte, rangee)
            if (stats == null) {
                liaison.texteSyntheseTaches.isVisible = false
                liaison.texteSyntheseTaches.text = ""
            } else {
                liaison.texteSyntheseTaches.text = stats
                liaison.texteSyntheseTaches.setTextColor(
                    ContextCompat.getColor(contexte, jo.codeide.core.ui.R.color.codeide_succes),
                )
                liaison.texteSyntheseTaches.isVisible = true
            }
        }

        /**
         * Formate le récapitulatif classpath (v0.40.1, prompt de suivi §4)
         * — « 3 modules · 312 jars · 4 sources · 12 AARs ». `null` si les
         * stats ne sont pas disponibles (classpath non résolu).
         */
        private fun formatRecapClasspath(
            contexte: Context,
            rangee: RangeeConsole.SyntheseSync,
        ): String? {
            val nbModules = rangee.nbModules ?: return null
            val parties = mutableListOf("$nbModules ${contexte.getString(R.string.editor_console_stats_modules)}")
            rangee.nbJars?.let {
                if (it > 0) parties += "$it ${contexte.getString(R.string.editor_console_stats_jars)}"
            }
            rangee.nbAars?.let {
                if (it > 0) parties += "$it ${contexte.getString(R.string.editor_console_stats_aars)}"
            }
            rangee.nbSources?.let {
                if (it > 0) parties += "$it ${contexte.getString(R.string.editor_console_stats_sources)}"
            }
            return parties.joinToString(" · ")
        }

        /** Atténue une couleur vers le fond. */
        private fun Int.apaiser(): Int =
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                this,
                ALPHA_ATTENUE,
            )
    }

    // ---- Détail classpath par module (v0.40.1 §4) -----------------------

    /** Sous-ligne classpath d'un module : « :app · 312 jars · 4 sources ·
     *  variante debug » en monospace atténué, indenté à 28 dp. */
    private class ClasspathModuleHolder(
        private val liaison: LigneClasspathModuleBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(rangee: RangeeConsole.DetailClasspath) {
            val parties = mutableListOf(rangee.nomModule)
            rangee.nbJars?.let { if (it > 0) parties += "$it jars" }
            rangee.nbAars?.let { if (it > 0) parties += "$it AARs" }
            rangee.nbSources?.let { if (it > 0) parties += "$it sources" }
            rangee.varianteAndroid?.let { parties += "variante $it" }
            rangee.nbDependancesProjet?.let { if (it > 0) parties += "$it deps" }
            liaison.texteClasspathModule.text = parties.joinToString(" · ")
        }
    }

    // ---- Tâche du build (v3 ; v5 — SEULE ligne de la vue Build) ---------

    /** Une tâche : texte LOCALISÉ selon son statut (durée au terme), la
     *  couleur suit le statut — l'étiquette de canal a disparu avec la
     *  chronologie brute (v5, aperçu : le chip Build dit déjà qui parle). */
    private class TacheHolder(
        private val liaison: LigneTacheConsoleBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        fun lier(ligne: LigneConsole.Tache) {
            liaison.texteTache.text = texte(ligne.etat)
            liaison.texteTache.setTextColor(couleur(ligne))
        }

        /**
         * Mise à jour EN PLACE (v0.40.1, correctif n°5) : seul le texte de
         * la tâche (statut + durée) change — pas de rebind complet, pas
         * d'animation de changement. L'identité de la tâche (chemin) ne
         * change jamais après création de la rangée.
         */
        fun lierMajEtat(nouvelEtat: EtatTacheAffichee) {
            liaison.texteTache.text = texte(nouvelEtat)
            // La couleur suit le statut — on la recalcule depuis un faux
            // `LigneConsole.Tache` portant le nouvel état (les champs
            // `id` et `canal` ne servent pas à `couleur()`).
            liaison.texteTache.setTextColor(
                couleur(
                    LigneConsole.Tache(
                        id = 0L,
                        canal = CanalTooling.BUILD,
                        etat = nouvelEtat,
                    ),
                ),
            )
        }

        /** Libellé d'une tâche selon son statut (durée au-delà d'une seconde,
         *  comme la vue Build d'Android Studio) — l'état reste pur, la
         *  chaîne est construite ICI. */
        private fun texte(etat: EtatTacheAffichee): String {
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

        /** Couleur du texte : la tâche en cours porte la couleur de SON
         *  canal (le « qui parle » en direct), l'échec reste rouge, le
         *  sauté se repose en arrière-plan. */
        private fun couleur(ligne: LigneConsole.Tache): Int {
            val contexte = liaison.root.context
            return when (ligne.etat.statut) {
                StatutTache.EN_COURS -> {
                    ThemeHarmonizer.harmoniserAvecPrimaire(contexte, CanalTooling.BUILD.couleur)
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

        /**
         * Payloads granulaires (v0.40.1, correctif n°5 du prompt de suivi)
         * : seuls les champs qui CHANGENT entre deux versions d'une même
         * rangée sont renvoyés à `onBindViewHolder(holder, position,
         * payloads)` — le `RecyclerView` ne fait plus d'animation de
         * changement (clignotement) sur un simple tick de durée.
         *
         * Pour une étape d'arbre : `etat` (statut/durée). Pour une tâche :
         * `ligne.etat` (statut/durée). Pour les autres rangées (détail de
         * téléchargement, synthèse, pied), aucun payload — la rangée est
         * entièrement rebine.
         */
        override fun getChangePayload(
            ancienne: RangeeConsole,
            nouvelle: RangeeConsole,
        ): Any? {
            // Seules les rangées qui peuvent muter EN PLACE sans
            // déclencher d'animation de changement portent un payload.
            return when (nouvelle) {
                is RangeeConsole.EtapeArbre -> {
                    val ancienEtat = (ancienne as? RangeeConsole.EtapeArbre)?.etat
                    if (ancienEtat == nouvelle.etat) null else nouvelle.etat
                }

                is RangeeConsole.Tache -> {
                    val ancienEtat = (ancienne as? RangeeConsole.Tache)?.ligne?.etat
                    if (ancienEtat == nouvelle.ligne.etat) null else nouvelle.ligne.etat
                }

                else -> {
                    null
                }
            }
        }
    }
}
