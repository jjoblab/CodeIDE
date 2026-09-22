package jo.codeide.core.logging

import app.cash.turbine.test
import jo.codeide.core.domain.LogConfig
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests du [LogEngine] : filtrage (et non-évaluation des messages filtrés),
 * expurgation, troncature, aplatissement borné des exceptions, tampon
 * circulaire et émission sur le flot.
 */
class LogEngineTest {
    /** Sink enregistreur : capte les entrées sans Android ni disque. */
    private class RecordingSink(
        override val minLevel: LogLevel,
    ) : LogSink {
        val written = CopyOnWriteArrayList<LogEntry>()

        override fun write(entry: LogEntry) {
            written += entry
        }
    }

    private companion object {
        const val HORODATAGE_FIXE = 1_700_000_123_456L
        const val TAILLE_MAX_MESSAGE = 4 * 1024
        const val TRAMES_MAX = 50
    }

    private fun moteur(
        config: LogConfig = LogConfig(minLevel = LogLevel.INFO),
        sinks: List<LogSink> = emptyList(),
    ): LogEngine =
        LogEngine(
            timeProvider = TimeProvider { HORODATAGE_FIXE },
            configHolder = LogConfigHolder(config),
            sinks = sinks,
        )

    @Test
    fun `un message filtré n'est jamais évalué ni écrit`() {
        val sink = RecordingSink(LogLevel.DEBUG)
        var evaluations = 0
        val moteur = moteur(LogConfig(minLevel = LogLevel.WARN), listOf(sink))

        moteur.d("Test") {
            evaluations++
            "coûteux"
        }

        assertEquals(0, evaluations)
        assertTrue(sink.written.isEmpty())
    }

    @Test
    fun `un message actif est évalué, expurgé et enrichi`() {
        val sink = RecordingSink(LogLevel.DEBUG)
        val moteur = moteur(sinks = listOf(sink))

        moteur.i("App") { "ouverture du projet 7 par auteur@example.fr" }

        val entree = sink.written.single()
        assertEquals(LogLevel.INFO, entree.level)
        assertEquals("App", entree.tag)
        assertEquals(HORODATAGE_FIXE, entree.timestampMillis)
        assertEquals(moteur.sessionId, entree.sessionId)
        assertFalse(entree.threadName.isBlank())
        assertTrue(entree.message.contains("auteur@example.fr").not())
        assertTrue(entree.message.contains("<courriel>"))
        // L'identifiant métier survit à l'expurgation.
        assertTrue(entree.message.contains("projet 7"))
    }

    @Test
    fun `un message trop long est tronqué à 4 Kio sans casser l'UTF-8`() {
        val sink = RecordingSink(LogLevel.DEBUG)
        val moteur = moteur(sinks = listOf(sink))

        moteur.w("Test") { "é".repeat(TAILLE_MAX_MESSAGE) + "débordement" }

        val message = sink.written.single().message
        assertTrue(message.toByteArray(Charsets.UTF_8).size <= TAILLE_MAX_MESSAGE)
        // La coupe ne laisse pas de caractère cassé : le texte redécode
        // exactement en lui-même.
        assertEquals(message, String(message.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `une exception est aplatie, bornée et expurgée`() {
        val pile = List(TRAMES_MAX * 2) { StackTraceElement("C", "m", "F.kt", it + 1) }
        val exception =
            IllegalStateException("échec sur /storage/emulated/0/Projets/X").apply {
                stackTrace = pile.toTypedArray()
                initCause(RuntimeException("contacter a@b.fr"))
            }
        val sink = RecordingSink(LogLevel.DEBUG)
        val moteur = moteur(sinks = listOf(sink))

        moteur.e("Storage", exception) { "écriture impossible" }

        val aplatie = sink.written.single().exception
        assertEquals("java.lang.IllegalStateException", aplatie?.className)
        assertTrue(aplatie?.message?.contains("<chemin>") == true)
        assertEquals(TRAMES_MAX, aplatie?.frames?.size)
        val cause = aplatie?.cause
        assertEquals("java.lang.RuntimeException", cause?.className)
        assertTrue(cause?.message?.contains("<courriel>") == true)
        assertNull(cause?.cause)
    }

    @Test
    fun `le tampon circulaire garde les 200 dernières entrées`() {
        val sink = RecordingSink(LogLevel.DEBUG)
        val moteur = moteur(sinks = listOf(sink))

        repeat(210) { index -> moteur.i("Test") { "message $index" } }

        val instantane = moteur.snapshot(LoggingLimits.BREADCRUMB_CAPACITY)
        assertEquals(LoggingLimits.BREADCRUMB_CAPACITY, instantane.size)
        assertEquals("message 10", instantane.first().message)
        assertEquals("message 209", instantane.last().message)
    }

    @Test
    fun `les entrées actives sont émises sur le flot`() =
        runTest {
            val moteur = moteur()
            moteur.i("Test") { "avant abonnement" }

            moteur.entries.test {
                moteur.i("Test") { "première" }
                assertEquals("première", awaitItem().message)
                moteur.e("Test") { "deuxième" }
                assertEquals("deuxième", awaitItem().message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `la configuration peut se resserrer à chaud`() {
        val sink = RecordingSink(LogLevel.DEBUG)
        val holder = LogConfigHolder(LogConfig(minLevel = LogLevel.DEBUG))
        val moteur = LogEngine(TimeProvider { HORODATAGE_FIXE }, holder, listOf(sink))
        var evaluations = 0

        moteur.d("Test") {
            evaluations++
            "passe"
        }
        holder.update(LogConfig(minLevel = LogLevel.ERROR))
        moteur.d("Test") {
            evaluations++
            "bloqué"
        }

        assertEquals(1, evaluations)
        assertEquals(1, sink.written.size)
    }

    @Test
    fun `le vidage sans sink fichier est trivialement acquis`() {
        val moteur = moteur(sinks = listOf(RecordingSink(LogLevel.DEBUG)))

        assertTrue(moteur.flushBlocking(10))
    }

    @Test
    fun `resetBuffer efface l'instantané récent`() {
        val moteur = moteur()
        moteur.i("Test") { "une entrée" }
        assertEquals(1, moteur.snapshot(LoggingLimits.BREADCRUMB_CAPACITY).size)

        moteur.resetBuffer()

        assertTrue(moteur.snapshot(LoggingLimits.BREADCRUMB_CAPACITY).isEmpty())
    }
}
