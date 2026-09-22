package jo.codeide.core.logging

/**
 * Bornes et constantes du pipeline de journalisation (section 5.7 du prompt
 * maître). Centralisées ici pour rester nommées, testées et documentées —
 * jamais de nombre magique dans le pipeline.
 */
internal object LoggingLimits {
    /** Taille maximale du message d'une entrée, en octets UTF-8. */
    const val MESSAGE_MAX_BYTES = 4 * 1024

    /** Nombre maximal de tranches de pile par exception journalisée. */
    const val EXCEPTION_MAX_FRAMES = 50

    /** Nombre maximal d'exceptions enchaînées conservées. */
    const val EXCEPTION_MAX_CAUSES = 10

    /** Capacité du tampon circulaire de breadcrumbs (entrées récentes). */
    const val BREADCRUMB_CAPACITY = 200

    /** Capacité de la file d'attente d'écriture disque (DROP_OLDEST au-delà). */
    const val CHANNEL_CAPACITY = 512

    /** Fenêtre d'écriture groupée, en millisecondes. */
    const val BATCH_WINDOW_MS = 500L

    /** Nombre d'archives d'export conservées dans le cache. */
    const val MAX_EXPORTS_KEPT = 5

    /** Nombre de millisecondes par jour (rétention des archives). */
    const val MILLIS_PER_DAY: Long = 86_400_000L

    /** Nom du fichier courant de journal (JSON Lines). */
    const val CURRENT_FILE_NAME = "current.jsonl"

    /** Préfixe des noms d'archives : `archive-1.jsonl` est la plus récente. */
    const val ARCHIVE_FILE_PREFIX = "archive-"

    /** Répertoire des journaux, sous `filesDir`. */
    const val LOGS_DIR_NAME = "logs"

    /** Répertoire des exports, sous `cacheDir`. */
    const val EXPORTS_DIR_NAME = "exports"

    /** Entrée zip : les journaux. */
    const val ZIP_ENTRY_LOGS = "logs.jsonl"

    /** Entrée zip : les informations d'appareil. */
    const val ZIP_ENTRY_DEVICE_INFO = "device-info.txt"

    /** Motif du nom des archives d'export : `codeide-logs-<date>.zip`. */
    const val EXPORT_FILE_PREFIX = "codeide-logs-"

    /** Format d'horodatage des noms d'export (stable, ASCII). */
    const val EXPORT_TIMESTAMP_FORMAT = "yyyy-MM-dd-HHmmss"
}
