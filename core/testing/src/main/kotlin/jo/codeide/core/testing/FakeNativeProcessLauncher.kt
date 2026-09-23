package jo.codeide.core.testing

import jo.codeide.core.domain.ManagedProcess
import jo.codeide.core.domain.NativeProcessLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.io.File
import java.io.IOException

/**
 * [NativeProcessLauncher](jo.codeide.core.domain.NativeProcessLauncher)
 * en mémoire : enregistre chaque lancement et sert des processus
 * scriptés (sorties et code de sortie prédéfinis) — aucune exécution
 * réelle.
 *
 * Le comportement par défaut retourne un processus silencieux qui
 * réussit (code 0) ; un test peut soit remplacer [fabrique], soit
 * injecter [echecLancement] pour éprouver l'échec du lancement
 * lui-même (binaire introuvable).
 */
public class FakeNativeProcessLauncher : NativeProcessLauncher {
    /** Commande lancée, capturée pour les assertions du test. */
    public data class CommandeLancee(
        public val command: List<String>,
        public val extraEnv: Map<String, String>,
        public val workingDir: File?,
    )

    /** Lancements dans l'ordre chronologique. */
    public val lancements: MutableList<CommandeLancee> = mutableListOf()

    /** Processus retournés, dans le même ordre que [lancements]. */
    public val processus: MutableList<ManagedProcess> = mutableListOf()

    /** Fabrique de processus pour un lancement donné (script par défaut). */
    public var fabrique: (CommandeLancee) -> ManagedProcess = { ProcessusScripte() }

    /** Quand non nulle, tout lancement échoue (binaire introuvable). */
    public var echecLancement: IOException? = null

    public override fun launch(
        command: List<String>,
        extraEnv: Map<String, String>,
        workingDir: File?,
    ): ManagedProcess {
        echecLancement?.let { throw it }
        val commande = CommandeLancee(command, extraEnv, workingDir)
        lancements += commande
        val processus = fabrique(commande)
        this.processus += processus
        return processus
    }
}

/**
 * [ManagedProcess](jo.codeide.core.domain.ManagedProcess) scripté : les
 * sorties et le code de sortie sont fournis par le test, aucune
 * exécution réelle.
 *
 * Les flux rejouent leur liste à chaque collecte (contrairement à un
 * vrai processus, le tuyau se relit — pratique pour plusieurs
 * assertions).
 */
public class ProcessusScripte(
    /** Code de sortie retourné par `awaitExit()`. */
    public val codeSortie: Int = 0,
    /** Lignes rejouées par `stdoutLines()`. */
    public val lignesStdout: List<String> = emptyList(),
    /** Lignes rejouées par `stderrLines()`. */
    public val lignesStderr: List<String> = emptyList(),
) : ManagedProcess {
    /** Identifiant factice mais réaliste (le port autorise n'importe quel positif). */
    override val pid: Int = PID_FACTICE

    /** Le processus est-il encore vivant (avant `awaitExit` ou `kill`) ? */
    public var vivant: Boolean = true
        private set

    /** Dernier `kill` reçu : `null` = jamais tué, sinon la valeur de `force`. */
    public var tueAvecForce: Boolean? = null
        private set

    override fun isAlive(): Boolean = vivant

    override fun stdoutLines(): Flow<String> = lignesStdout.asFlow()

    override fun stderrLines(): Flow<String> = lignesStderr.asFlow()

    override suspend fun awaitExit(): Int {
        vivant = false
        return codeSortie
    }

    override fun kill(force: Boolean) {
        tueAvecForce = force
        vivant = false
    }

    private companion object {
        /** Identifiant factice mais réaliste, distinct du repli -1 du port. */
        private const val PID_FACTICE = 42_424
    }
}
