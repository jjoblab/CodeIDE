package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.LogDiskUsage
import jo.codeide.core.model.AppError
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity

/**
 * État observable de l'onglet Journaux (lot L4, ADR 0162) — le chrome des
 * ACTIONS uniquement : l'AFFICHAGE (fenêtre, recherche n/N insensible aux
 * accents, suivi, état vide) vit dans le lecteur du Centre de sorties
 * (famille JOURNAL) consommé par l'écran — jamais un second afficheur.
 *
 * @property filtresNiveaux niveaux retenus (chips, persistés) — vide =
 * tous les niveaux ; poussé au lecteur comme un `FiltreSorties`.
 * @property verbosite réglage utilisateur courant.
 * @property usageDisque occupation des fichiers de journal, ou `null` si non
 * encore mesurée.
 * @property erreur dernière erreur d'opération typée (effacement, export,
 * verbosité), ou `null`. La lecture d'historique en échec est annoncée par
 * le bandeau du fragment (source du lecteur, [CollecteurSortiesJournal]).
 */
data class EtatJournal(
    val filtresNiveaux: Set<LogLevel> = emptySet(),
    val verbosite: LogVerbosity = LogVerbosity.NORMAL,
    val usageDisque: LogDiskUsage? = null,
    val erreur: AppError? = null,
)

/** Actions intentionnelles de l'onglet Journaux (les gestes d'AFFICHAGE
 *  — recherche, pause, effacement du tampon — sont ceux du lecteur). */
sealed interface ActionJournal {
    /** Bascule un niveau de filtre (vide = tous les niveaux affichés). */
    data class BasculerNiveau(val niveau: LogLevel) : ActionJournal

    /** Efface tous les journaux (après confirmation côté UI) — le tampon
     *  du lecteur est vidé par le fragment à l'effet [EffetJournal.Efface]. */
    data object Effacer : ActionJournal

    /** Produit l'archive de journaux pour le partage. */
    data object Partager : ActionJournal

    /** Écrit l'archive à la destination choisie par le sélecteur SAF. */
    data class Enregistrer(val destinationUri: String) : ActionJournal

    /** Change la verbosité de journalisation (Normal / Détaillé). */
    data class ChoisirVerbosite(val verbosite: LogVerbosity) : ActionJournal
}

/** Effets ponctuels de l'onglet Journaux. */
sealed interface EffetJournal {
    /** L'archive de partage est prête — la feuille de partage doit s'ouvrir. */
    data class ArchivePrete(val archive: ExportedLogs) : EffetJournal

    /** Les journaux viennent d'être effacés — le tampon du lecteur est
     *  vidé (même geste honnête que « effacer » du lecteur lui-même). */
    data object Efface : EffetJournal
}
