package jo.codeide.core.crash

/**
 * Bornes du système de gestion des plantages (section 5.8 du prompt
 * maître) — aucune valeur magique dispersée dans le code.
 */
internal object CrashLimits {
    /** Répertoire des rapports, sous `filesDir/`. */
    const val DIRECTORY_NAME = "crashes"

    /** Extension des fichiers de rapport. */
    const val REPORT_SUFFIX = ".json"

    /** Extension du fichier témoin d'état « consulté ». */
    const val REVIEWED_SUFFIX = ".reviewed"

    /** Taille maximale d'un rapport sur disque : 256 Ko. */
    const val MAX_REPORT_BYTES: Int = 256 * 1024

    /** Nombre maximal de rapports conservés (les plus récents). */
    const val MAX_REPORTS: Int = 20

    /** Filons de pain joints à un rapport (50 derniers). */
    const val MAX_BREADCRUMBS: Int = 50

    /** Tranches de pile conservées dans un rapport. */
    const val MAX_FRAMES: Int = 100

    /** Sauts de cause conservés dans un rapport. */
    const val MAX_CAUSES: Int = 10

    /** Taille maximale d'un message d'exception dans un rapport. */
    const val MAX_MESSAGE_BYTES: Int = 2_048

    /** Fenêtre de détection de boucle : 3 plantages en 60 s. */
    const val LOOP_WINDOW_MILLIS: Long = 60_000

    /** Seuil de détection de boucle. */
    const val LOOP_THRESHOLD: Int = 3

    /** Nombre d'horodatages de boucle conservés en historique. */
    const val LOOP_HISTORY_MAX: Int = 10

    /** Vidage du journal borné à 500 ms (section 5.8). */
    const val FLUSH_TIMEOUT_MILLIS: Long = 500

    /** Durée totale maximale du gestionnaire : 2 s (section 5.8). */
    const val HANDLER_BUDGET_MILLIS: Long = 2_000

    /** Nombre maximal de sorties de processus lues au démarrage. */
    const val EXIT_INFO_MAX: Int = 20

    /** Taille maximale de la trace d'une sortie de processus. */
    const val EXIT_INFO_TRACE_MAX_BYTES: Int = 8_192
}
