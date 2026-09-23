package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.LogDiskUsage
import jo.codeide.core.model.AppError
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity

/**
 * État observable de l'onglet Journaux (étape 12).
 *
 * [entrees] est la **fenêtre affichée** — filtrée par niveaux et recherche,
 * bornée aux plus récentes des entrées connues : les entrées plus anciennes
 * sont révélées par paliers au défilement ([plusAnciennesDisponibles]
 * l'annonce), la fusion temps réel du tampon mémoire ne recharge jamais tout.
 *
 * @property chargement lecture initiale de l'historique en cours.
 * @property entrees entrées affichées, chronologiques (la plus récente en bas).
 * @property plusAnciennesDisponibles `true` si des entrées filtrées plus
 * anciennes restent masquées (le défilement peut en révéler).
 * @property recherche texte recherché (casse ignorée).
 * @property filtresNiveaux niveaux retenus — vide = tous les niveaux.
 * @property suivreDirect la visionneuse suit les nouvelles entrées en direct.
 * @property verbosite réglage utilisateur courant.
 * @property usageDisque occupation des fichiers de journal, ou `null` si non
 * encore mesurée.
 * @property erreur dernière erreur d'opération typée, ou `null`.
 */
data class EtatJournal(
    val chargement: Boolean = true,
    val entrees: List<LogEntry> = emptyList(),
    val plusAnciennesDisponibles: Boolean = false,
    val recherche: String = "",
    val filtresNiveaux: Set<LogLevel> = emptySet(),
    val suivreDirect: Boolean = false,
    val verbosite: LogVerbosity = LogVerbosity.NORMAL,
    val usageDisque: LogDiskUsage? = null,
    val erreur: AppError? = null,
)

/** Actions intentionnelles de l'onglet Journaux. */
sealed interface ActionJournal {
    /** Change le texte recherché (fusionné par un délai côté ViewModel). */
    data class Rechercher(
        val texte: String,
    ) : ActionJournal

    /** Bascule un niveau de filtre (vide = tous les niveaux affichés). */
    data class BasculerNiveau(
        val niveau: LogLevel,
    ) : ActionJournal

    /** Bascule le suivi en direct des nouvelles entrées. */
    data object BasculerSuiviDirect : ActionJournal

    /** Révèle le palier suivant d'entrées plus anciennes. */
    data object ChargerPlusAnciennes : ActionJournal

    /** Efface tous les journaux (après confirmation côté UI). */
    data object Effacer : ActionJournal

    /** Produit l'archive de journaux pour le partage. */
    data object Partager : ActionJournal

    /** Écrit l'archive à la destination choisie par le sélecteur SAF. */
    data class Enregistrer(
        val destinationUri: String,
    ) : ActionJournal

    /** Change la verbosité de journalisation (Normal / Détaillé). */
    data class ChoisirVerbosite(
        val verbosite: LogVerbosity,
    ) : ActionJournal
}

/** Effets ponctuels de l'onglet Journaux. */
sealed interface EffetJournal {
    /** L'archive de partage est prête — la feuille de partage doit s'ouvrir. */
    data class ArchivePrete(
        val archive: ExportedLogs,
    ) : EffetJournal

    /** Le suivi direct vient d'être activé — défiler vers l'entrée la plus récente. */
    data object DefilerVersBas : EffetJournal
}
