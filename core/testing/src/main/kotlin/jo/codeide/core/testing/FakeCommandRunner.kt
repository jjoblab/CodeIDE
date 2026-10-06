package jo.codeide.core.testing

import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandRunner
import jo.codeide.core.domain.CommandSpec
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Doublure de [CommandRunner] (refonte E2, ADR 0087) : les commandes
 * sont enregistrées et les résultats **scriptés** par le test via
 * [fabrique] — jamais de vrai sous-processus.
 *
 * Le semeur [echecLancement] reproduit un refus du système au lancement
 * (W^X, ADR 0045) : l'exception monte telle quelle, comme avec le vrai
 * lanceur.
 */
public class FakeCommandRunner : CommandRunner {
    /** Commandes exécutées, dans l'ordre (threadsafe : le pipeline est concurrent). */
    public val commandes: MutableList<CommandSpec> = CopyOnWriteArrayList()

    /** Fabrique des résultats — remplaçable par le test (défaut : succès muet). */
    public var fabrique: (CommandSpec) -> CommandResult =
        { CommandResult(exitCode = 0, stdout = emptyList(), stderr = emptyList()) }

    /** Quand non `null`, le lancement échoue par [IOException] (W^X). */
    public var echecLancement: IOException? = null

    override suspend fun run(command: CommandSpec): CommandResult {
        echecLancement?.let { throw it }
        commandes += command
        return fabrique(command)
    }
}
