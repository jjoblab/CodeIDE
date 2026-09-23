package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Lance un sous-processus **non interactif** du bootstrap natif (prompt
 * compagnon Terminal-1, sections 1.4 et 3.3).
 *
 * Périmètre strict : scripts d'installation (second stage, `apt`),
 * futur serveur Gradle du tooling. Les sessions shell **interactives**
 * ne passent pas par ce port — elles exigent un pseudo-terminal (pty)
 * et vivent dans `core:terminal-runtime` (section 1.5 du prompt).
 *
 * L'environnement du processus lancé est **toujours** celui de
 * [ProcessEnvironmentProvider] (source unique de vérité : retrait de
 * `CLASSPATH`/`LD_PRELOAD`, `GRADLE_USER_HOME` explicite, `PATH` du
 * bootstrap), complété par [extraEnv] — jamais reconstruit ailleurs.
 */
public interface NativeProcessLauncher {
    /**
     * Lance une commande dans l'environnement du bootstrap.
     *
     * @param command commande complète à exécuter (binaire + arguments),
     * par des chemins absolus vers `$PREFIX/bin`.
     * @param extraEnv variables ajoutées par-dessus l'environnement de
     * base (priorité sur celui-ci en cas d'homonymie).
     * @param workingDir répertoire de travail du processus, ou `null`
     * pour le répertoire courant de l'application.
     * @return le processus lancé et supervisé ([ManagedProcess]).
     * @throws java.io.IOException si le binaire est introuvable ou non
     * exécutable (lancement lui-même impossible).
     */
    public fun launch(
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        workingDir: File? = null,
    ): ManagedProcess
}

/**
 * Sous-processus natif lancé et supervisé (prompt compagnon Terminal-1,
 * section 2.2).
 *
 * Sémantique des flux : `stdoutLines()` et `stderrLines()` sont des
 * flux froids lisant le processus **ligne à ligne** — chaque flux n'est
 * consommable qu'une seule fois (un tuyau de processus ne se rembobine
 * pas) ; l'absence de collecteur n'interrompt pas le processus.
 *
 * `awaitExit()` est annulable sans tuer le processus : l'arrêt reste
 * un acte explicite ([kill]).
 */
public interface ManagedProcess {
    /**
     * Identifiant du processus, ou `-1` si le système ne l'expose pas
     * (obtention par réflexion sur l'implémentation de `java.lang.Process`,
     * avec repli silencieux — l'identifiant est purement informatif).
     */
    public val pid: Int

    /** Le processus est-il encore vivant ? */
    public fun isAlive(): Boolean

    /** Lignes de la sortie standard, ligne à ligne (flux froid, consommation unique). */
    public fun stdoutLines(): Flow<String>

    /** Lignes de la sortie d'erreur, ligne à ligne (flux froid, consommation unique). */
    public fun stderrLines(): Flow<String>

    /**
     * Attend la fin du processus et retourne son code de sortie.
     *
     * Annulable : l'annulation de l'appelant **ne tue pas** le
     * processus (voir [kill]).
     */
    public suspend fun awaitExit(): Int

    /**
     * Termine le processus.
     *
     * @param force `true` pour un `SIGKILL` (destruction brutale),
     * `false` pour une terminaison propre (`SIGTERM` ou équivalent).
     */
    public fun kill(force: Boolean)
}
