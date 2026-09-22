package jo.codeide.core.logging

import jo.codeide.core.model.LogEntry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Propriétaire unique du stockage disque des journaux : répertoire
 * `filesDir/logs/`, fichier courant `current.jsonl` au format JSON Lines,
 * archives `archive-1.jsonl` (la plus récente) à `archive-N.jsonl`.
 *
 * Toutes les opérations passent par un verrou unique : le sink fichier est
 * le seul consommateur en écriture, mais le dépôt lit, efface et balaie la
 * rétention depuis d'autres coroutines — le verrou garantit la cohérence
 * des rotations.
 *
 * Tolérance aux écritures interrompues (plantage brutal d'un précédent
 * lancement) : les lignes illisibles sont **comptées puis ignorées** à la
 * lecture — [skippedLines] et [lastReadError] restent consultables pour le
 * diagnostic ; on n'avale pas l'information, on la mesure.
 *
 * @param directory répertoire racine des journaux (créé à la demande).
 * @param json configureur de sérialisation des entrées.
 */
internal class JsonlLogStore(
    private val directory: File,
    private val json: Json,
) {
    /** Lignes illisibles ignorées depuis la dernière initialisation. */
    @Volatile
    var skippedLines: Int = 0
        private set

    /** Dernière erreur de lecture rencontrée (diagnostic, jamais affichée). */
    @Volatile
    var lastReadError: String? = null
        private set

    private val lock = Any()

    /** Nommage et énumération des fichiers du répertoire. */
    private val fichiers = FichiersJournal(directory)

    /**
     * Ajoute des lignes JSONL, avec rotation : le fichier courant passe en
     * archive dès que la taille maximale serait dépassée.
     *
     * Le flux de sortie reste ouvert sur l'ensemble du lot (et n'est rouvert
     * qu'aux rotations) : une ouverture par ligne transformerait chaque
     * écriture groupée en rafale de descripteurs et ralentirait le
     * consommateur au point de saturer le canal borné.
     *
     * @param lines lignes sérialisées (sans le retour à la ligne final).
     * @param maxFileSizeBytes taille maximale du fichier courant.
     * @param maxArchiveFiles nombre maximal d'archives conservées.
     * @throws IOException si l'écriture échoue (remontée au sink appelant).
     */
    fun appendLines(
        lines: List<String>,
        maxFileSizeBytes: Long,
        maxArchiveFiles: Int,
    ) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            directory.mkdirs()
            var courant = fichiers.currentFile()
            var taille = if (courant.exists()) courant.length() else 0L
            var flux: FileOutputStream? = null
            try {
                for (ligne in lines.map { (it + '\n').toByteArray(Charsets.UTF_8) }) {
                    if (taille > 0 && taille + ligne.size > maxFileSizeBytes) {
                        flux?.close()
                        flux = null
                        rotate(maxArchiveFiles)
                        courant = fichiers.currentFile()
                        taille = 0
                    }
                    if (flux == null) flux = FileOutputStream(courant, true)
                    flux.write(ligne)
                    taille += ligne.size
                }
            } finally {
                flux?.close()
            }
        }
    }

    /**
     * Lit toutes les entrées, de la plus ancienne à la plus récente
     * (archives puis fichier courant).
     *
     * @return les entrées lisibles ; les lignes corrompues sont comptées.
     */
    fun readAll(): List<LogEntry> =
        synchronized(lock) {
            if (!directory.exists()) return emptyList()
            fichiers.enOrdreChronologique().flatMap(::lireFichier)
        }

    /**
     * Mesure l'occupation disque.
     *
     * @return les octets cumulés et le nombre de fichiers de journal.
     */
    fun diskStats(): DiskStats =
        synchronized(lock) {
            val presents = fichiers.deJournal()
            DiskStats(bytes = presents.sumOf { it.length() }, fileCount = presents.size)
        }

    /** Supprime tous les fichiers de journal (le répertoire est conservé). */
    fun clear() {
        synchronized(lock) {
            fichiers.deJournal().forEach { it.delete() }
            skippedLines = 0
        }
    }

    /**
     * Applique la rétention : supprime les archives plus vieilles que
     * [retentionDays] jours, puis celles au-delà du plafond
     * [maxArchiveFiles]. Le fichier courant n'est jamais supprimé.
     *
     * @param nowMillis instant de référence (horloge injectée).
     * @param retentionDays rétention maximale, en jours.
     * @param maxArchiveFiles plafond du nombre d'archives.
     */
    fun sweep(
        nowMillis: Long,
        retentionDays: Int,
        maxArchiveFiles: Int,
    ) {
        synchronized(lock) {
            if (!directory.exists()) return
            val coupure = nowMillis - retentionDays * LoggingLimits.MILLIS_PER_DAY
            fichiers
                .indicesArchives()
                .filter { indice ->
                    val fichier = fichiers.archiveFile(indice)
                    fichier.lastModified() < coupure || indice > maxArchiveFiles
                }.forEach { indice -> fichiers.archiveFile(indice).delete() }
        }
    }

    /** Fait tourner les fichiers : current → archive-1, archives décalées. */
    private fun rotate(maxArchiveFiles: Int) {
        fichiers.archiveFile(maxArchiveFiles).delete()
        for (indice in maxArchiveFiles - 1 downTo 1) {
            val source = fichiers.archiveFile(indice)
            if (source.exists()) source.renameTo(fichiers.archiveFile(indice + 1))
        }
        fichiers.currentFile().renameTo(fichiers.archiveFile(1))
    }

    /** Analyse le contenu d'un fichier : une entrée JSON par ligne. */
    private fun lireFichier(file: File): List<LogEntry> =
        file
            .readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { ligne ->
                try {
                    json.decodeFromString(LogEntry.serializer(), ligne)
                } catch (e: SerializationException) {
                    skippedLines++
                    lastReadError = e.javaClass.simpleName
                    null
                }
            }

    /** Occupation disque des journaux. */
    internal data class DiskStats(
        val bytes: Long,
        val fileCount: Int,
    )
}

/**
 * Nommage et énumération des fichiers de journal d'un répertoire :
 * `current.jsonl` et `archive-N.jsonl` (N = 1 est la plus récente).
 */
private class FichiersJournal(
    private val repertoire: File,
) {
    /** Fichier courant de journal. */
    fun currentFile(): File = File(repertoire, LoggingLimits.CURRENT_FILE_NAME)

    /** Archive d'indice donné (1 = la plus récente). */
    fun archiveFile(indice: Int): File = File(repertoire, "${LoggingLimits.ARCHIVE_FILE_PREFIX}$indice.jsonl")

    /** Tous les fichiers de journal présents dans le répertoire. */
    fun deJournal(): List<File> =
        repertoire
            .listFiles()
            .orEmpty()
            .filter { it.isFile && (it.name == LoggingLimits.CURRENT_FILE_NAME || estArchive(it.name)) }
            .toList()

    /** Archives (indices décroissants) puis fichier courant, si présents. */
    fun enOrdreChronologique(): List<File> {
        val ordres = mutableListOf<File>()
        for (indice in indicesArchives()) ordres += archiveFile(indice)
        if (currentFile().exists()) ordres += currentFile()
        return ordres
    }

    /** Indices des archives existantes, de la plus récente à la plus vieille. */
    fun indicesArchives(): List<Int> =
        repertoire
            .listFiles()
            .orEmpty()
            .filter { it.isFile && estArchive(it.name) }
            .mapNotNull { nomArchive(it.name)?.toIntOrNull() }
            .sortedDescending()

    private fun estArchive(nom: String): Boolean = nomArchive(nom) != null

    private fun nomArchive(nom: String): String? =
        nom
            .takeIf { it.startsWith(LoggingLimits.ARCHIVE_FILE_PREFIX) && it.endsWith(".jsonl") }
            ?.removePrefix(LoggingLimits.ARCHIVE_FILE_PREFIX)
            ?.removeSuffix(".jsonl")
}
