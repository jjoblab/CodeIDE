package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandRunner
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.NativeProcessLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exécution de commandes non interactives du parcours d'installation
 * (port [CommandRunner], ADR 0085 § 3 / ADR 0087 § 2) — enveloppe du
 * [NativeProcessLauncher] : programme et arguments explicites (jamais
 * de shell externe), capture **intégrale** de stdout et stderr (le
 * contrat « aucune sortie jetée » — l'ancien `SupervisionProcessus` ne
 * gardait que 5 lignes de stderr), délai maximal et annulation qui
 * détruit le processus.
 *
 * Un `sh -c "…"` reste légitime : le programme exécuté est `sh`, les
 * arguments sont explicites — l'interpolation par un shell externe est
 * impossible.
 *
 * @param lanceur lanceur de sous-processus dans l'environnement canonique
 * (le drainage des flux vit dans les `Flow` du processus géré).
 * @throws java.io.IOException si le lancement est refusé par le système
 * (W^X, ADR 0045) — l'étape appelante traduit via
 * [ErreursInstallation.traduireLancement].
 */
@Singleton
internal class CommandRunnerProcessus
    @Inject
    constructor(
        private val lanceur: NativeProcessLauncher,
    ) : CommandRunner {
        override suspend fun run(command: CommandSpec): CommandResult = executer(command)

        /** Lance, draine en parallèle, applique le délai maximal. */
        private suspend fun executer(command: CommandSpec): CommandResult {
            val processus =
                lanceur.launch(
                    command = listOf(command.program) + command.arguments,
                    extraEnv = command.environment,
                    workingDir = command.workingDir,
                )
            val stdout = ConcurrentLinkedQueue<String>()
            val stderr = ConcurrentLinkedQueue<String>()
            try {
                return coroutineScope {
                    // Deux tuyaux drainés en parallèle : un tuyau non lu
                    // sature et bloque le sous-processus (leçon v0.55.0).
                    val drainages =
                        listOf(
                            launch { processus.stdoutLines().collect { stdout += it } },
                            launch { processus.stderrLines().collect { stderr += it } },
                        )
                    val code =
                        withTimeoutOrNull(command.timeoutMillis ?: DUREE_INFINIE) {
                            processus.awaitExit()
                        }
                    if (code == null) {
                        processus.kill(force = true)
                    }
                    drainages.joinAll()
                    val codeFinal = code ?: processus.awaitExit()
                    CommandResult(
                        exitCode = codeFinal,
                        stdout = stdout.toList(),
                        stderr = stderr.toList(),
                        timedOut = code == null,
                    )
                }
            } catch (e: CancellationException) {
                processus.kill(force = true)
                throw e
            }
        }

        private companion object {
            /** Aucun délai maximal : la commande vit autant que nécessaire. */
            private const val DUREE_INFINIE: Long = Long.MAX_VALUE
        }
    }
