package jo.codeide.core.bootstrap

import jo.codeide.core.domain.ManagedProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
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

    /**
     * Lit un tuyau ligne à ligne dans le dispatcheur d'E/S.
     *
     * Tolérance au DÉMONTAGE du tuyau (ADR 0063) : sur Android, la mort du
     * process (arrêt forcé du health check, fin de tentative) referme les
     * descripteurs depuis un AUTRE fil — libcore réveille la lecture
     * bloquée par `InterruptedIOException` (« read interrupted by close()
     * on another thread », journal de terrain v0.35.2 : plantage de
     * EditorActivity 56 ms après « orchestrateur muet — arrêt forcé »).
     * Cette fermeture est DÉCidée par notre supervision : la fin du flux
     * est NORMALE, pas un échec — remonter l'exception faisait planter
     * l'app entière pour un diagnostic de tuyau. Une [IOException] alors
     * que le process est déjà mort reçoit le même traitement (bruit de
     * démontage) ; pendant qu'il vit, elle reste une VRAIE erreur de
     * lecture et remonte telle quelle.
     *
     * Exemption ciblée (SwallowedException) : la fermeture interrompue EST
     * l'information de fin (démontage délibéré du superviseur) — la fin
     * silencieuse du flux est le comportement documenté au port, même
     * approche que le EOF de SessionSocketAndroid.
     */
    @Suppress("SwallowedException")
    private fun lignes(flux: InputStream): Flow<String> =
        flow {
            try {
                BufferedReader(InputStreamReader(flux, StandardCharsets.UTF_8)).use { lecteur ->
                    var ligne = lecteur.readLine()
                    while (ligne != null) {
                        emit(ligne)
                        ligne = lecteur.readLine()
                    }
                }
            } catch (interrompue: InterruptedIOException) {
                // readLine() bloqué réveillé par la fermeture du flux
                // depuis un autre fil (arrêt forcé) : fin normale.
            } catch (fermee: IOException) {
                // Process déjà mort : bruit de démontage du tuyau, fin
                // normale — vivant, c'est une vraie erreur de lecture.
                if (processus.isAlive) throw fermee
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
