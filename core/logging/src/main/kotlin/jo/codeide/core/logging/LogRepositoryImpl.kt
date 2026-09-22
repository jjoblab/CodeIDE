package jo.codeide.core.logging

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.LogDiskUsage
import jo.codeide.core.domain.LogRepository
import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation applicative du [LogRepository] du domaine (section 5.7).
 *
 * - [observeRecent] : le tampon mémoire du moteur, semé avec l'instantané
 *   courant puis prolongé à chaque émission ;
 * - [readAll] / [diskUsage] : lecture du stockage JSONL, sur le dispatcher
 *   d'I/O injecté ;
 * - [clear] : fichiers et tampon, ensemble — un effacement qui oublierait le
 *   tampon ferait réapparaître les entrées « récentes » à la prochaine
 *   émission.
 */
@Singleton
internal class LogRepositoryImpl
    @Inject
    constructor(
        private val engine: LogEngine,
        private val store: JsonlLogStore,
        private val dispatchers: DispatcherProvider,
    ) : LogRepository {
        override fun observeRecent(limit: Int): Flow<List<LogEntry>> =
            flow {
                var fenetre = engine.snapshot(limit)
                emit(fenetre)
                engine.entries.collect { entree ->
                    fenetre = (fenetre + entree).takeLast(limit)
                    emit(fenetre)
                }
            }

        override suspend fun readAll(): List<LogEntry> =
            withContext(dispatchers.io) {
                store.readAll()
            }

        override suspend fun diskUsage(): LogDiskUsage =
            withContext(dispatchers.io) {
                val stats = store.diskStats()
                LogDiskUsage(bytes = stats.bytes, fileCount = stats.fileCount)
            }

        override suspend fun clear() {
            withContext(dispatchers.io) {
                store.clear()
            }
            engine.resetBuffer()
        }
    }
