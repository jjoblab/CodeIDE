package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.ArchiveExtractor
import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandRunner
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DownloadManager
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.ToolManifestClient

/**
 * Contexte d'exécution d'une étape (port [StepContext], ADR 0087 § 1) :
 * l'orchestrateur l'injecte à chaque étape — commandes **journalisées**
 * (chaque ligne stdout/stderr part au journal du parcours, jamais
 * jetée), téléchargements, extraction, manifeste, canaux de
 * progression et de journal.
 *
 * @param commands exécution de commandes (décorée par [CommandRunnerJournalise]).
 * @param downloads téléchargements avec cache SHA-256.
 * @param archives extraction d'artefacts.
 * @param manifest client du manifeste d'outils v2.
 * @param surProgression receveur de la progression courante (conflation
 * par l'orchestrateur, publication dans `PhaseState.Running`).
 * @param surJournal receveur des lignes de journal (borné et expurgé par
 * l'orchestrateur).
 */
internal class ContexteEtape(
    override val commands: CommandRunner,
    override val downloads: DownloadManager,
    override val archives: ArchiveExtractor,
    override val manifest: ToolManifestClient,
    private val surProgression: (Progress) -> Unit,
    private val surJournal: (String) -> Unit,
) : StepContext {
    override fun reportProgress(progress: Progress) {
        surProgression(progress)
    }

    override fun journal(line: String) {
        surJournal(line)
    }
}

/**
 * Décorateur du [CommandRunner] qui journalise chaque commande et
 * chacune de ses lignes de sortie (ADR 0087 § 9 : la journalisation
 * est une responsabilité du contexte, pas du runner — le port retourne
 * le résultat brut, le journal reçoit tout au fil de l'eau).
 */
internal class CommandRunnerJournalise(
    private val delegue: CommandRunner,
    private val surLigne: (String) -> Unit,
) : CommandRunner {
    override suspend fun run(command: CommandSpec): CommandResult {
        surLigne("$ ${command.program} ${command.arguments.joinToString(" ")}".trimEnd())
        val resultat = delegue.run(command)
        resultat.stdout.forEach(surLigne)
        resultat.stderr.forEach { surLigne(it) }
        return resultat
    }
}
