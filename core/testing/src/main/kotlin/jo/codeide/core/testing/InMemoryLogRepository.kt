package jo.codeide.core.testing

import jo.codeide.core.domain.LogDiskUsage
import jo.codeide.core.domain.LogRepository
import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * [LogRepository](jo.codeide.core.domain.LogRepository) en mémoire : les
 * entrées vivent dans un [MutableStateFlow], sans disque.
 *
 * Les deux propriétés `*Error` sont des robinets de défaillance pilotés par
 * le test : les mettre à une [IOException] fait échouer l'opération
 * correspondante — de quoi éprouver la gestion d'erreur des cas d'usage
 * (`ExportLogsUseCase`, `ClearLogsUseCase`) sans mock ni système de
 * fichiers.
 */
public class InMemoryLogRepository : LogRepository {
    private val etat = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Quand non nulle, [readAll] lève cette exception (simulation d'I/O). */
    public var readError: IOException? = null

    /** Quand non nulle, [clear] lève cette exception (simulation d'I/O). */
    public var clearError: IOException? = null

    /**
     * Ajoute des entrées au dépôt (et les republie aux observateurs).
     *
     * @param newEntries entrées à ajouter, dans l'ordre chronologique.
     */
    public fun add(vararg newEntries: LogEntry) {
        etat.value += newEntries
    }

    public override fun observeRecent(limit: Int): Flow<List<LogEntry>> =
        etat.map { entrees -> entrees.takeLast(limit) }

    public override suspend fun readAll(): List<LogEntry> {
        readError?.let { throw it }
        return etat.value.toList()
    }

    public override suspend fun diskUsage(): LogDiskUsage = LogDiskUsage(bytes = 0, fileCount = 0)

    public override suspend fun clear() {
        clearError?.let { throw it }
        etat.value = emptyList()
    }
}
