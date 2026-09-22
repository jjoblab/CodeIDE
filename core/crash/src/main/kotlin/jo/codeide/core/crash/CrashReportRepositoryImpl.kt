package jo.codeide.core.crash

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.CrashReportRepository
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashReportSummary
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de [CrashReportRepository] sur fichiers (section 5.8).
 *
 * Les lectures et mutations passent par [CrashReportFileStore] sur le
 * dispatcher d'I/O ; l'observation est un flot froid rafraîchi par signal à
 * chaque mutation du dépôt (les écritures du gestionnaire ou du détecteur
 * de démarrage, hors dépôt, apparaissent à la prochaine collection).
 */
@Singleton
class CrashReportRepositoryImpl
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        private val dispatchers: DispatcherProvider,
    ) : CrashReportRepository {
        private val store = CrashReportFileStore(File(context.filesDir, CrashLimits.DIRECTORY_NAME))

        /**
         * Signal de rafraîchissement : un rejeu initial (valeur au démarrage)
         * et un par mutation — chaque collecteur repart de l'état courant.
         */
        private val rafraichissements =
            MutableSharedFlow<Unit>(
                replay = 1,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        init {
            rafraichissements.tryEmit(Unit)
        }

        override fun observeSummaries(): Flow<List<CrashReportSummary>> =
            rafraichissements.map { avecIo { store.listSummaries() } }

        override suspend fun get(id: String): CrashReport? = avecIo { store.get(id) }

        override suspend fun markReviewed(id: String): Boolean {
            val pose = avecIo { store.markReviewed(id) }
            if (pose) rafraichir()
            return pose
        }

        override suspend fun delete(id: String): Boolean {
            val supprime = avecIo { store.delete(id) }
            if (supprime) rafraichir()
            return supprime
        }

        override suspend fun deleteAll(): Int {
            val nombre = avecIo { store.deleteAll() }
            if (nombre > 0) rafraichir()
            return nombre
        }

        override suspend fun hasUnreviewed(): Boolean = avecIo { store.hasUnreviewed() }

        private fun rafraichir() {
            rafraichissements.tryEmit(Unit)
        }

        private suspend fun <T> avecIo(bloc: () -> T): T = withContext(dispatchers.io) { bloc() }
    }
