package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ManagedProcess
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ProcessEnvironmentProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de référence de [NativeProcessLauncher] (prompt
 * compagnon Terminal-1, section 3.3 : bootstrap natif sans `proot`, un
 * simple `ProcessBuilder` avec l'environnement de la section 3.2
 * suffit).
 *
 * L'environnement du processus est **exactement**
 * [ProcessEnvironmentProvider.baseEnvironment] (source unique de
 * vérité — l'environnement hérité du processus Android est filtré
 * dedans) complété de [extraEnv] : le tableau du `ProcessBuilder` est
 * reconstruit de toutes pièces, jamais amendé à la marge.
 *
 * Le lancement lui-même est immédiat (aucun thread à occuper) ; les
 * lectures de flux et l'attente de sortie vivent dans le processus
 * supervisé retourné ([ProcessusGere]).
 */
@Singleton
internal class LanceurProcessusNatifs
    @Inject
    constructor(
        private val environnement: ProcessEnvironmentProvider,
        private val dispatchers: DispatcherProvider,
    ) : NativeProcessLauncher {
        override fun launch(
            command: List<String>,
            extraEnv: Map<String, String>,
            workingDir: File?,
        ): ManagedProcess {
            val constructeur = ProcessBuilder(command)
            workingDir?.let { constructeur.directory(it) }

            val variables = constructeur.environment()
            variables.clear()
            variables.putAll(environnement.baseEnvironment())
            variables.putAll(extraEnv)

            // Un échec de lancement (binaire introuvable, non exécutable)
            // remonte en IOException, documentée au port — jamais avalée.
            val processus = constructeur.start()
            return ProcessusGere(processus, dispatchers.io)
        }
    }
