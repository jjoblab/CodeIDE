package jo.codeide.core.domain

import jo.codeide.core.model.LogLevel

/**
 * Configuration du système de journalisation (section 5.7 du prompt maître).
 *
 * Les valeurs par défaut matérialisent les bornes du prompt : fichier
 * courant de 1 Mio, 5 archives au plus, rétention de 7 jours — soit un
 * plafond total d'environ 6 Mio sur disque.
 *
 * À l'étape 2, le niveau minimal est décidé par le type de build
 * ([debugDefault] / [releaseDefault]) ; à partir de l'étape 4, le réglage
 * utilisateur `AppSettings.logLevel` (`NORMAL` = INFO, `DETAILED` = DEBUG)
 * deviendra la source de vérité et mettra à jour cette configuration à
 * l'exécution.
 *
 * @property minLevel niveau minimal actif : les entrées moins sévères ne
 * sont ni évaluées ni écrites.
 * @property fileLoggingEnabled écriture disque activée — toujours `true`
 * dans le processus principal, coupée dans un éventuel processus `:crash`
 * (anti-corruption).
 * @property maxFileSizeBytes taille maximale du fichier courant avant
 * archivage (rotation).
 * @property maxArchiveFiles nombre maximal d'archives conservées.
 * @property retentionDays âge maximal des archives, en jours.
 */
public data class LogConfig(
    public val minLevel: LogLevel = LogLevel.INFO,
    public val fileLoggingEnabled: Boolean = true,
    public val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    public val maxArchiveFiles: Int = DEFAULT_MAX_ARCHIVE_FILES,
    public val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    init {
        require(maxFileSizeBytes > 0) { "La taille maximale de fichier doit être positive." }
        require(maxArchiveFiles > 0) { "Le nombre maximal d'archives doit être positif." }
        require(retentionDays > 0) { "La rétention en jours doit être positive." }
    }

    public companion object {
        /** 1 Mio : taille maximale du fichier courant de journal. */
        public const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 1_048_576L

        /** 5 archives maximum au-delà du fichier courant. */
        public const val DEFAULT_MAX_ARCHIVE_FILES: Int = 5

        /** 7 jours de rétention des archives. */
        public const val DEFAULT_RETENTION_DAYS: Int = 7

        /**
         * Configuration par défaut d'un build debug : tout passe, le niveau
         * minimal est [LogLevel.DEBUG].
         */
        public fun debugDefault(): LogConfig = LogConfig(minLevel = LogLevel.DEBUG)

        /**
         * Configuration par défaut d'un build release : niveau minimal
         * [LogLevel.INFO] (équivalent du futur réglage « NORMAL »).
         */
        public fun releaseDefault(): LogConfig = LogConfig(minLevel = LogLevel.INFO)
    }
}
