package jo.codeide.core.logging

import jo.codeide.core.domain.LogConfig
import jo.codeide.core.domain.SystemTimeProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Tests du [FileSink] (critères d'acceptation de l'étape 2) : groupement
 * des écritures dans la fenêtre de 500 ms, écriture immédiate sur `ERROR`,
 * vidage bloquant borné, et non-blocage de l'appelant (canal borné).
 *
 * Les tests de fenêtre utilisent le temps virtuel ; le test de vidage
 * utilise de vrais threads (le verrou de coordination est bloquant).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSinkTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun entree(
        niveau: LogLevel = LogLevel.INFO,
        message: String = "une entrée",
    ): LogEntry =
        LogEntry(
            timestampMillis = 0L,
            sessionId = "s",
            level = niveau,
            tag = "Test",
            threadName = "main",
            message = message,
        )

    private fun sink(
        dossier: File,
        timeProvider: TimeProvider,
        echecs: MutableList<IOException> = mutableListOf(),
    ): FileSink {
        val store = JsonlLogStore(dossier, json)
        return FileSink(
            store = store,
            json = json,
            timeProvider = timeProvider,
            configSupplier = { LogConfig() },
            failureListener = WriteFailureListener { e -> echecs += e },
        )
    }

    @Test
    fun `sans démarrage, aucune écriture disque n'a lieu`() {
        val sink = sink(dossierTemporaire.root, SystemTimeProvider())

        repeat(100) { sink.write(entree()) }

        assertEquals(
            0,
            dossierTemporaire.root
                .listFiles()
                .orEmpty()
                .size,
        )
    }

    @Test
    fun `regroupe les écritures dans la fenêtre de 500 ms`() =
        runTest {
            val horloge = TimeProvider { currentTime }
            val sink = sink(dossierTemporaire.root, horloge)
            val store = JsonlLogStore(dossierTemporaire.root, json)
            sink.start(backgroundScope)

            sink.write(entree(message = "première"))
            advanceTimeBy(LoggingLimits.BATCH_WINDOW_MS - 1)
            runCurrent()
            // La fenêtre n'est pas écoulée : rien n'est encore sur disque.
            assertEquals(0, store.readAll().size)

            advanceTimeBy(1)
            runCurrent()

            assertEquals(1, store.readAll().size)
            assertEquals("première", store.readAll().single().message)
        }

    @Test
    fun `une entrée ERROR force l'écriture immédiate`() =
        runTest {
            val horloge = TimeProvider { currentTime }
            val store = JsonlLogStore(dossierTemporaire.root, json)
            val sink =
                FileSink(store, json, horloge, { LogConfig() }) { }
            sink.start(backgroundScope)

            sink.write(entree(LogLevel.ERROR, "urgente"))
            runCurrent() // sans avancer le temps virtuel

            assertEquals(1, store.readAll().size)
            assertEquals(LogLevel.ERROR, store.readAll().single().level)
        }

    @Test
    fun `flushBlocking écrit tout ce qui précède et rend la main`() {
        // runBlocking autorisé dans un test : c'est précisément un vidage
        // bloquant, sur threads réels, que l'on éprouve ici.
        kotlinx.coroutines.runBlocking {
            val horloge = SystemTimeProvider()
            val store = JsonlLogStore(dossierTemporaire.root, json)
            val sink = FileSink(store, json, horloge, { LogConfig() }) { }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            sink.start(scope)

            repeat(50) { index -> sink.write(entree(message = "entrée $index")) }
            assertTrue(sink.flushBlocking(2_000))
            assertEquals(50, store.readAll().size)

            scope.cancel()
        }
    }

    @Test
    fun `le canal borné ne bloque jamais l'appelant`() {
        val sink = sink(dossierTemporaire.root, SystemTimeProvider())

        // Aucun consommateur démarré : la file sature immédiatement et
        // DROP_OLDEST absorbe — ces 10 000 offres doivent revenir
        // instantanément (le test timeout s'il y a blocage).
        repeat(10_000) { index -> sink.write(entree(message = "rafale $index")) }

        assertEquals(0, sink.writeErrorCount)
    }
}
