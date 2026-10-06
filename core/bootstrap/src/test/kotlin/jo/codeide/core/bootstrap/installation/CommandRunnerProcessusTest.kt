package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.ProcessusScripte
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests du runner de commandes (ADR 0087 § 2) : capture **intégrale**
 * des deux flux, délai maximal qui détruit le processus (`timedOut`),
 * annulation qui détruit le sous-processus, échec de lancement (W^X)
 * qui monte l'`IOException` — doublures `core:testing`, aucun vrai
 * processus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandRunnerProcessusTest {
    private val dispatcheur = StandardTestDispatcher()

    @Test
    fun `la sortie intégrale de stdout et stderr est capturée avec le code de retour`() =
        runTest(dispatcheur) {
            val lanceur = FakeNativeProcessLauncher()
            lanceur.fabrique = {
                ProcessusScripte(
                    codeSortie = 3,
                    lignesStdout = listOf("ligne 1", "ligne 2"),
                    lignesStderr = listOf("avertissement", "erreur fatale"),
                )
            }
            val runner = CommandRunnerProcessus(lanceur)

            val resultat = runner.run(CommandSpec(program = "/prefix/bin/pkg", arguments = listOf("update")))

            assertEquals(3, resultat.exitCode)
            assertEquals(listOf("ligne 1", "ligne 2"), resultat.stdout)
            assertEquals(listOf("avertissement", "erreur fatale"), resultat.stderr)
            assertFalse(resultat.succeeded)
            assertFalse(resultat.timedOut)
        }

    @Test
    fun `le délai maximal détruit le processus et signale timedOut`() =
        runTest(dispatcheur) {
            val lanceur = FakeNativeProcessLauncher()
            val sansFin = ProcessusSansFin()
            lanceur.fabrique = { sansFin }
            val runner = CommandRunnerProcessus(lanceur)

            val resultat =
                runner.run(
                    CommandSpec(
                        program = "/prefix/bin/apt",
                        arguments = listOf("update"),
                        timeoutMillis = 50,
                    ),
                )

            assertTrue(resultat.timedOut)
            assertTrue(sansFin.tue)
        }

    @Test
    fun `l annulation de l appelant détruit le sous-processus`() =
        runTest(dispatcheur) {
            val lanceur = FakeNativeProcessLauncher()
            val sansFin = ProcessusSansFin()
            lanceur.fabrique = { sansFin }
            val runner = CommandRunnerProcessus(lanceur)
            var termine = false

            val travail =
                launch {
                    runner.run(CommandSpec(program = "/prefix/bin/pkg", arguments = listOf("install", "-y", "curl")))
                    termine = true
                }
            // Démarre la coroutine jusqu'au premier suspend (le sous-processus
            // est lancé) AVANT d'annuler — sinon elle ne s'exécute jamais.
            dispatcheur.scheduler.runCurrent()
            travail.cancel()
            travail.join()

            assertTrue(sansFin.tue)
            assertFalse(termine)
        }

    @Test
    fun `un échec de lancement monte l IOException telle quelle`() =
        runTest(dispatcheur) {
            val lanceur = FakeNativeProcessLauncher()
            lanceur.echecLancement = IOException("Permission denied")
            val runner = CommandRunnerProcessus(lanceur)

            var levee: IOException? = null
            try {
                runner.run(CommandSpec(program = "/prefix/bin/sh"))
            } catch (e: IOException) {
                levee = e
            }

            assertEquals("Permission denied", levee?.message)
        }

    /** Processus figé : `awaitExit` ne revient jamais — seul le timeout ou le kill l'arrête. */
    private class ProcessusSansFin : jo.codeide.core.domain.ManagedProcess {
        var tue = false

        override val pid: Int = 99

        override fun isAlive(): Boolean = !tue

        override fun stdoutLines(): Flow<String> = flow { }

        override fun stderrLines(): Flow<String> = flow { }

        override suspend fun awaitExit(): Int {
            while (!tue) {
                delay(10)
            }
            return 137
        }

        override fun kill(force: Boolean) {
            tue = true
        }
    }
}
