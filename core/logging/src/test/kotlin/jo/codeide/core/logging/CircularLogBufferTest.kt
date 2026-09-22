package jo.codeide.core.logging

import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Tests du [CircularLogBuffer] : fenêtre, ordre, instantané, effacement —
 * puis cohérence sous accès concurrents (critère d'acceptation de l'étape 2 :
 * stress multi-threads).
 */
class CircularLogBufferTest {
    private companion object {
        const val THREADS = 8
        const val PAR_THREAD = 500
    }

    private fun entree(index: Int) =
        LogEntry(
            timestampMillis = index.toLong(),
            sessionId = "s",
            level = LogLevel.DEBUG,
            tag = "Stress",
            threadName = Thread.currentThread().name,
            message = "entrée $index",
        )

    @Test
    fun `conserve l'ordre et évite la plus ancienne entrée quand plein`() {
        val tampon = CircularLogBuffer(capacity = 3)

        repeat(5) { index -> tampon.add(entree(index)) }

        assertEquals(listOf("entrée 2", "entrée 3", "entrée 4"), tampon.snapshot().map { it.message })
        assertEquals(3, tampon.size())
    }

    @Test
    fun `l'instantané est une copie indépendante`() {
        val tampon = CircularLogBuffer(capacity = 2)
        tampon.add(entree(1))

        val instantane = tampon.snapshot()
        tampon.add(entree(2))

        assertEquals(1, instantane.size)
        assertEquals(2, tampon.snapshot().size)
    }

    @Test
    fun `efface le contenu`() {
        val tampon = CircularLogBuffer(capacity = 2)
        tampon.add(entree(1))
        tampon.add(entree(2))

        tampon.clear()

        assertTrue(tampon.snapshot().isEmpty())
    }

    @Test
    fun `reste cohérent sous écritures et lectures concurrentes`() {
        val tampon = CircularLogBuffer(capacity = 200)
        val executeurs = Executors.newFixedThreadPool(THREADS)
        val barriere = CountDownLatch(THREADS)

        repeat(THREADS) { numero ->
            executeurs.submit {
                barriere.countDown()
                barriere.await()
                repeat(PAR_THREAD) { index -> tampon.add(entree(numero * PAR_THREAD + index)) }
            }
        }
        // Un lecteur concurrent pendant les écritures : ne doit jamais
        // observer d'état incohérent (ni exception, ni taille hors bornes).
        val lecteur =
            Thread {
                repeat(100) {
                    val instantane = tampon.snapshot()
                    assertTrue(instantane.size <= 200)
                    instantane.forEach { assertTrue(it.message.isNotBlank()) }
                }
            }
        lecteur.start()
        executeurs.shutdown()
        assertTrue(executeurs.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        lecteur.join(10_000)

        assertEquals(200, tampon.size())
        val instantane = tampon.snapshot()
        assertEquals(instantane.size, instantane.map { it.message }.distinct().size)
    }
}
