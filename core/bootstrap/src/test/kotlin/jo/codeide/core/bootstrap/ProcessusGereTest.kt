package jo.codeide.core.bootstrap

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Tests de régression du démontage du tuyau (ADR 0063) : journal de
 * terrain v0.35.2 — plantage d'EditorActivity 56 ms après « orchestrateur
 * muet — arrêt forcé », pile `InterruptedIOException : read interrupted
 * by close() on another thread` dans `ProcessusGere.lignes`.
 *
 * Sur Android (libcore), la mort du process referme les descripteurs
 * depuis un AUTRE fil et RÉVEILLE la lecture bloquée par
 * [InterruptedIOException] — notre supervision a DÉCIDÉ de fermer : la fin
 * du flux est NORMALE. Sur JVM bureau, la fermeture par un autre fil ne
 * réveille PAS la lecture (constaté empiriquement) : le mécanisme est
 * rejoué sur un process factice au flux scripté, et le kill réel couvre
 * le chemin EOF de la JVM des tests.
 */
class ProcessusGereTest {
    /** Process factice : flux d'entrée scripté, vie contrôlée par le test. */
    private class ProcessFactice(
        private val fluxEntree: InputStream,
        private val vivant: () -> Boolean,
    ) : Process() {
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = fluxEntree

        override fun getErrorStream(): InputStream = java.io.InputStream.nullInputStream()

        // isAlive() du JDK se déduit d'exitValue() : sans cette
        // redéfinition, le fake serait TOUJOURS mort aux yeux du
        // ProcessusGere (exitValue ne lève jamais ici).
        override fun isAlive(): Boolean = vivant()

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    /** Flux qui livre des octets PUIS se comporte comme un tuyau démonté. */
    private class FluxDemonte(
        private val lot: ByteArray,
        private val ensuite: () -> Nothing,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            when {
                position < lot.size -> lot[position++].toInt() and 0xFF
                else -> ensuite()
            }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int =
            when {
                len == 0 -> {
                    0
                }

                position < lot.size -> {
                    val n = minOf(len, lot.size - position)
                    System.arraycopy(lot, position, b, off, n)
                    position += n
                    n
                }

                else -> {
                    ensuite()
                }
            }
    }

    @Test
    fun `une lecture interrompue par la fermeture d un autre fil termine le flux normalement`() =
        runBlocking {
            // Régression EXACTE du journal de terrain : le message d'Android
            // libcore, tel que relevé dans la pile du plantage.
            val process =
                ProcessFactice(
                    FluxDemonte("avant la fermeture\n".toByteArray()) {
                        throw InterruptedIOException("read interrupted by close() on another thread")
                    },
                    vivant = { false },
                )
            val gere = ProcessusGere(process, Dispatchers.IO)

            // Doit COMPLÉTER sans lever — c'était un plantage de l'app.
            val lignes = mutableListOf<String>()
            withTimeout(5_000) { gere.stdoutLines().toList(lignes) }

            assertEquals(listOf("avant la fermeture"), lignes)
        }

    @Test
    fun `une lecture interrompue des le premier octet termine le flux vide sans erreur`() =
        runBlocking {
            val process =
                ProcessFactice(
                    FluxDemonte(ByteArray(0)) {
                        throw InterruptedIOException("read interrupted by close() on another thread")
                    },
                    vivant = { true },
                )
            val gere = ProcessusGere(process, Dispatchers.IO)

            // Même kill asynchrone pas encore effectué (process encore
            // « vivant ») : l'interruption reste une fin normale.
            val lignes = mutableListOf<String>()
            withTimeout(5_000) { gere.stdoutLines().toList(lignes) }

            assertTrue(lignes.isEmpty())
        }

    @Test
    fun `un flux ferme sur un process mort termine le flux normalement`() =
        runBlocking {
            val process =
                ProcessFactice(
                    FluxDemonte(ByteArray(0)) {
                        throw IOException("Stream closed")
                    },
                    vivant = { false },
                )
            val gere = ProcessusGere(process, Dispatchers.IO)

            val lignes = mutableListOf<String>()
            withTimeout(5_000) { gere.stderrLines().toList(lignes) }

            assertTrue(lignes.isEmpty())
        }

    @Test
    fun `un flux ferme sur un process vivant remonte l erreur de lecture`() =
        runBlocking {
            val process =
                ProcessFactice(
                    FluxDemonte(ByteArray(0)) {
                        throw IOException("Stream closed")
                    },
                    vivant = { true },
                )
            val gere = ProcessusGere(process, Dispatchers.IO)

            val levee =
                runCatching {
                    val lignes = mutableListOf<String>()
                    withTimeout(5_000) { gere.stdoutLines().toList(lignes) }
                }.exceptionOrNull()

            // Une erreur de lecture sur un process VIVANT est une vraie
            // erreur : elle remonte au consommateur.
            assertTrue("IOException attendue, reçue : $levee", levee is IOException)
        }

    @Test
    fun `l arret force pendant la lecture bloquee termine le flux sans exception`() =
        runBlocking {
            // Chemin RÉEL de l'arrêt forcé (health check muet, fin de
            // tentative) : le lecteur est bloqué dans readLine() quand le
            // kill referme le tuyau. Sur la JVM des tests le process mort
            // referme le tuyau en EOF — sur Android, le même chemin lève
            // l'InterruptedIOException des tests précédents. Dans les DEUX
            // cas : fin NORMALE du flux, jamais d'exception au collecteur.
            // (sleep DIRECT, sans shell : aucun fils n'hériterait le tuyau
            // et retarderait l'EOF.)
            val process = ProcessBuilder("sleep", "30").start()
            val gere = ProcessusGere(process, Dispatchers.IO)
            val termine = CompletableDeferred<List<String>>()
            val collecteur =
                launch {
                    termine.complete(gere.stdoutLines().toList(mutableListOf()))
                }

            // Laisse le lecteur atteindre le readLine() bloquant.
            TimeUnit.MILLISECONDS.sleep(500)
            gere.kill(force = true)

            val lignes = withTimeout(10_000) { termine.await() }
            assertTrue("aucune ligne attendue de sleep muet", lignes.isEmpty())
            collecteur.cancel()
            assertTrue("le process devait être mort", !process.isAlive)
        }
}
