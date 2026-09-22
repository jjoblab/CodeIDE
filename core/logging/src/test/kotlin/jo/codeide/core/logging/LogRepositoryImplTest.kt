package jo.codeide.core.logging

import app.cash.turbine.test
import jo.codeide.core.domain.LogConfig
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.LogLevel
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests du [LogRepositoryImpl] : fenêtre d'observation semée avec
 * l'instantané du tampon puis prolongée, lecture du stockage, occupation
 * disque, effacement complet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogRepositoryImplTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    /** Assemble moteur + stockage + dépôt sur le scheduler du test. */
    private fun assembler(scheduler: TestCoroutineScheduler): Triple<LogEngine, JsonlLogStore, LogRepositoryImpl> {
        val store = JsonlLogStore(dossierTemporaire.root, json)
        val holder = LogConfigHolder(LogConfig(minLevel = LogLevel.DEBUG))
        val moteur = LogEngine(TimeProvider { 0L }, holder, emptyList())
        val dispatchers = TestDispatcherProvider(StandardTestDispatcher(scheduler))
        return Triple(moteur, store, LogRepositoryImpl(moteur, store, dispatchers))
    }

    /** Écrit une entrée directement dans le stockage (controle du disque). */
    private fun ecrireSurDisque(
        store: JsonlLogStore,
        message: String,
    ) {
        store.appendLines(
            lines =
                listOf(
                    """{"timestampMillis":1,"sessionId":"a","level":"INFO","tag":"T",""" +
                        """"threadName":"main","message":"$message"}""",
                ),
            maxFileSizeBytes = 1_048_576L,
            maxArchiveFiles = 5,
        )
    }

    @Test
    fun `observeRecent sème avec l'instantané puis suit les nouvelles entrées`() =
        runTest {
            val (moteur, _, depot) = assembler(testScheduler)
            moteur.i("Test") { "première" }
            moteur.i("Test") { "deuxième" }

            depot.observeRecent(limit = 2).test {
                assertEquals(listOf("première", "deuxième"), awaitItem().map { it.message })
                moteur.i("Test") { "troisième" }
                assertEquals(listOf("deuxième", "troisième"), awaitItem().map { it.message })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `readAll lit l'historique persisté`() =
        runTest {
            val (_, store, depot) = assembler(testScheduler)
            ecrireSurDisque(store, "une")
            ecrireSurDisque(store, "deux")

            val lues = depot.readAll()

            assertEquals(listOf("une", "deux"), lues.map { it.message })
        }

    @Test
    fun `diskUsage reflète le stockage`() =
        runTest {
            val (_, store, depot) = assembler(testScheduler)
            ecrireSurDisque(store, "une")

            val occupation = depot.diskUsage()

            assertTrue(occupation.bytes > 0)
            assertEquals(1, occupation.fileCount)
        }

    @Test
    fun `clear efface les fichiers et le tampon des entrées récentes`() =
        runTest {
            val (moteur, store, depot) = assembler(testScheduler)
            moteur.i("Test") { "au tampon" }
            ecrireSurDisque(store, "sur disque")

            depot.clear()

            assertTrue(depot.readAll().isEmpty())
            assertTrue(moteur.snapshot(LoggingLimits.BREADCRUMB_CAPACITY).isEmpty())
        }
}
