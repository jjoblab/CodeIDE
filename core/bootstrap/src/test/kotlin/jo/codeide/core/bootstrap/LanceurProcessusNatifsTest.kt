package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.testing.FakeProcessEnvironmentProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests du lanceur de sous-processus (critère d'acceptation T2, section
 * 3.3 du prompt Terminal-1) sur de **vrais** processus `/bin/sh` en
 * JVM : flux de lignes stdout/stderr, code de sortie, terminaison,
 * environnement exactement issu du fournisseur + extraEnv.
 */
class LanceurProcessusNatifsTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private val environnement = FakeProcessEnvironmentProvider()
    private val lanceur =
        LanceurProcessusNatifs(
            environnement = environnement,
            dispatchers = dispatcheursDirects(),
        )

    @Test
    fun `lit les lignes de stdout et retourne le code de sortie`() =
        runBlocking {
            val processus = lanceur.launch(listOf("/bin/sh", "-c", "echo bonjour; echo monde"))

            val lignes = processus.stdoutLines().toList()
            assertEquals(listOf("bonjour", "monde"), lignes)
            assertEquals(0, processus.awaitExit())
            assertTrue(processus.pid == -1 || processus.pid > 0)
        }

    @Test
    fun `lit les lignes de stderr séparément`() =
        runBlocking {
            val processus = lanceur.launch(listOf("/bin/sh", "-c", "echo alerte 1>&2"))

            assertEquals(listOf("alerte"), processus.stderrLines().toList())
            assertEquals(0, processus.awaitExit())
        }

    @Test
    fun `retourne le code de sortie non nul`() =
        runBlocking {
            val processus = lanceur.launch(listOf("/bin/sh", "-c", "exit 3"))

            assertEquals(3, processus.awaitExit())
        }

    @Test
    fun `le processus voit exactement l environnement du fournisseur`() =
        runBlocking {
            environnement.semer(mapOf("CODEIDE_TEST" to "fournisseur", "TERM" to "dumb"))

            val processus = lanceur.launch(listOf("/bin/sh", "-c", "echo \$CODEIDE_TEST \$TERM"))

            assertEquals(listOf("fournisseur dumb"), processus.stdoutLines().toList())
        }

    @Test
    fun `extraEnv prime sur l environnement du fournisseur`() =
        runBlocking {
            environnement.semer(mapOf("CODEIDE_TEST" to "fournisseur"))

            val processus =
                lanceur.launch(
                    listOf("/bin/sh", "-c", "echo \$CODEIDE_TEST"),
                    extraEnv = mapOf("CODEIDE_TEST" to "remplace"),
                )

            assertEquals(listOf("remplace"), processus.stdoutLines().toList())
        }

    @Test
    fun `kill force termine un processus long`() =
        runBlocking {
            val processus = lanceur.launch(listOf("/bin/sh", "-c", "sleep 60"))

            assertTrue(processus.isAlive())
            processus.kill(force = true)
            // Petit délai : la destruction asynchrone se propage au tuyau.
            var attente = 0
            while (processus.isAlive() && attente < 50) {
                TimeUnit.MILLISECONDS.sleep(10)
                attente++
            }
            assertTrue(!processus.isAlive())
        }

    @Test
    fun `le répertoire de travail est honoré`() =
        runBlocking {
            val dossier = dossierTemp.newFolder()
            File(dossier, "marqueur.txt").writeText("x")
            val processus =
                lanceur.launch(
                    listOf("/bin/sh", "-c", "ls marqueur.txt >/dev/null && echo present"),
                    workingDir = dossier,
                )

            assertEquals(listOf("present"), processus.stdoutLines().toList())
        }

    @Test
    fun `awaitExit borne dans le temps pour un processus court`() =
        runBlocking {
            val processus = lanceur.launch(listOf("/bin/sh", "-c", "exit 0"))

            val code = withTimeout(TimeUnit.SECONDS.toMillis(10)) { processus.awaitExit() }
            assertEquals(0, code)
        }
}

/** Dispatchers réels (test JVM : IO/Main réels, pas de virtualisation). */
private fun dispatcheursDirects(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = kotlinx.coroutines.Dispatchers.IO
        override val default = kotlinx.coroutines.Dispatchers.Default
        override val main = kotlinx.coroutines.Dispatchers.Default
    }
