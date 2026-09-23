package jo.codeide.core.bootstrap

import jo.codeide.core.domain.ManagedProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * Sous-processus natif supervisé (prompt compagnon Terminal-1,
 * section 3.3 — implémentation de [ManagedProcess]).
 *
 * Les flux de lignes sont lus dans le dispatcheur d'E/S au collecteur
 * (le tuyau du processus ne peut être lu qu'une fois : chaque flux est
 * **froid et consommable une seule fois**, documenté au port).
 * `awaitExit()` sonde la vie du processus à intervalle court : annulable
 * sans tuer le processus, contrairement à un `waitFor()` bloquant qui
 * ignorerait l'annulation.
 */
internal class ProcessusGere(
    private val processus: Process,
    private val io: CoroutineDispatcher,
) : ManagedProcess {
    override val pid: Int by lazy { lirePid(processus) }

    override fun isAlive(): Boolean = processus.isAlive

    override fun stdoutLines(): Flow<String> = lignes(processus.inputStream)

    override fun stderrLines(): Flow<String> = lignes(processus.errorStream)

    override suspend fun awaitExit(): Int {
        while (processus.isAlive && coroutineContext.isActive) {
            delay(PERIODE_SONDE)
        }
        return processus.exitValue()
    }

    override fun kill(force: Boolean) {
        if (force) {
            processus.destroyForcibly()
        } else {
            processus.destroy()
        }
    }

    /** Lit un tuyau ligne à ligne dans le dispatcheur d'E/S. */
    private fun lignes(flux: InputStream): Flow<String> =
        flow {
            BufferedReader(InputStreamReader(flux, StandardCharsets.UTF_8)).use { lecteur ->
                var ligne = lecteur.readLine()
                while (ligne != null) {
                    emit(ligne)
                    ligne = lecteur.readLine()
                }
            }
        }.flowOn(io)

    private companion object {
        /** Période de sonde de [awaitExit] (millisecondes). */
        private const val PERIODE_SONDE = 100L
    }

    // Exemption ciblée (SwallowedException) : la réflexion peut échouer
    // pour toute raison (API masquée, JVM de test) — le pid est purement
    // informatif, -1 est la valeur de repli documentée au port (même
    // approche que Termux, ShellUtils.getPid).
    @Suppress("SwallowedException")
    private fun lirePid(p: Process): Int =
        try {
            val champ = p.javaClass.getDeclaredField("pid")
            champ.isAccessible = true
            try {
                champ.getInt(p)
            } finally {
                champ.isAccessible = false
            }
        } catch (t: Throwable) {
            -1
        }
}
