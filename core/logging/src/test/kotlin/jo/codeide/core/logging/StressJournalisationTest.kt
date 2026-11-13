package jo.codeide.core.logging

import jo.codeide.core.domain.LogConfig
import jo.codeide.core.domain.SystemTimeProvider
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test de stress multi-threads (critère d'acceptation de l'étape 2) :
 * huit threads produisent deux mille entrées à travers le moteur réel et
 * le sink fichier réel.
 *
 * Ce qui est garanti et vérifié : **l'intégrité** — aucune ligne corrompue,
 * aucun doublon, aucun échec d'écriture, aucune exception des producteurs,
 * et un vidage bloquant qui rend la main.
 *
 * Ce qui ne l'est pas : le taux de livraison. La file est bornée avec
 * DROP_OLDEST (section 5.7 : l'appelant n'est **jamais bloqué**) — sous
 * surcharge soutenue, la perte est le comportement spécifié, et son ampleur
 * dépend des ressources disponibles (sur cette machine à quota CPU
 * partagé, le test lui-même prive le consommateur de cycles : mesuré entre
 * ~40 % et ~95 % de livraison ; sur une machine au repos, ~100 %).
 */
class StressJournalisationTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private companion object {
        const val THREADS = 8
        const val TOTAL_ENTREES = 2_000

        /** Taille de fichier volontairement petite : la rotation aussi est sous stress. */
        const val TAILLE_FICHIER = 32_768L
    }

    @Test
    fun `le pipeline résiste à un stress multi-threads sans corruption`() {
        val json = Json { ignoreUnknownKeys = true }
        val store = JsonlLogStore(dossierTemporaire.root, json)
        val holder = LogConfigHolder(LogConfig(minLevel = LogLevel.DEBUG, maxFileSizeBytes = TAILLE_FICHIER))
        val sink = FileSink(store, json, SystemTimeProvider(), holder::read) { }
        val moteur = LogEngine(SystemTimeProvider(), holder, listOf(sink))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sink.start(scope)
        // Amorçage : le vidage bloquant prouve que le consommateur tourne —
        // le stress éprouve l'état permanent, pas la course au démarrage.
        moteur.i("Stress") { "amorçage" }
        assertTrue("l'amorçage doit être écrit", moteur.flushBlocking(5_000))

        val executeurs = Executors.newFixedThreadPool(THREADS)
        val barriere = CountDownLatch(THREADS)
        val erreurs = mutableListOf<Throwable>()
        repeat(THREADS) { numero ->
            executeurs.submit {
                try {
                    barriere.countDown()
                    barriere.await()
                    repeat(TOTAL_ENTREES / THREADS) { index ->
                        moteur.i("Stress") { "thread $numero entrée $index" }
                    }
                } catch (e: Throwable) {
                    synchronized(erreurs) { erreurs += e }
                }
            }
        }
        executeurs.shutdown()
        assertTrue("les producteurs n'ont pas fini à temps", executeurs.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue("le vidage borné doit réussir", moteur.flushBlocking(5_000))
        scope.cancel()

        assertTrue("exceptions des producteurs : $erreurs", erreurs.isEmpty())
        assertEquals("aucune écriture disque ne doit échouer", 0, sink.writeErrorCount)

        val lues = store.readAll()
        assertTrue(
            "le pipeline doit livrer un volume significatif (au moins la capacité du canal), obtenu : ${lues.size}",
            lues.size >= LoggingLimits.CHANNEL_CAPACITY,
        )
        // Intégrité : chaque ligne livrée redécode (readAll le garantit) et
        // aucun message n'apparaît deux fois — pas de duplication ni d'altération.
        assertEquals(lues.size, lues.map { it.message }.distinct().size)
        // FIFO par producteur : le canal préserve l'ordre relatif des
        // survivants — DROP_OLDEST retire en tête, ne réordonne jamais.
        // (L'horodatage global, lui, peut s'inverser d'un millième de
        // seconde entre threads : ce n'est pas une garantie.)
        val regex = Regex("""thread (\d+) entrée (\d+)$""")
        val derniersIndices = mutableMapOf<Int, Int>()
        lues.forEach { entree ->
            val correspondance = regex.find(entree.message) ?: return@forEach
            val numero = correspondance.groupValues[1].toInt()
            val index = correspondance.groupValues[2].toInt()
            val precedent = derniersIndices[numero]
            assertTrue(
                "ordre FIFO rompu pour le thread $numero : $precedent puis $index",
                precedent == null || precedent < index,
            )
            derniersIndices[numero] = index
        }
        // La rotation a bien travaillé : plusieurs fichiers coexistent.
        assertTrue("des archives de rotation sont attendues", store.diskStats().fileCount > 1)
    }

    @Test
    fun `le vidage sous requêtes concurrentes reste correct`() {
        val json = Json { ignoreUnknownKeys = true }
        val store = JsonlLogStore(dossierTemporaire.root, json)
        val holder = LogConfigHolder(LogConfig())
        val sink = FileSink(store, json, SystemTimeProvider(), holder::read) { }
        val moteur = LogEngine(SystemTimeProvider(), holder, listOf(sink))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sink.start(scope)
        moteur.i("Stress") { "amorçage" }
        assertTrue(moteur.flushBlocking(1_000))

        val executeurs = Executors.newFixedThreadPool(4)
        val barriere = CountDownLatch(4)
        repeat(4) { numero ->
            executeurs.submit {
                barriere.countDown()
                barriere.await()
                repeat(100) { index -> moteur.d("Stress") { "lot $numero entrée $index" } }
                moteur.flushBlocking(1_000)
            }
        }
        executeurs.shutdown()
        assertTrue(executeurs.awaitTermination(60, TimeUnit.SECONDS))
        scope.cancel()

        val lues = store.readAll()
        assertEquals(lues.size, lues.map { it.message }.distinct().size)
        assertEquals(0, sink.writeErrorCount)
    }
}
